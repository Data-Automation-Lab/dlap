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

package com.dlap.reporting.report.expiry;

import java.util.List;
import java.util.Optional;

import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.CanonicalAssetId;

/**
 * Reads the account of an asset from its canonical URI (ADR-0047, {@code docs/specs/asset-id.md}).
 * The catalog row has no account field, and the URI carries the account exactly when the provider
 * puts it in the native identifier, so the report reads it from there and never guesses.
 *
 * <p>The "account" is the provider's top-level billing or tenancy scope:
 * <ul>
 *   <li>AWS: the 12-digit account ID of an ARN or Athena identifier;</li>
 *   <li>GCP: the project of a BigQuery, BigQuery job or Cloud SQL identifier;</li>
 *   <li>Azure: the storage account of a blob identifier, otherwise the subscription of an ARM ID;</li>
 *   <li>Unity Catalog: the metastore; Databricks: the workspace; Snowflake: the account.</li>
 * </ul>
 * An S3 bucket or a GCS bucket has no account in its URI (a bucket name is global), so the account
 * is unknown and the report leaves it empty. A URI that does not parse (a row not yet re-keyed to
 * the canonical form) also gives an unknown account.
 */
final class AssetScope {

    private AssetScope() {
    }

    /**
     * The account of an asset.
     *
     * @param assetUri the catalog's {@code assetUri}; may be null or not canonical
     * @return the account, or empty when the URI does not carry one
     */
    static Optional<String> accountOf(String assetUri) {
        if (assetUri == null || assetUri.isBlank()) {
            return Optional.empty();
        }
        if (!(AssetIdNormalizer.parse(assetUri, null) instanceof AssetIdParseResult.Normalized normalized)) {
            return Optional.empty();
        }
        CanonicalAssetId id = normalized.canonical();
        List<String> parts = id.parts();
        AssetKind kind = id.kind();
        String account = switch (kind) {
            case RDS_INSTANCE, REDSHIFT_CLUSTER, DYNAMODB_TABLE, GLUE_DATABASE, GLUE_TABLE, SPARK_JOB, PYTHON_JOB ->
                    arnAccount(id.uri());
            case REDSHIFT_QUERY -> parts.size() > 1 ? parts.get(1) : null;
            case BIGQUERY_DATASET, BIGQUERY_TABLE, BIGQUERY_JOB, CLOUD_SQL_INSTANCE -> first(parts);
            case BLOB_STORAGE, BLOB_CONTAINER, BLOB_OBJECT -> first(parts);
            case SQL_DATABASE, COSMOS_DB, DATAFLOW_JOB -> first(parts);
            case UNITY_CATALOG, UNITY_SCHEMA, UNITY_TABLE, UNITY_VOLUME, DATABRICKS_JOB, SNOWFLAKE_TABLE -> first(parts);
            default -> null;
        };
        return account == null || account.isBlank() ? Optional.empty() : Optional.of(account);
    }

    /** The account field of an ARN ({@code arn:partition:service:region:account:resource}), if it is 12 digits. */
    private static String arnAccount(String uri) {
        String[] fields = uri.split(":", 6);
        return fields.length == 6 && fields[4].matches("\\d{12}") ? fields[4] : null;
    }

    private static String first(List<String> parts) {
        return parts.isEmpty() ? null : parts.get(0);
    }
}
