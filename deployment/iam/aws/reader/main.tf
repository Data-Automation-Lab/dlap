#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# DLAP Reader role for AWS (IAM-03), the Terraform twin of reader-role.cfn.yaml. The action
# list comes from permissions.yaml, where every action names the SDK call that needs it.
# See docs/guides/cloud-permissions.md. Creates no access key.
#
# Creates:
#   - the Reader policy: the account scope, Resource "*" (metadata, CloudTrail event history);
#   - the log bucket policy (only when cloudtrail_log_bucket or s3_access_log_bucket is set): the
#     log_bucket scope on the named bucket ARNs and the log_bucket_objects scope on their objects,
#     so object content is readable in the named log buckets and nowhere else.

locals {
  trace = yamldecode(file("${path.module}/permissions.yaml"))

  account_actions    = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "account")])
  log_bucket_actions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "log_bucket")])
  log_object_actions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "log_bucket_objects")])

  log_buckets     = sort(distinct(compact([var.cloudtrail_log_bucket, var.s3_access_log_bucket])))
  log_bucket_arns = [for b in local.log_buckets : "arn:${data.aws_partition.current.partition}:s3:::${b}"]
  log_object_arns = [for b in local.log_buckets : "arn:${data.aws_partition.current.partition}:s3:::${b}/*"]

  reader_actions  = sort(distinct(concat(local.account_actions, length(local.log_buckets) > 0 ? concat(local.log_bucket_actions, local.log_object_actions) : [])))
  trust_principal = var.trusted_principal_arn != ""
}

data "aws_partition" "current" {}

check "external_id_with_principal" {
  assert {
    condition     = !local.trust_principal || var.external_id != ""
    error_message = "external_id is required when trusted_principal_arn is set."
  }
}

data "aws_iam_policy_document" "reader" {
  statement {
    sid       = "DlapReaderMetadataAndLogs"
    effect    = "Allow"
    actions   = local.account_actions
    resources = ["*"]
  }
}

data "aws_iam_policy_document" "reader_log_buckets" {
  count = length(local.log_buckets) > 0 ? 1 : 0

  statement {
    sid       = "DlapReaderLogBuckets"
    effect    = "Allow"
    actions   = local.log_bucket_actions
    resources = local.log_bucket_arns
  }

  statement {
    sid       = "DlapReaderLogObjects"
    effect    = "Allow"
    actions   = local.log_object_actions
    resources = local.log_object_arns
  }
}

data "aws_iam_policy_document" "trust" {
  dynamic "statement" {
    for_each = local.trust_principal ? [1] : []
    content {
      effect  = "Allow"
      actions = ["sts:AssumeRole"]
      principals {
        type        = "AWS"
        identifiers = [var.trusted_principal_arn]
      }
      condition {
        test     = "StringEquals"
        variable = "sts:ExternalId"
        values   = [var.external_id]
      }
    }
  }

  dynamic "statement" {
    for_each = local.trust_principal ? [] : [1]
    content {
      effect  = "Allow"
      actions = ["sts:AssumeRole"]
      principals {
        type        = "Service"
        identifiers = ["ec2.amazonaws.com"]
      }
    }
  }
}

resource "aws_iam_policy" "reader" {
  name        = "${var.role_name}-policy"
  description = "DLAP Reader. Source of truth deployment/iam/aws/reader/permissions.yaml."
  policy      = data.aws_iam_policy_document.reader.json
}

resource "aws_iam_role" "reader" {
  name                 = var.role_name
  description          = "DLAP Reader. Read-only; holds no write action."
  assume_role_policy   = data.aws_iam_policy_document.trust.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy_attachment" "reader" {
  role       = aws_iam_role.reader.name
  policy_arn = aws_iam_policy.reader.arn
}

resource "aws_iam_policy" "reader_log_buckets" {
  count       = length(local.log_buckets) > 0 ? 1 : 0
  name        = "${var.role_name}-log-buckets-policy"
  description = "DLAP Reader: list and read the named log buckets only. Source of truth deployment/iam/aws/reader/permissions.yaml."
  policy      = data.aws_iam_policy_document.reader_log_buckets[0].json
}

resource "aws_iam_role_policy_attachment" "reader_log_buckets" {
  count      = length(local.log_buckets) > 0 ? 1 : 0
  role       = aws_iam_role.reader.name
  policy_arn = aws_iam_policy.reader_log_buckets[0].arn
}

resource "aws_iam_instance_profile" "reader" {
  count = local.trust_principal ? 0 : 1
  name  = var.role_name
  role  = aws_iam_role.reader.name
}
