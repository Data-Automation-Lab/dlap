#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

output "reader_role_definition_id" {
  description = "Resource id of the custom role."
  value       = azurerm_role_definition.reader.role_definition_resource_id
}

output "reader_actions" {
  description = "Actions of the custom role, from permissions.yaml."
  value       = local.reader_actions
}
