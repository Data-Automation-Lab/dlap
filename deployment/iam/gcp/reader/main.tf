#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# DLAP Reader role for GCP (IAM-03). Read-only: discovery, audit-log ingest and read coverage.
# The permission list is not repeated here: it comes from permissions.yaml, where every
# permission names the SDK call that needs it. See docs/guides/cloud-permissions.md.
#
# Creates:
#   - a service account (optional; pass existing_service_account_email to reuse one);
#   - dlapReader in every monitored project, granted to the account there;
#   - dlapReaderOrg in the organization (optional), granted on the organization or on folders;
#   - dlapLogSinkBucketReader and dlapLogSinkDatasetReader in the log sink project (optional),
#     granted on the sink bucket and the sink dataset only, so object and table content is
#     readable nowhere else. One role per resource type: the bucket role holds storage
#     permissions only and the dataset role BigQuery permissions only.
# Creates no key. Attach the account to the pilot VM, or use Workload Identity Federation.

locals {
  trace = yamldecode(file("${path.module}/permissions.yaml"))

  project_permissions      = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "project")])
  organization_permissions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "organization")])
  sink_bucket_permissions  = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "log_sink_bucket")])
  sink_dataset_permissions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "log_sink_dataset")])

  monitored_projects = toset(distinct(concat([var.project_id], var.monitored_project_ids)))
  sink_project       = var.log_sink_project_id != "" ? var.log_sink_project_id : var.project_id

  reader_email  = var.existing_service_account_email != "" ? var.existing_service_account_email : google_service_account.reader[0].email
  reader_member = "serviceAccount:${local.reader_email}"
}

resource "google_service_account" "reader" {
  count        = var.existing_service_account_email == "" ? 1 : 0
  project      = var.project_id
  account_id   = var.service_account_id
  display_name = "DLAP Reader (read-only discovery and audit-log ingest)"
}

resource "google_project_iam_custom_role" "reader" {
  for_each    = local.monitored_projects
  project     = each.value
  role_id     = "dlapReader"
  title       = "DLAP Reader"
  description = "Read-only metadata, audit logs and read coverage for DLAP. Source: deployment/iam/gcp/reader/permissions.yaml."
  permissions = local.project_permissions
  stage       = "GA"
}

resource "google_project_iam_member" "reader" {
  for_each = local.monitored_projects
  project  = each.value
  role     = google_project_iam_custom_role.reader[each.value].id
  member   = local.reader_member
}

resource "google_organization_iam_custom_role" "reader_org" {
  count       = var.organization_id != "" ? 1 : 0
  org_id      = var.organization_id
  role_id     = "dlapReaderOrg"
  title       = "DLAP Reader (folders and inherited audit configs)"
  description = "Folder listing and inherited auditConfigs for DLAP. Source: deployment/iam/gcp/reader/permissions.yaml."
  permissions = local.organization_permissions
  stage       = "GA"
}

resource "google_organization_iam_member" "reader_org" {
  count  = var.organization_id != "" && var.grant_on_organization ? 1 : 0
  org_id = var.organization_id
  role   = google_organization_iam_custom_role.reader_org[0].id
  member = local.reader_member
}

resource "google_folder_iam_member" "reader_org" {
  for_each = var.organization_id != "" ? toset(var.folder_ids) : toset([])
  folder   = "folders/${each.value}"
  role     = google_organization_iam_custom_role.reader_org[0].id
  member   = local.reader_member
}

resource "google_project_iam_custom_role" "log_sink_bucket_reader" {
  count       = var.log_sink_bucket != "" ? 1 : 0
  project     = local.sink_project
  role_id     = "dlapLogSinkBucketReader"
  title       = "DLAP Log Sink Bucket Reader"
  description = "Reads exported audit logs. Granted on the log sink bucket only. Source: deployment/iam/gcp/reader/permissions.yaml."
  permissions = local.sink_bucket_permissions
  stage       = "GA"
}

resource "google_storage_bucket_iam_member" "log_sink_reader" {
  count  = var.log_sink_bucket != "" ? 1 : 0
  bucket = var.log_sink_bucket
  role   = google_project_iam_custom_role.log_sink_bucket_reader[0].id
  member = local.reader_member
}

resource "google_project_iam_custom_role" "log_sink_dataset_reader" {
  count       = var.log_sink_dataset != "" ? 1 : 0
  project     = local.sink_project
  role_id     = "dlapLogSinkDatasetReader"
  title       = "DLAP Log Sink Dataset Reader"
  description = "Queries exported audit logs. Granted on the log sink dataset only. Source: deployment/iam/gcp/reader/permissions.yaml."
  permissions = local.sink_dataset_permissions
  stage       = "GA"
}

resource "google_bigquery_dataset_iam_member" "log_sink_reader" {
  count      = var.log_sink_dataset != "" ? 1 : 0
  project    = local.sink_project
  dataset_id = var.log_sink_dataset
  role       = google_project_iam_custom_role.log_sink_dataset_reader[0].id
  member     = local.reader_member
}
