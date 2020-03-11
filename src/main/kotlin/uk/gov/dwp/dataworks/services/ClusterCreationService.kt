package uk.gov.dwp.dataworks.services

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.emr.EmrAsyncClient
import software.amazon.awssdk.services.emr.model.*
import software.amazon.awssdk.services.emr.model.MarketType.ON_DEMAND
import software.amazon.awssdk.services.emr.model.MarketType.SPOT
import uk.gov.dwp.dataworks.model.CreationRequest
import uk.gov.dwp.dataworks.model.CustomInstanceConfig
import uk.gov.dwp.dataworks.model.EmrConfiguration
import uk.gov.dwp.dataworks.model.Step
import uk.gov.dwp.dataworks.services.ConfigKey.*

@Service
class ClusterCreationService {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(ClusterCreationService::class.java)
    }

    @Autowired
    private lateinit var configService: ConfigurationService

    fun jobFlowRoleIsBlacklisted(role: String): Boolean {
        return configService.getListConfig(JOB_FLOW_ROLE_BLACKLIST).contains(role)
    }

    fun submitStepRequest(clusterName: String, creationRequest: CreationRequest) {
        val serviceRole: String = configService.getIfEmpty(creationRequest.serviceRole, SERVICE_ROLE)
        val jobFlowRole: String = configService.getIfEmpty(creationRequest.jobFlowRole, JOB_FLOW_ROLE)
        val autoScalingRole: String = configService.getIfEmpty(creationRequest.autoScalingRole, AUTO_SCALING_ROLE)
        val hostedZoneId: String = configService.getIfEmpty(creationRequest.hostedZoneId, HOSTED_ZONE_ID)

        val clusterRequest = RunJobFlowRequest.builder()
                .name(clusterName)
                .visibleToAllUsers(true)
                .releaseLabel(configService.getStringConfig(EMR_RELEASE_LABEL))
                .customAmiId(getAmiId())
                .repoUpgradeOnBoot(RepoUpgradeOnBoot.NONE)
                .steps(formatSteps(creationRequest.steps))
                .logUri(configService.getStringConfig(S3_LOG_URI))
                .serviceRole(serviceRole)
                .jobFlowRole(jobFlowRole)
                .autoScalingRole(autoScalingRole)
                .securityConfiguration(configService.getStringConfig(SECURITY_CONFIGURATION))
                .applications(creationRequest.applications.map { Application.builder().name(it).build() })
                .instances(formatInstanceConfig(creationRequest.customInstanceConfig))
                .configurations(formatEmrConfigs(creationRequest.customEmrConfigs))
                .tags(Tag.builder().key("createdBy").value("clusterBroker").key("hostedZoneId").value(hostedZoneId).build())
                .build()

        logger.info("Starting cluster $clusterName.")
        val response = EmrAsyncClient.builder().region(configService.awsRegion).build().runJobFlow(clusterRequest)
        response.whenComplete { _, prevStepError ->
            if (prevStepError != null)
                logger.error("Failed to start cluster $clusterName", prevStepError)
        }
    }

    fun getAmiId(): String {
        val images = Ec2Client.builder().region(configService.awsRegion).build()
                .describeImages(
                        DescribeImagesRequest.builder()
                                .filters(Filter.builder()
                                        .name("name")
                                        .values(configService.getStringConfig(AMI_SEARCH_PATTERN)).build())
                                .build())
                .images()
                .toMutableList()
        images.sortByDescending { it.creationDate() }
        return images.first().imageId()
    }

    fun formatSteps(steps: Iterable<Step>): List<StepConfig> {
        return steps.map {
            StepConfig.builder()
                    .name(it.name)
                    .actionOnFailure(it.actionOnFailure)
                    .hadoopJarStep(HadoopJarStepConfig.builder().jar(it.jarPath).build())
                    .build()
        }
    }

    fun formatInstanceConfig(customInstanceConfig: CustomInstanceConfig): JobFlowInstancesConfig {
        val instancesConfigBuilder = customInstanceConfig.instanceTemplate.get()

        val instanceGroups = instancesConfigBuilder.build().instanceGroups()
                .map { it.toBuilder() }
                .map { it.market(if (customInstanceConfig.useSpotPricing) SPOT else ON_DEMAND).build() }

        return instancesConfigBuilder
                .instanceGroups(instanceGroups)
                .ec2SubnetId(customInstanceConfig.ec2SubnetId)
                .keepJobFlowAliveWhenNoSteps(customInstanceConfig.keepAlivePostJob)
                .build()
    }

    fun formatEmrConfigs(customEmrConfigs: List<EmrConfiguration>): List<Configuration> {
        return customEmrConfigs.map {
            Configuration.builder()
                    .classification(it.classification)
                    .properties(it.properties)
                    .build()
        }
    }
}
