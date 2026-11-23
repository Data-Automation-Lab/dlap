#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# DLAP Actor for Azure (IAM-04, IAM-06). One instance of this module per retire executor: executor =
# "delete" (da-autodelete) or "archive" (da-autoarchival), each with its own role_name, so the two
# Actors never share a role. The actions come from permissions.yaml, where every action names the
# call that needs it. See docs/guides/cloud-permissions.md.
#
# Creates:
#   - "<role_name> (account)": control-plane actions, assignable to and assigned on the named
#     storage accounts only (the delete Actor; the archive Actor holds none);
#   - "<role_name> (container)" (only when object_actions is true): blob data actions, assignable
#     to and assigned on the named containers only. Delete: blobs/delete. Archive: blobs/read and
#     blobs/write, the write narrowed by the role assignment condition of permissions.yaml to the
#     Set Blob Tier suboperation (Blob.Write.Tier).
# Creates no secret.

provider "azurerm" {
  features {}
  subscription_id = var.subscription_id
}

locals {
  trace = yamldecode(file("${path.module}/permissions.yaml"))

  account_actions        = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "storage_account") && contains(p.executors, var.executor)])
  container_data_actions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, "container") && contains(p.executors, var.executor)])

  # Role assignment conditions (ABAC, version 2.0) of the executor's container permissions, joined
  # with AND; null when there is none, so an assignment without a condition stays as it was.
  container_conditions = [for p in local.trace.permissions : p.condition if contains(p.scopes, "container") && contains(p.executors, var.executor) && try(p.condition, "") != ""]
  container_condition  = length(local.container_conditions) > 0 ? join(" AND ", local.container_conditions) : null

  storage_account_ids = toset(var.storage_account_ids)
  container_ids       = toset(var.container_ids)

  grant_accounts   = length(local.storage_account_ids) > 0 && length(local.account_actions) > 0
  grant_containers = var.object_actions && length(local.container_ids) > 0 && length(local.container_data_actions) > 0
  assign           = var.principal_object_id != ""
}

resource "azurerm_role_definition" "account" {
  count       = local.grant_accounts ? 1 : 0
  name        = "${var.role_name} (account)"
  scope       = "/subscriptions/${var.subscription_id}"
  description = "DLAP ${var.executor} Actor: lifecycle management policy of the named storage accounts. Source: deployment/iam/azure/actor/permissions.yaml."

  permissions {
    actions      = local.account_actions
    not_actions  = []
    data_actions = []
  }

  assignable_scopes = sort(tolist(local.storage_account_ids))
}

resource "azurerm_role_assignment" "account" {
  for_each           = local.grant_accounts && local.assign ? local.storage_account_ids : toset([])
  scope              = each.value
  role_definition_id = azurerm_role_definition.account[0].role_definition_resource_id
  principal_id       = var.principal_object_id
}

resource "azurerm_role_definition" "container" {
  count       = local.grant_containers ? 1 : 0
  name        = "${var.role_name} (container)"
  scope       = "/subscriptions/${var.subscription_id}"
  description = "DLAP ${var.executor} Actor: single blobs of the named containers. Source: deployment/iam/azure/actor/permissions.yaml."

  permissions {
    actions      = []
    not_actions  = []
    data_actions = local.container_data_actions
  }

  assignable_scopes = sort(tolist(local.container_ids))

  lifecycle {
    precondition {
      condition     = var.executor != "archive" || strcontains(lower(var.role_name), "archive")
      error_message = "The archive Actor's role_name must contain \"archive\", so it never shares a role with the delete Actor."
    }
  }
}

resource "azurerm_role_assignment" "container" {
  for_each           = local.grant_containers && local.assign ? local.container_ids : toset([])
  scope              = each.value
  role_definition_id = azurerm_role_definition.container[0].role_definition_resource_id
  principal_id       = var.principal_object_id
  condition          = local.container_condition
  condition_version  = local.container_condition == null ? null : "2.0"
}
