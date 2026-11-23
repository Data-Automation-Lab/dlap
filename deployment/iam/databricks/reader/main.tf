#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# DLAP Reader for Databricks Unity Catalog (IAM-03). Grants BROWSE, USE_CATALOG and USE_SCHEMA
# on each listed catalog to one service principal. The privileges come from permissions.yaml,
# where every privilege names the call that needs it. See docs/guides/cloud-permissions.md.
#
# Uses databricks_grant (one principal, additive), never databricks_grants, which would replace
# every other grant on the catalog. Creates no token: issue an OAuth secret or token for the
# service principal through your normal process and set CLOUD_PROVIDER_UNITY_TOKEN.

locals {
  trace      = yamldecode(file("${path.module}/permissions.yaml"))
  privileges = sort([for p in local.trace.permissions : p.permission])
  principal  = var.existing_application_id != "" ? var.existing_application_id : databricks_service_principal.reader[0].application_id
}

resource "databricks_service_principal" "reader" {
  count        = var.existing_application_id == "" ? 1 : 0
  display_name = var.service_principal_name
}

resource "databricks_grant" "reader" {
  for_each   = toset(var.catalogs)
  catalog    = each.value
  principal  = local.principal
  privileges = local.privileges
}
