#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

output "account_role_definition_id" {
  description = "Resource id of the account-scope custom role; null when the executor holds no account action or no account is named."
  value       = one(azurerm_role_definition.account[*].role_definition_resource_id)
}

output "container_role_definition_id" {
  description = "Resource id of the container-scope custom role; null unless object_actions is true and containers are named."
  value       = one(azurerm_role_definition.container[*].role_definition_resource_id)
}

output "account_actions" {
  description = "Control-plane actions of the account role, from permissions.yaml."
  value       = local.account_actions
}

output "container_data_actions" {
  description = "Data actions of the container role, from permissions.yaml; granted only when object_actions is true."
  value       = local.grant_containers ? local.container_data_actions : []
}

output "container_condition" {
  description = "Role assignment condition (version 2.0) on the container assignments, from permissions.yaml; null when the executor's container permissions carry none (the delete Actor)."
  value       = local.grant_containers ? local.container_condition : null
}
