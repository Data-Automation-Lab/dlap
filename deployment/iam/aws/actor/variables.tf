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

variable "role_name" {
  description = "Name of the Actor role, its policies and its instance profile, for example dlap-delete-actor."
  type        = string
}

variable "buckets" {
  description = "Names of the buckets this Actor may change. The role holds no action on any other bucket."
  type        = list(string)

  validation {
    condition     = length(var.buckets) > 0 && alltrue([for b in var.buckets : can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", b))])
    error_message = "buckets must name at least one bucket, each a bucket name (no ARN, no wildcard)."
  }
}

variable "object_actions" {
  description = "Grant the single-object actions (delete one object, or archive and restore one object) on the objects of the named buckets. False keeps the Actor to lifecycle rules."
  type        = bool
  default     = false
}

variable "trusted_principal_arn" {
  description = "Principal that assumes the role: DLAP's runtime identity (the IRSA role or the pilot VM's instance profile role). Empty: the role trusts EC2 and gets its own instance profile."
  type        = string
  default     = ""
}

variable "external_id" {
  description = "External id the trusted principal must send. Required with trusted_principal_arn."
  type        = string
  default     = ""
  sensitive   = true
}
