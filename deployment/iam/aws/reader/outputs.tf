#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

output "reader_role_arn" {
  description = "The Reader role. Set AWS_ROLE_ARN or a profile role_arn to it on the DLAP host."
  value       = aws_iam_role.reader.arn
}

output "reader_instance_profile" {
  description = "Instance profile for the pilot VM; null when the role trusts an outside principal."
  value       = local.trust_principal ? null : aws_iam_instance_profile.reader[0].name
}

output "reader_actions" {
  description = "Actions the Reader role is granted, from permissions.yaml. The log bucket actions appear only when a log bucket is named."
  value       = local.reader_actions
}

output "reader_log_buckets" {
  description = "The log buckets whose objects the Reader may read; empty when none is named."
  value       = local.log_buckets
}
