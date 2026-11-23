/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.dlap.discovery.common.constants;

/**
 * Constants for resource type IDs and related literals.
 * This class centralizes string literals used for resource types across the
 * application.
 */
public final class ResourceTypeConstants {
	private ResourceTypeConstants() {
		// Prevent instantiation
	}

	// GCP resource type IDs
	public static final String GCP_COMPUTE_INSTANCE = "gcp_compute_instance";
	public static final String GCP_COMPUTE_DISK = "gcp_compute_disk";
	public static final String GCP_BIGQUERY_DATASET = "gcp_bigquery_dataset";
	public static final String GCP_BIGQUERY_TABLE = "gcp_bigquery_table";
	/**
	 * A BigQuery job (query / load / extract). C7: modeled as a processing-job
	 * asset (maps to service_type {@code BIGQUERY_JOB}) so lineage can attach it
	 * as a real endpoint. The {@code kind} carries "bigquery_job" - matched by
	 * da-catalog's serviceType derivation.
	 */
	public static final String GCP_BIGQUERY_JOB = "gcp_bigquery_job";
	/** A Dataflow job. C7: maps to service_type {@code DATAFLOW_JOB}. */
	public static final String GCP_DATAFLOW_JOB = "gcp_dataflow_job";

	// AWS resource type IDs
	public static final String AWS_EC2_INSTANCE = "aws_ec2_instance";
	public static final String AWS_S3_BUCKET = "aws_s3_bucket";
	public static final String AWS_RDS_INSTANCE = "aws_rds_instance";
	public static final String AWS_DYNAMO_TABLE = "aws_dynamodb_table";
	public static final String AWS_LAMBDA_FUNCTION = "aws_lambda_function";
	/**
	 * An AWS Glue job. V4-AWS: modeled as a processing-job asset so lineage can
	 * attach it as a real endpoint. da-catalog's serviceType derivation maps the
	 * {@code glue_job} kind to {@code SPARK_JOB} (Glue runs Spark) - or
	 * {@code PYTHON_JOB} for a Python-shell Glue job, carried on the asset.
	 */
	public static final String AWS_GLUE_JOB = "aws_glue_job";
	/** An Athena query. V4-AWS: maps to service_type {@code REDSHIFT_QUERY} (neutral SQL-analytics job kind). */
	public static final String AWS_ATHENA_QUERY = "aws_athena_query";
	/** An EMR step. V4-AWS: maps to service_type {@code SPARK_JOB}. */
	public static final String AWS_EMR_STEP = "aws_emr_step";

	// Azure resource type IDs
	public static final String AZURE_VM_INSTANCE = "azure_vm_instance";
	public static final String AZURE_BLOB_CONTAINER = "azure_blob_container";
	public static final String AZURE_SQL_DATABASE = "azure_sql_database";
	/**
	 * An Azure Data Factory pipeline. V4-AZURE: modeled as a processing-job asset so
	 * lineage can attach it as a real endpoint. da-catalog's serviceType derivation
	 * maps the {@code data_factory}/{@code pipeline} kind to {@code DATAFLOW_JOB}
	 * (orchestrated data movement).
	 */
	public static final String AZURE_DATA_FACTORY_PIPELINE = "azure_data_factory_pipeline";
	/** An Azure Synapse Spark job. V4-AZURE: maps to service_type {@code SPARK_JOB}. */
	public static final String AZURE_SYNAPSE_SPARK_JOB = "azure_synapse_spark_job";

	// Databricks resource type IDs
	/**
	 * A Databricks job run. V4-DBX: modeled as a processing-job asset so lineage can
	 * attach it as a real endpoint. da-catalog's serviceType derivation maps the
	 * {@code databricks}+{@code job} kind to {@code DATABRICKS_JOB}.
	 */
	public static final String DATABRICKS_JOB = "databricks_job";
	/**
	 * A Databricks SQL query. V4-DBX: mapped to {@code DATABRICKS_JOB} (not
	 * {@code REDSHIFT_QUERY}) so all Databricks processing endpoints share one
	 * neutral job kind.
	 */
	public static final String DATABRICKS_SQL_QUERY = "databricks_sql_query";

	// Oracle resource type IDs
	public static final String ORACLE_COMPUTE_INSTANCE = "oracle_compute_instance";
	public static final String ORACLE_BLOCK_VOLUME = "oracle_block_volume";

	// GCP service IDs
	public static final String GCP_COMPUTE_SERVICE = "gcp_compute";
	public static final String GCP_BIGQUERY_SERVICE = "gcp_bigquery";

	// AWS service IDs
	public static final String AWS_EC2_SERVICE = "aws_ec2";
	public static final String AWS_S3_SERVICE = "aws_s3";
	public static final String AWS_RDS_SERVICE = "aws_rds";

	// Azure service IDs
	public static final String AZURE_COMPUTE_SERVICE = "azure_compute";

	// Unknown/default values
	public static final String UNKNOWN_RESOURCE_ID = "unknown-resource-id";
	public static final String UNKNOWN_RESOURCE_TYPE = "unknown-resource-type";
}
