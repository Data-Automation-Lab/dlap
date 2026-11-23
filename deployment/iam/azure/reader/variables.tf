#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#

variable "subscription_id" {
  description = "Subscription to discover and read coverage from. The role is defined and assigned here."
  type        = string
}

variable "role_name" {
  description = "Name of the custom role."
  type        = string
  default     = "DLAP Reader"
}

variable "principal_object_id" {
  description = "Object id of the managed identity or service principal DLAP runs as. Empty: define the role only."
  type        = string
  default     = ""
}
