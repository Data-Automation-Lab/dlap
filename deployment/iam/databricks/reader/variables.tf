#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

variable "catalogs" {
  description = "Unity Catalog catalogs DLAP discovers. The privileges are granted on each one."
  type        = list(string)
}

variable "service_principal_name" {
  description = "Display name of the Reader service principal to create. Ignored with existing_application_id."
  type        = string
  default     = "dlap-reader"
}

variable "existing_application_id" {
  description = "Application id of an existing service principal to grant instead of creating one."
  type        = string
  default     = ""
}
