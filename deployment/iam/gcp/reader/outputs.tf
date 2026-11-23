#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

output "reader_service_account_email" {
  description = "The Reader service account. Attach it to the pilot VM (no key file)."
  value       = local.reader_email
}

output "project_role_permissions" {
  description = "Permissions of dlapReader, from permissions.yaml."
  value       = local.project_permissions
}

output "organization_role_permissions" {
  description = "Permissions of dlapReaderOrg, from permissions.yaml."
  value       = local.organization_permissions
}

output "log_sink_bucket_role_permissions" {
  description = "Permissions of dlapLogSinkBucketReader, from permissions.yaml."
  value       = local.sink_bucket_permissions
}

output "log_sink_dataset_role_permissions" {
  description = "Permissions of dlapLogSinkDatasetReader, from permissions.yaml."
  value       = local.sink_dataset_permissions
}
