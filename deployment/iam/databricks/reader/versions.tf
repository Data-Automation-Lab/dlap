#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# Configure the provider from the environment (DATABRICKS_HOST plus OAuth or token variables);
# no credential belongs in this module.

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    databricks = {
      source  = "databricks/databricks"
      version = ">= 1.50.0, < 2.0.0"
    }
  }
}
