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

package com.dlap.common.assetid;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The kind of an asset. Each kind maps to exactly one {@code dlap_assets.service_type} value.
 *
 * <p>The list is frozen for wave 1 (ADR-0047): every service type in
 * {@code DlapConstants.ServiceType} plus {@link #S3_OBJECT}, {@link #GCS_OBJECT},
 * {@link #BLOB_CONTAINER}, {@link #BLOB_OBJECT}, {@link #GLUE_DATABASE}, {@link #GLUE_TABLE},
 * {@link #UNITY_VOLUME} and {@link #SNOWFLAKE_TABLE}. CAT-01 adds the new values to the
 * {@code ck_asset_service_type_valid} constraint.
 *
 * <p>The per-kind {@link CanonicalAssetId#parts()} layout is documented on each constant and in
 * {@code docs/specs/asset-id.md}.
 */
public enum AssetKind {
    // ---- AWS ------------------------------------------------------------------------------
    /** S3 bucket. Parts: {@code [bucket]}. URI {@code s3://{bucket}}. */
    S3_BUCKET(AssetProvider.AWS),
    /** S3 object. Parts: {@code [bucket, key]}. URI {@code s3://{bucket}/{key}}. */
    S3_OBJECT(AssetProvider.AWS),
    /** RDS DB instance. Parts: the five ARN fields. URI: the ARN. */
    RDS_INSTANCE(AssetProvider.AWS),
    /** Redshift cluster. Parts: the five ARN fields. URI: the ARN. */
    REDSHIFT_CLUSTER(AssetProvider.AWS),
    /** DynamoDB table. Parts: the five ARN fields. URI: the ARN. */
    DYNAMODB_TABLE(AssetProvider.AWS),
    /** Glue Data Catalog database. Parts: the five ARN fields. URI: the ARN. */
    GLUE_DATABASE(AssetProvider.AWS),
    /** Glue Data Catalog table. Parts: the five ARN fields. URI: the ARN. */
    GLUE_TABLE(AssetProvider.AWS),

    // ---- GCP ------------------------------------------------------------------------------
    /** Cloud Storage bucket. Parts: {@code [bucket]}. URI {@code gs://{bucket}}. */
    GCS_BUCKET(AssetProvider.GCP),
    /** Cloud Storage object. Parts: {@code [bucket, object]}. URI {@code gs://{bucket}/{object}}. */
    GCS_OBJECT(AssetProvider.GCP),
    /** BigQuery dataset. Parts: {@code [project, dataset]}. URI {@code bq://{project}/{dataset}}. */
    BIGQUERY_DATASET(AssetProvider.GCP),
    /** BigQuery table or view. Parts: {@code [project, dataset, table]}. URI {@code bq://{p}/{d}/{t}}. */
    BIGQUERY_TABLE(AssetProvider.GCP),
    /** Cloud SQL instance. Parts: {@code [project, instance]}. */
    CLOUD_SQL_INSTANCE(AssetProvider.GCP),

    // ---- Azure ----------------------------------------------------------------------------
    /** Azure storage account. Parts: {@code [account]}. URI {@code az://{account}}. */
    BLOB_STORAGE(AssetProvider.AZURE),
    /** Blob container or ADLS Gen2 file system. Parts: {@code [account, container]}. */
    BLOB_CONTAINER(AssetProvider.AZURE),
    /** Blob or ADLS Gen2 path. Parts: {@code [account, container, path]}. */
    BLOB_OBJECT(AssetProvider.AZURE),
    /** Azure SQL database. Parts: {@code [subscription, resourceGroup, server, database]}. */
    SQL_DATABASE(AssetProvider.AZURE),
    /**
     * Cosmos DB account, SQL database or container.
     * Parts: {@code [subscription, resourceGroup, account]}, optionally followed by
     * {@code database} and {@code container}.
     */
    COSMOS_DB(AssetProvider.AZURE),
    /** Generic data-lake bucket. Catalog fallback only; no canonical scheme produces it. */
    DATA_LAKE(AssetProvider.AZURE),

    // ---- Unity Catalog --------------------------------------------------------------------
    /** Unity Catalog catalog. Parts: {@code [metastore, catalog]}. */
    UNITY_CATALOG(AssetProvider.UNITY),
    /** Unity Catalog schema. Parts: {@code [metastore, catalog, schema]}. */
    UNITY_SCHEMA(AssetProvider.UNITY),
    /** Unity Catalog table or view. Parts: {@code [metastore, catalog, schema, table]}. */
    UNITY_TABLE(AssetProvider.UNITY),
    /** Unity Catalog volume. Parts: {@code [metastore, catalog, schema, volume]}. */
    UNITY_VOLUME(AssetProvider.UNITY),

    // ---- Snowflake ------------------------------------------------------------------------
    /** Snowflake table or view. Parts: {@code [account, database, schema, table]}. */
    SNOWFLAKE_TABLE(AssetProvider.SNOWFLAKE),

    // ---- Processing jobs (C7). Several providers share these kinds. -----------------------
    /** Spark job: an AWS Glue Spark job or an EMR step. Parts: the five ARN fields. */
    SPARK_JOB(null),
    /** Data-movement pipeline: an Azure Data Factory pipeline. Parts: {@code [subscription, resourceGroup, factory, pipeline]}. */
    DATAFLOW_JOB(null),
    /** BigQuery job. Parts: {@code [project, jobId]}. URI {@code bqjob://{project}/{jobId}}. */
    BIGQUERY_JOB(AssetProvider.GCP),
    /** dbt model materialization. No canonical scheme yet. */
    DBT_MODEL(null),
    /** SQL query execution; today an Athena query execution. Parts: {@code [region, account, queryExecutionId]}. */
    REDSHIFT_QUERY(AssetProvider.AWS),
    /** Databricks job, job run or SQL query. Parts: {@code [workspaceId, jobs|runs|queries, id]}. */
    DATABRICKS_JOB(AssetProvider.DATABRICKS),
    /** Python job: an AWS Glue Python shell job. Parts: the five ARN fields. */
    PYTHON_JOB(null);

    private static final Set<AssetKind> OBJECT_LEVEL =
            Collections.unmodifiableSet(EnumSet.of(S3_OBJECT, GCS_OBJECT, BLOB_OBJECT));

    private static final Set<AssetKind> JOBS = Collections.unmodifiableSet(EnumSet.of(
            SPARK_JOB, DATAFLOW_JOB, BIGQUERY_JOB, DBT_MODEL, REDSHIFT_QUERY, DATABRICKS_JOB, PYTHON_JOB));

    private static final Map<String, AssetKind> BY_SERVICE_TYPE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(AssetKind::serviceType, Function.identity()));

    private final AssetProvider defaultProvider;

    AssetKind(AssetProvider defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    /**
     * The {@code dlap_assets.service_type} value for this kind.
     *
     * @return the service type, identical to {@link #name()}
     */
    public String serviceType() {
        return name();
    }

    /**
     * The provider that owns every asset of this kind, when the kind belongs to one provider.
     * Job kinds that several providers share return empty.
     *
     * @return the owning provider, or empty for a shared kind
     */
    public Optional<AssetProvider> defaultProvider() {
        return Optional.ofNullable(defaultProvider);
    }

    /**
     * The kind one level up, the kind of {@link CanonicalAssetId#parent()}. A Databricks run's
     * parent is its job, which has the same kind, so {@link #DATABRICKS_JOB} reports none here.
     *
     * @return the parent kind, or empty for a top-level kind
     */
    public Optional<AssetKind> parentKind() {
        return Optional.ofNullable(switch (this) {
            case S3_OBJECT -> S3_BUCKET;
            case GCS_OBJECT -> GCS_BUCKET;
            case BIGQUERY_TABLE -> BIGQUERY_DATASET;
            case GLUE_TABLE -> GLUE_DATABASE;
            case BLOB_OBJECT -> BLOB_CONTAINER;
            case BLOB_CONTAINER -> BLOB_STORAGE;
            case UNITY_TABLE, UNITY_VOLUME -> UNITY_SCHEMA;
            case UNITY_SCHEMA -> UNITY_CATALOG;
            default -> null;
        });
    }

    /**
     * True for object-level kinds (an S3 object, a GCS object, a blob). Under ADR-0046 their
     * events roll up to the parent container; see {@link CanonicalAssetId#rollUp()}.
     *
     * @return whether this kind names an object inside a container
     */
    public boolean isObjectLevel() {
        return OBJECT_LEVEL.contains(this);
    }

    /**
     * True for the processing-job kinds, the same set as
     * {@code DlapConstants.ServiceType.JOB_SERVICE_TYPES}.
     *
     * @return whether this kind is a job or engine kind
     */
    public boolean isJob() {
        return JOBS.contains(this);
    }

    /**
     * Looks up the kind for a {@code dlap_assets.service_type} value, ignoring case.
     *
     * @param serviceType the service type, may be {@code null}
     * @return the kind, or empty when the value is blank or unknown
     */
    public static Optional<AssetKind> fromServiceType(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_SERVICE_TYPE.get(serviceType.strip().toUpperCase(Locale.ROOT)));
    }
}
