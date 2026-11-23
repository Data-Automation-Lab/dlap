#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# DLAP Actor for GCP (IAM-04). One instance of this module per retire executor and project:
# executor = "delete" (da-autodelete) or "archive" (da-autoarchival). The permission lists come
# from permissions.yaml, where every permission names the SDK call that needs it. See
# docs/guides/cloud-permissions.md.
#
# Creates:
#   - a service account (optional; pass existing_service_account_email to reuse one);
#   - dlap<Executor>ActorBucket, granted on each named bucket only;
#   - dlap<Executor>ActorObjects (only when object_actions is true), granted on the same named
#     buckets only;
#   - dlap<Executor>ActorDataset (only when datasets are named), granted on each named dataset only.
# Nothing is granted on the project, folder or organization. Creates no key.

locals {
  trace = yamldecode(file("${path.module}/permissions.yaml"))

  bucket_permissions  = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "bucket") && contains(p.executors, var.executor)])
  object_permissions  = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "bucket_objects") && contains(p.executors, var.executor)])
  dataset_permissions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "dataset") && contains(p.executors, var.executor)])

  role_prefix = "dlap${title(var.executor)}Actor"

  buckets  = toset(var.buckets)
  datasets = toset(var.datasets)

  grant_buckets  = length(local.buckets) > 0 && length(local.bucket_permissions) > 0
  grant_objects  = var.object_actions && length(local.buckets) > 0 && length(local.object_permissions) > 0
  grant_datasets = length(local.datasets) > 0 && length(local.dataset_permissions) > 0

  actor_email  = var.existing_service_account_email != "" ? var.existing_service_account_email : one(google_service_account.actor[*].email)
  actor_member = "serviceAccount:${local.actor_email}"
}

check "service_account_named" {
  assert {
    condition     = var.existing_service_account_email != "" || var.service_account_id != ""
    error_message = "set service_account_id to create the Actor service account, or existing_service_account_email to reuse one."
  }
}

check "datasets_only_for_an_executor_that_uses_them" {
  assert {
    condition     = length(local.datasets) == 0 || length(local.dataset_permissions) > 0
    error_message = "datasets are named but this executor holds no BigQuery permission; only the delete executor sets table expirations."
  }
}

resource "google_service_account" "actor" {
  count        = var.existing_service_account_email == "" ? 1 : 0
  project      = var.project_id
  account_id   = var.service_account_id
  display_name = "DLAP ${var.executor} Actor (lifecycle rules on named buckets and datasets)"
}

resource "google_project_iam_custom_role" "bucket" {
  count       = local.grant_buckets ? 1 : 0
  project     = var.project_id
  role_id     = "${local.role_prefix}Bucket"
  title       = "DLAP ${var.executor} Actor (bucket)"
  description = "Lifecycle rules of the named buckets. Granted on those buckets only. Source: deployment/iam/gcp/actor/permissions.yaml."
  permissions = local.bucket_permissions
  stage       = "GA"
}

resource "google_storage_bucket_iam_member" "bucket" {
  for_each = local.grant_buckets ? local.buckets : toset([])
  bucket   = each.value
  role     = google_project_iam_custom_role.bucket[0].id
  member   = local.actor_member
}

resource "google_project_iam_custom_role" "objects" {
  count       = local.grant_objects ? 1 : 0
  project     = var.project_id
  role_id     = "${local.role_prefix}Objects"
  title       = "DLAP ${var.executor} Actor (single objects)"
  description = "Single-object delete or archive in the named buckets. Granted on those buckets only. Source: deployment/iam/gcp/actor/permissions.yaml."
  permissions = local.object_permissions
  stage       = "GA"
}

resource "google_storage_bucket_iam_member" "objects" {
  for_each = local.grant_objects ? local.buckets : toset([])
  bucket   = each.value
  role     = google_project_iam_custom_role.objects[0].id
  member   = local.actor_member
}

resource "google_project_iam_custom_role" "dataset" {
  count       = local.grant_datasets ? 1 : 0
  project     = var.project_id
  role_id     = "${local.role_prefix}Dataset"
  title       = "DLAP ${var.executor} Actor (table expiration)"
  description = "Table expirations in the named datasets. Granted on those datasets only. Source: deployment/iam/gcp/actor/permissions.yaml."
  permissions = local.dataset_permissions
  stage       = "GA"
}

resource "google_bigquery_dataset_iam_member" "dataset" {
  for_each   = local.grant_datasets ? local.datasets : toset([])
  project    = var.project_id
  dataset_id = each.value
  role       = google_project_iam_custom_role.dataset[0].id
  member     = local.actor_member
}
