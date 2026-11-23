#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# DLAP Actor role for AWS (IAM-04), the Terraform twin of actor-role.cfn.yaml. One instance of
# this module per retire executor: executor = "delete" (da-autodelete) or "archive"
# (da-autoarchival). The action lists come from permissions.yaml, where every action names the
# SDK call that needs it. See docs/guides/cloud-permissions.md. Creates no access key.
#
# Creates:
#   - the bucket policy: the bucket scope on the named bucket ARNs only;
#   - the object policy (only when object_actions is true and the executor has object actions):
#     the bucket_objects scope on the objects of the named buckets only. Never Resource "*".

locals {
  trace = yamldecode(file("${path.module}/permissions.yaml"))

  bucket_actions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "bucket") && contains(p.executors, var.executor)])
  object_actions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "bucket_objects") && contains(p.executors, var.executor)])

  buckets     = sort(distinct(var.buckets))
  bucket_arns = [for b in local.buckets : "arn:${data.aws_partition.current.partition}:s3:::${b}"]
  object_arns = [for b in local.buckets : "arn:${data.aws_partition.current.partition}:s3:::${b}/*"]

  grant_objects   = var.object_actions && length(local.object_actions) > 0
  actor_actions   = sort(distinct(concat(local.bucket_actions, local.grant_objects ? local.object_actions : [])))
  trust_principal = var.trusted_principal_arn != ""
}

data "aws_partition" "current" {}

check "external_id_with_principal" {
  assert {
    condition     = !local.trust_principal || var.external_id != ""
    error_message = "external_id is required when trusted_principal_arn is set."
  }
}

data "aws_iam_policy_document" "actor_buckets" {
  statement {
    sid       = "DlapActorBuckets"
    effect    = "Allow"
    actions   = local.bucket_actions
    resources = local.bucket_arns
  }
}

data "aws_iam_policy_document" "actor_objects" {
  count = local.grant_objects ? 1 : 0

  statement {
    sid       = "DlapActorObjects"
    effect    = "Allow"
    actions   = local.object_actions
    resources = local.object_arns
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

resource "aws_iam_role" "actor" {
  name                 = var.role_name
  description          = "DLAP ${var.executor} Actor. Lifecycle rules on the named buckets only."
  assume_role_policy   = data.aws_iam_policy_document.trust.json
  max_session_duration = 3600
}

resource "aws_iam_policy" "actor_buckets" {
  name        = "${var.role_name}-buckets-policy"
  description = "DLAP ${var.executor} Actor: the named buckets only. Source of truth deployment/iam/aws/actor/permissions.yaml."
  policy      = data.aws_iam_policy_document.actor_buckets.json
}

resource "aws_iam_role_policy_attachment" "actor_buckets" {
  role       = aws_iam_role.actor.name
  policy_arn = aws_iam_policy.actor_buckets.arn
}

resource "aws_iam_policy" "actor_objects" {
  count       = local.grant_objects ? 1 : 0
  name        = "${var.role_name}-objects-policy"
  description = "DLAP ${var.executor} Actor: single objects of the named buckets only. Source of truth deployment/iam/aws/actor/permissions.yaml."
  policy      = data.aws_iam_policy_document.actor_objects[0].json
}

resource "aws_iam_role_policy_attachment" "actor_objects" {
  count      = local.grant_objects ? 1 : 0
  role       = aws_iam_role.actor.name
  policy_arn = aws_iam_policy.actor_objects[0].arn
}

resource "aws_iam_instance_profile" "actor" {
  count = local.trust_principal ? 0 : 1
  name  = var.role_name
  role  = aws_iam_role.actor.name
}
