#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

variable "role_name" {
  description = "Name of the Reader role, its policy and its instance profile."
  type        = string
  default     = "dlap-reader"
}

variable "trusted_principal_arn" {
  description = "Principal that assumes the role from outside this account. Empty: the role trusts EC2 and gets an instance profile for the pilot VM."
  type        = string
  default     = ""
}

variable "external_id" {
  description = "External id the trusted principal must send. Required with trusted_principal_arn."
  type        = string
  default     = ""
  sensitive   = true
}

variable "cloudtrail_log_bucket" {
  description = "Name of the bucket a CloudTrail trail delivers its trail files to. The Reader may list it and read its objects; no other bucket's objects are readable. Empty when DLAP reads no trail files."
  type        = string
  default     = ""

  validation {
    condition     = var.cloudtrail_log_bucket == "" || can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.cloudtrail_log_bucket))
    error_message = "cloudtrail_log_bucket must be a bucket name (no ARN, no wildcard) or empty."
  }
}

variable "s3_access_log_bucket" {
  description = "Name of the bucket S3 server access logging delivers to. The Reader may list it and read its objects; no other bucket's objects are readable. Empty when DLAP reads no access logs."
  type        = string
  default     = ""

  validation {
    condition     = var.s3_access_log_bucket == "" || can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.s3_access_log_bucket))
    error_message = "s3_access_log_bucket must be a bucket name (no ARN, no wildcard) or empty."
  }
}
