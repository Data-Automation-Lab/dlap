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

package com.dlap.catalog.kafka;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.dlap.common.assetid.AssetKind;

/**
 * Maps the {@code targetResource.kind} strings producers send today to an {@link AssetKind}
 * hint for {@code AssetIdNormalizer} (asset-id.md section 7). The kind hint only picks between
 * readings of a bare or ambiguous form; it never overrides a self-describing one, and the
 * normalizer still validates the shape. An unknown string gives no hint.
 *
 * <p>Sources of the names: DLAP service types ({@code AssetKind} names), the da-discovery
 * crawler type ids ({@code AWSConstants}, {@code ResourceTypeConstants}, the GCP and Unity
 * crawlers), CloudTrail {@code resources[].type} (CloudFormation resource types), and GCP
 * {@code LogEntry.resource.type} for the kinds that carry bare names.
 */
final class TargetKindHints {

    private static final Map<String, AssetKind> BY_NAME = Map.ofEntries(
            // da-discovery crawler type ids
            Map.entry("aws_s3_bucket", AssetKind.S3_BUCKET),
            Map.entry("aws_s3_object", AssetKind.S3_OBJECT),
            Map.entry("aws_rds_instance", AssetKind.RDS_INSTANCE),
            Map.entry("aws_glue_database", AssetKind.GLUE_DATABASE),
            Map.entry("aws_glue_table", AssetKind.GLUE_TABLE),
            Map.entry("aws_dynamodb_table", AssetKind.DYNAMODB_TABLE),
            Map.entry("aws_redshift_cluster", AssetKind.REDSHIFT_CLUSTER),
            Map.entry("gcp_gcs_bucket", AssetKind.GCS_BUCKET),
            Map.entry("gcp_gcs_object", AssetKind.GCS_OBJECT),
            Map.entry("gcp_bigquery_dataset", AssetKind.BIGQUERY_DATASET),
            Map.entry("gcp_bigquery_table", AssetKind.BIGQUERY_TABLE),
            Map.entry("gcp_bigquery_job", AssetKind.BIGQUERY_JOB),
            Map.entry("gcp_cloudsql_instance", AssetKind.CLOUD_SQL_INSTANCE),
            Map.entry("unity_catalog", AssetKind.UNITY_CATALOG),
            Map.entry("unity_schema", AssetKind.UNITY_SCHEMA),
            Map.entry("unity_table", AssetKind.UNITY_TABLE),
            Map.entry("unity_volume", AssetKind.UNITY_VOLUME),
            Map.entry("databricks_job", AssetKind.DATABRICKS_JOB),
            // CloudTrail resources[].type
            Map.entry("aws::s3::bucket", AssetKind.S3_BUCKET),
            Map.entry("aws::s3::object", AssetKind.S3_OBJECT),
            Map.entry("aws::rds::dbinstance", AssetKind.RDS_INSTANCE),
            Map.entry("aws::dynamodb::table", AssetKind.DYNAMODB_TABLE),
            Map.entry("aws::redshift::cluster", AssetKind.REDSHIFT_CLUSTER),
            Map.entry("aws::glue::database", AssetKind.GLUE_DATABASE),
            Map.entry("aws::glue::table", AssetKind.GLUE_TABLE),
            // GCP LogEntry resource.type
            Map.entry("gcs_bucket", AssetKind.GCS_BUCKET),
            Map.entry("cloudsql_database", AssetKind.CLOUD_SQL_INSTANCE));

    private TargetKindHints() {
    }

    /**
     * The kind hint for a producer's kind string.
     *
     * @param kind {@code targetResource.kind}, or a stored {@code service_type}
     * @return the kind, or empty when the string names no DLAP kind
     */
    static Optional<AssetKind> fromTargetKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return Optional.empty();
        }
        Optional<AssetKind> serviceType = AssetKind.fromServiceType(kind);
        if (serviceType.isPresent()) {
            return serviceType;
        }
        return Optional.ofNullable(BY_NAME.get(kind.strip().toLowerCase(Locale.ROOT)));
    }
}
