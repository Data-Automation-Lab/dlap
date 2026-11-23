#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

variable "project_id" {
  description = "Project that holds the Reader service account and the dlapReader custom role. It is also monitored and is the BigQuery billing project."
  type        = string
}

variable "monitored_project_ids" {
  description = "Further projects to discover and read logs from. The dlapReader custom role is created in each one, because a project custom role can be granted only inside its own project."
  type        = list(string)
  default     = []
}

variable "service_account_id" {
  description = "Account id of the Reader service account to create. Ignored when existing_service_account_email is set."
  type        = string
  default     = "dlap-reader"
}

variable "existing_service_account_email" {
  description = "Use this service account instead of creating one, for example the pilot VM's attached account."
  type        = string
  default     = ""
}

variable "organization_id" {
  description = "Numeric organization id. When set, the dlapReaderOrg organization role is created for folder crawling and inherited audit configs."
  type        = string
  default     = ""
}

variable "grant_on_organization" {
  description = "Grant dlapReaderOrg on the whole organization. When false, it is granted only on folder_ids."
  type        = bool
  default     = false
}

variable "folder_ids" {
  description = "Folder numbers (without the folders/ prefix) to grant dlapReaderOrg on. Needs organization_id."
  type        = list(string)
  default     = []
}

variable "log_sink_project_id" {
  description = "Project of the log sink bucket and dataset. Defaults to project_id."
  type        = string
  default     = ""
}

variable "log_sink_bucket" {
  description = "Cloud Storage bucket that a Logging sink exports audit logs to. Empty when no GCS sink is read."
  type        = string
  default     = ""
}

variable "log_sink_dataset" {
  description = "BigQuery dataset id that a Logging sink exports audit logs to. Empty when no BigQuery sink is read."
  type        = string
  default     = ""
}
