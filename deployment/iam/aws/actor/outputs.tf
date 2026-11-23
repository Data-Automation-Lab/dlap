#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

output "actor_role_arn" {
  description = "The Actor role. Name it in dlap.permission-check.aws.executors.<executor>.role-arn on da-admin-service."
  value       = aws_iam_role.actor.arn
}

output "actor_instance_profile" {
  description = "Instance profile for the host that runs the executor; null when the role trusts an outside principal."
  value       = one(aws_iam_instance_profile.actor[*].name)
}

output "actor_actions" {
  description = "Actions the Actor role is granted, from permissions.yaml. The object actions appear only when object_actions is true."
  value       = local.actor_actions
}

output "actor_buckets" {
  description = "The buckets the Actor may change."
  value       = local.buckets
}
