variable "name" {
  type        = string
  description = "(Required) common name"
}

variable "common_tags" {
  type        = map(string)
  description = "(Required) common tags to apply to aws resources"
}

variable "vpc" {
  description = "(Required) vpc configuration block"
  type = object({
    id                  = string
    cidr_block          = string
    main_route_table_id = string
  })
}

variable "role_arn" {
  type        = map
  description = "(Required) The role to assume when doing an apply"
}

variable "region" {
  type        = string
  description = "(Required) The region to deploy into"
}

variable "internet_proxy_service_name" {
  description = "Internet Proxy VPC Endpoint Service name"
  type        = string
}

variable "frontend_vpc" {
  description = "(Optional) The Frontend VPC Used for Peering"
  type        = string
}
