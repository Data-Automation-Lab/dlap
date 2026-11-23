#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

variable "executor" {
  description = "The retire executor this Actor serves: delete (da-autodelete) or archive (da-autoarchival). Apply the module once per executor."
  type        = string

  validation {
    condition     = contains(["delete", "archive"], var.executor)
    error_message = "executor must be delete or archive."
  }
}

variable "subscription_id" {
  description = "Subscription that holds the named storage accounts. The custom roles are defined here and are assignable only to the named resources."
  type        = string
}

variable "role_name" {
  description = "Base name of the custom roles, for example DLAP Delete Actor or DLAP Archive Actor. Give each executor its own name: the delete and archive Actors never share a role."
  type        = string
}

variable "storage_account_ids" {
  description = "ARM ids of the storage accounts whose lifecycle management policy this Actor may change (the same list as dlap.autodelete.native-rule.azure-storage-account-ids)."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for id in var.storage_account_ids : can(regex("^/subscriptions/[0-9a-fA-F-]{36}/resourceGroups/[-\\w._()]{1,90}/providers/Microsoft\\.Storage/storageAccounts/[a-z0-9]{3,24}$", id))])
    error_message = "storage_account_ids must be storage account ARM ids (no wildcard)."
  }
}

variable "object_actions" {
  description = "Grant the per-blob data actions on the named containers: the single-blob delete (executor delete), or the tier change with the tier and size reads (executor archive). False keeps the delete Actor to management policy rules and gives the archive Actor no role."
  type        = bool
  default     = false
}

variable "container_ids" {
  description = "ARM ids of the containers in which this Actor may delete (delete) or re-tier (archive) single blobs: <storage account id>/blobServices/default/containers/<name>. Used only when object_actions is true."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for id in var.container_ids : can(regex("^/subscriptions/[0-9a-fA-F-]{36}/resourceGroups/[-\\w._()]{1,90}/providers/Microsoft\\.Storage/storageAccounts/[a-z0-9]{3,24}/blobServices/default/containers/[a-z0-9](?:[a-z0-9]|-[a-z0-9]){2,62}$", id))])
    error_message = "container_ids must be container ARM ids (no wildcard)."
  }
}

variable "principal_object_id" {
  description = "Object id of the managed identity or service principal the executor runs as. Empty: define the roles only."
  type        = string
  default     = ""
}
