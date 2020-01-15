package uk.gov.dwp.dataworks.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.junit4.SpringRunner
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.emr.model.ActionOnFailure
import software.amazon.awssdk.services.emr.model.HadoopJarStepConfig
import software.amazon.awssdk.services.emr.model.MarketType
import software.amazon.awssdk.services.emr.model.StepConfig
import uk.gov.dwp.dataworks.model.CustomInstanceConfig
import uk.gov.dwp.dataworks.model.InstanceTemplate
import uk.gov.dwp.dataworks.model.Step
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(SpringRunner::class)
@WebMvcTest(ClusterCreationService::class, ConfigurationService::class)
class ClusterCreationServiceTest {

    @Autowired
    private lateinit var configService: ConfigurationService

    @Autowired
    private lateinit var clusterCreationService: ClusterCreationService

    @Before
    fun setEnvVars() {
        System.setProperty(ConfigKey.AMI_SEARCH_PATTERN.key, "redhat*")
        System.setProperty(ConfigKey.JOB_FLOW_ROLE_BLACKLIST.key, "blacklisted_role,another_blacklisted_role")
    }

    @After
    fun clearEnvVars() {
        ConfigKey.values().forEach { System.clearProperty(it.key) }
    }

    @Test
    fun `Formats Steps correctly`() {
        val expectedSteps = listOf(createStep("step1"), createStep("step2"), createStep("step3"))
        val actualSteps = clusterCreationService.formatSteps(listOf(
                Step("step1", ActionOnFailure.CONTINUE, "step1/jar"),
                Step("step2", ActionOnFailure.CONTINUE, "step2/jar"),
                Step("step3", ActionOnFailure.CONTINUE, "step3/jar")))

        assertThat(actualSteps).containsExactlyElementsOf(expectedSteps)
    }

    @Test
    fun `Can reconfigure JobConfig with custom values`() {
        val customConfig = CustomInstanceConfig(true, InstanceTemplate.LARGE, false)
        val actualConfig = clusterCreationService.formatInstanceConfig(customConfig)

        actualConfig.instanceGroups().forEach { assertThat(it.market()).isEqualTo(MarketType.SPOT) }
        assertThat(actualConfig.keepJobFlowAliveWhenNoSteps()).isEqualTo(false)
    }

    @Test
    fun `Validates a role is on blacklist`() {
        val actual = clusterCreationService.jobFlowRoleIsBlacklisted("blacklisted_role")
        assertThat(actual).isTrue()

        val actual2 = clusterCreationService.jobFlowRoleIsBlacklisted("another_blacklisted_role")
        assertThat(actual2).isTrue()
    }

    @Test
    fun `Validates a role is not on blacklist`() {
        val actual = clusterCreationService.jobFlowRoleIsBlacklisted("not_blacklisted_role")
        assertThat(actual).isFalse()
    }

    private fun createStep(stepName: String): StepConfig {
        return StepConfig.builder()
                .name(stepName)
                .actionOnFailure(ActionOnFailure.CONTINUE)
                .hadoopJarStep(HadoopJarStepConfig.builder().jar("$stepName/jar").build())
                .build()
    }
}
