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

variable "project_id" {
  description = "Project that holds the named buckets and datasets, the custom roles and (unless existing_service_account_email is set) the Actor service account. A project custom role can be granted only on resources of its own project."
  type        = string
}

variable "service_account_id" {
  description = "Account id of the Actor service account to create, for example dlap-delete-actor. Ignored when existing_service_account_email is set."
  type        = string
  default     = ""
}

variable "existing_service_account_email" {
  description = "Use this service account instead of creating one, for example the account the executor already runs as."
  type        = string
  default     = ""
}

variable "buckets" {
  description = "Names of the buckets in project_id this Actor may change. The roles are granted on these buckets only."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for b in var.buckets : can(regex("^[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]$", b))])
    error_message = "buckets must be bucket names (no gs:// prefix, no wildcard)."
  }
}

variable "object_actions" {
  description = "Grant the single-object role (delete one object, or rewrite one object to an archive class) on the named buckets. False keeps the Actor to lifecycle rules."
  type        = bool
  default     = false
}

variable "datasets" {
  description = "BigQuery dataset ids in project_id whose tables this Actor may give an expiration. Only the delete executor uses them."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for d in var.datasets : can(regex("^[A-Za-z0-9_]{1,1024}$", d))])
    error_message = "datasets must be dataset ids (letters, digits and underscores; no project prefix, no wildcard)."
  }
}
