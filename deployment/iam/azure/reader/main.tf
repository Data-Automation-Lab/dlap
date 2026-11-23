#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# DLAP Reader role for Azure (IAM-03). A custom role at subscription scope, narrower than the
# built-in Reader (which grants */read). The actions come from permissions.yaml, where every
# action names the call that needs it. See docs/guides/cloud-permissions.md.
# Creates no secret: assign the role to a managed identity or to an existing service principal.

provider "azurerm" {
  features {}
  subscription_id = var.subscription_id
}

locals {
  trace          = yamldecode(file("${path.module}/permissions.yaml"))
  reader_actions = sort([for p in local.trace.permissions : p.permission])
  scope          = "/subscriptions/${var.subscription_id}"
}

resource "azurerm_role_definition" "reader" {
  name        = var.role_name
  scope       = local.scope
  description = "DLAP Reader. Read-only; no data actions. Source: deployment/iam/azure/reader/permissions.yaml."

  permissions {
    actions      = local.reader_actions
    not_actions  = []
    data_actions = []
  }

  assignable_scopes = [local.scope]
}

resource "azurerm_role_assignment" "reader" {
  count              = var.principal_object_id != "" ? 1 : 0
  scope              = local.scope
  role_definition_id = azurerm_role_definition.reader.role_definition_resource_id
  principal_id       = var.principal_object_id
}
