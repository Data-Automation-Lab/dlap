#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

output "reader_application_id" {
  description = "Application id of the Reader service principal."
  value       = local.principal
}

output "reader_privileges" {
  description = "Privileges granted on each catalog, from permissions.yaml."
  value       = local.privileges
}
