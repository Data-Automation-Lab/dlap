#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

output "actor_service_account_email" {
  description = "The Actor service account. Attach it to the host or workload that runs the executor (no key file)."
  value       = local.actor_email
}

output "bucket_role_permissions" {
  description = "Permissions of dlap<Executor>ActorBucket, from permissions.yaml."
  value       = local.bucket_permissions
}

output "object_role_permissions" {
  description = "Permissions of dlap<Executor>ActorObjects, from permissions.yaml; granted only when object_actions is true."
  value       = local.grant_objects ? local.object_permissions : []
}

output "dataset_role_permissions" {
  description = "Permissions of dlap<Executor>ActorDataset, from permissions.yaml."
  value       = local.dataset_permissions
}
