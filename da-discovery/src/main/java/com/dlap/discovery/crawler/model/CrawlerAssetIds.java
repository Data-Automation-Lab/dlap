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

package com.dlap.discovery.crawler.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.crawler.model.aws.AwsProcessingJobResource;

/**
 * The one place da-discovery turns what a crawler or a log analyzer knows into a canonical asset
 * identifier (ADR-0047, rule R8). It wraps {@link AssetIdNormalizer}: crawlers pass the structured
 * parts they hold (or the provider-native string plus hints taken from the connection), the
 * result lands in {@link CloudResource#setUri(String)}, and the crawler-to-spine bridge re-derives
 * the same identifier for resources that arrive without one.
 *
 * <p><b>Never guess.</b> A rejection leaves the resource untouched and is logged by reason code
 * only (the normalizer never echoes the input). The caller keeps whatever identity it had, and the
 * bridge counts the event as unresolved.
 *
 * <p><b>Hints from the connection.</b> A crawler job runs against one connection, whose account id
 * is the AWS account, the GCP project, the Azure subscription, the Unity Catalog metastore or the
 * Databricks workspace, depending on the provider ({@link #hints}). Account, project, region and
 * location are attributes, not identity, unless the provider's native identifier contains them
 * (asset-id.md rule 3).
 *
 * <p>Pure Java apart from {@link CloudResource}: no provider SDK types.
 */
public final class CrawlerAssetIds {

    /** Width of {@code cloud_resource.uri} (V1__init_discovery_schema.sql); a longer URI is not stored there. */
    public static final int URI_COLUMN_WIDTH = 255;

    /** Marks a self-describing identifier: {@code gs://bucket/key}, {@code s3://bucket/key}, an ARN has none. */
    private static final String SCHEME_SEPARATOR = "://";

    /** Crawler technical-metadata key that carries an observed size, a non-negative decimal byte count. */
    public static final String SIZE_BYTES_KEY = "sizeBytes";

    private static final Logger log = LoggerFactory.getLogger(CrawlerAssetIds.class);

    /** The Glue job type that reads as {@link AssetKind#PYTHON_JOB} instead of {@link AssetKind#SPARK_JOB}. */
    private static final String JOB_TYPE_PYTHON = "PYTHON_JOB";

    /** Crawler resource type ids (lower case) and the kind each stands for. */
    private static final Map<String, AssetKind> KIND_BY_TYPE_ID = Map.ofEntries(
            Map.entry("aws_s3_bucket", AssetKind.S3_BUCKET),
            Map.entry("aws_s3_object", AssetKind.S3_OBJECT),
            Map.entry("aws_rds_instance", AssetKind.RDS_INSTANCE),
            Map.entry("aws_dynamodb_table", AssetKind.DYNAMODB_TABLE),
            Map.entry("aws_glue_database", AssetKind.GLUE_DATABASE),
            Map.entry("aws_glue_table", AssetKind.GLUE_TABLE),
            Map.entry("aws_glue_job", AssetKind.SPARK_JOB),
            Map.entry("aws_athena_query", AssetKind.REDSHIFT_QUERY),
            Map.entry("aws_emr_step", AssetKind.SPARK_JOB),
            Map.entry("gcp_gcs_bucket", AssetKind.GCS_BUCKET),
            Map.entry("gcp_gcs_object", AssetKind.GCS_OBJECT),
            Map.entry("gcp_bigquery_dataset", AssetKind.BIGQUERY_DATASET),
            Map.entry("gcp_bigquery_table", AssetKind.BIGQUERY_TABLE),
            Map.entry("gcp_bigquery_job", AssetKind.BIGQUERY_JOB),
            Map.entry("gcp_cloudsql_instance", AssetKind.CLOUD_SQL_INSTANCE),
            Map.entry("azure_blob_container", AssetKind.BLOB_CONTAINER),
            Map.entry("azure_sql_database", AssetKind.SQL_DATABASE),
            Map.entry("azure_data_factory_pipeline", AssetKind.DATAFLOW_JOB),
            Map.entry("unity_catalog", AssetKind.UNITY_CATALOG),
            Map.entry("unity_schema", AssetKind.UNITY_SCHEMA),
            Map.entry("unity_table", AssetKind.UNITY_TABLE),
            Map.entry("unity_volume", AssetKind.UNITY_VOLUME),
            Map.entry("databricks_job", AssetKind.DATABRICKS_JOB),
            Map.entry("databricks_sql_query", AssetKind.DATABRICKS_JOB));

    private CrawlerAssetIds() {
    }

    // ---- kinds and hints ---------------------------------------------------------------------

    /**
     * The asset kind a crawler resource type id stands for.
     *
     * @param typeId a crawler resource type id such as {@code aws_s3_bucket}; may be {@code null}
     * @return the kind, or empty when the type is not a DLAP asset kind (a virtual machine, a
     *         Synapse job) or is unknown
     */
    public static Optional<AssetKind> kindOfTypeId(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(KIND_BY_TYPE_ID.get(typeId.strip().toLowerCase(Locale.ROOT)));
    }

    /**
     * The kind of a crawled resource: its type id's kind, with a Glue Python shell job refined to
     * {@link AssetKind#PYTHON_JOB} (the ARN does not say which a Glue job is).
     *
     * @param resource the crawled resource
     * @return the kind, or empty when the type is not a DLAP asset kind
     */
    public static Optional<AssetKind> kindOf(CloudResource resource) {
        Optional<AssetKind> kind = kindOfTypeId(resource.getTypeId());
        if (kind.filter(k -> k == AssetKind.SPARK_JOB).isPresent()
                && resource instanceof AwsProcessingJobResource job
                && JOB_TYPE_PYTHON.equalsIgnoreCase(job.getJobType())) {
            return Optional.of(AssetKind.PYTHON_JOB);
        }
        return kind;
    }

    /**
     * Hints taken from the connection a job runs against. The connection's account id means a
     * different thing per provider: the AWS account (12 digits), the GCP project, the Azure
     * subscription, the Unity Catalog metastore or the Databricks workspace.
     *
     * @param provider  the provider; may be {@code null} when only the kind says it
     * @param kind      the expected kind; may be {@code null}
     * @param accountId the connection's account id; may be {@code null}
     * @param region    the AWS region or the location of the resource; may be {@code null}
     * @return the hints; blank values are treated as absent
     */
    public static AssetIdHints hints(AssetProvider provider, AssetKind kind, String accountId, String region) {
        AssetIdHints.Builder b = AssetIdHints.builder().provider(provider).kind(kind).region(region);
        AssetProvider effective = provider != null ? provider : kind == null ? null : kind.defaultProvider().orElse(null);
        if (effective != null) {
            switch (effective) {
                case AWS -> b.account(accountId);
                case GCP -> b.project(accountId);
                case AZURE -> b.subscription(accountId);
                case UNITY -> b.metastoreId(accountId);
                case DATABRICKS -> b.workspaceId(accountId);
                default -> { }
            }
        }
        return b.build();
    }

    /**
     * The provider of a crawled resource.
     *
     * @param resource the crawled resource
     * @return the provider, or empty when the resource's provider has no DLAP asset provider
     */
    public static Optional<AssetProvider> providerOf(CloudResource resource) {
        return resource.getCloudProviderEnum() == null
                ? Optional.empty()
                : AssetProvider.fromString(resource.getCloudProviderEnum().name());
    }

    // ---- assigning an identifier -------------------------------------------------------------

    /**
     * Builds the identifier of a kind that belongs to one provider from its structured parts and
     * stores it as the resource URI.
     *
     * @param resource the resource to update
     * @param kind     the kind; must belong to one provider
     * @param parts    the identity parts in the order documented on {@link AssetKind}; a {@code null}
     *                 part (a connection without a project, say) makes the build fail
     * @return the canonical ID, or empty when the parts are invalid for the kind (the resource is untouched)
     */
    public static Optional<CanonicalAssetId> assignBuilt(CloudResource resource, AssetKind kind, String... parts) {
        return assign(resource, AssetIdNormalizer.build(kind, Arrays.asList(parts)), kind);
    }

    /**
     * Builds the identifier of a kind that several providers share (a Spark job, a Data Factory
     * pipeline) and stores it as the resource URI.
     *
     * @param resource the resource to update
     * @param provider the provider
     * @param kind     the kind
     * @param hints    hints; may be {@code null}
     * @param parts    the identity parts in the order documented on {@link AssetKind}
     * @return the canonical ID, or empty when the parts are invalid (the resource is untouched)
     */
    public static Optional<CanonicalAssetId> assignBuilt(CloudResource resource, AssetProvider provider,
                                                         AssetKind kind, AssetIdHints hints, String... parts) {
        return assign(resource, AssetIdNormalizer.build(provider, kind, Arrays.asList(parts), hints), kind);
    }

    /**
     * Normalizes a provider-native identifier with hints from the connection and stores the result
     * as the resource URI.
     *
     * @param resource the resource to update
     * @param raw      the identifier in any form the normalizer accepts
     * @param hints    hints from the connection
     * @return the canonical ID, or empty when the identifier did not normalize (the resource is untouched)
     */
    public static Optional<CanonicalAssetId> assignParsed(CloudResource resource, String raw, AssetIdHints hints) {
        AssetKind expected = hints == null ? null : hints.kind();
        return assign(resource, AssetIdNormalizer.parse(raw, hints), expected);
    }

    private static Optional<CanonicalAssetId> assign(CloudResource resource, AssetIdParseResult result,
                                                     AssetKind expected) {
        if (result instanceof AssetIdParseResult.Normalized n && kindFits(n.canonical(), expected)) {
            CanonicalAssetId id = n.canonical();
            if (id.uri().length() <= URI_COLUMN_WIDTH) {
                resource.setUri(id.uri());
            } else {
                log.debug("Canonical URI of a {} resource is {} characters; not stored in the {}-wide uri column",
                        id.kind(), id.uri().length(), URI_COLUMN_WIDTH);
            }
            return Optional.of(id);
        }
        log.debug("No canonical id for a {} resource ({}); keeping its identity unchanged",
                resource.getTypeId(), describe(result));
        return Optional.empty();
    }

    // ---- resolving for the bridge ------------------------------------------------------------

    /**
     * The canonical identifier of a crawled resource, for the crawler-to-spine bridge. The resource
     * URI is tried first (already canonical when its crawler stored it), then the provider-native
     * resource id, both with hints taken from the resource itself: provider, kind from the type id,
     * the connection's account id and the region. A resource whose type is no DLAP asset kind (a
     * virtual machine) resolves to nothing, and so does an object whose only identifier is its bare
     * key: the key does not name its bucket, so the bridge counts it as unresolved instead of
     * guessing.
     *
     * @param resource the crawled resource
     * @return the canonical ID, or empty when the resource does not normalize
     */
    public static Optional<CanonicalAssetId> resolve(CloudResource resource) {
        Optional<AssetKind> kind = kindOf(resource);
        Optional<AssetProvider> provider = providerOf(resource);
        if (kind.isEmpty() || provider.isEmpty()) {
            return Optional.empty();
        }
        AssetIdHints hints = hints(provider.get(), kind.get(), accountOf(resource, provider.get()), resource.getRegion());
        // An object's resource id is its bare key, which does not say which bucket it is in (keys
        // collide across buckets), so for the object kinds only a self-describing form counts: the
        // uri, or the name the GCS crawler writes as gs://{bucket}/{object}. Reading a bare key as
        // bucket/object would name a different bucket's object.
        boolean objectLevel = kind.get().isObjectLevel();
        String[] candidates = objectLevel
                ? new String[] {resource.getUri(), resource.getName()}
                : new String[] {resource.getUri(), resource.getResourceId()};
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || (objectLevel && !candidate.contains(SCHEME_SEPARATOR))) {
                continue;
            }
            AssetIdParseResult result = AssetIdNormalizer.parse(candidate, hints);
            if (result instanceof AssetIdParseResult.Normalized n && kindFits(n.canonical(), kind.get())) {
                return Optional.of(n.canonical());
            }
        }
        return Optional.empty();
    }

    /**
     * The canonical identifier of a raw identifier named by a change record (the log-analyzer
     * path), with hints from the change.
     *
     * @param providerName the provider name as the analyzer reports it, for example {@code GCP}
     * @param typeId       the crawler resource type id of the change; may be {@code null}
     * @param raw          the identifier
     * @param accountId    the connection's account id (a GCP project for GCP); may be {@code null}
     * @param region       the region; may be {@code null}
     * @return the canonical ID, or empty when the identifier did not normalize
     */
    public static Optional<CanonicalAssetId> resolveRaw(String providerName, String typeId, String raw,
                                                        String accountId, String region) {
        Optional<AssetProvider> provider = AssetProvider.fromString(providerName);
        Optional<AssetKind> kind = kindOfTypeId(typeId);
        if (provider.isEmpty() && kind.isEmpty()) {
            return AssetIdNormalizer.normalize(raw);
        }
        AssetIdHints hints = hints(provider.orElse(null), kind.orElse(null), accountId, region);
        AssetIdParseResult result = AssetIdNormalizer.parse(raw, hints);
        return result instanceof AssetIdParseResult.Normalized n && kindFits(n.canonical(), kind.orElse(null))
                ? Optional.of(n.canonical())
                : Optional.empty();
    }

    /**
     * The observed size recorded on a crawled resource under {@link #SIZE_BYTES_KEY}.
     *
     * @param resource the crawled resource
     * @return the size in bytes, or empty when the crawler did not observe one (unknown is not zero)
     */
    public static Optional<Long> observedSizeBytes(CloudResource resource) {
        Map<String, String> tech = resource.getTechnicalMetadata();
        String text = tech == null ? null : tech.get(SIZE_BYTES_KEY);
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            long size = Long.parseLong(text.strip());
            return size >= 0 ? Optional.of(size) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Records an observed size on a crawled resource. A negative size is unknown and is not recorded.
     *
     * @param resource  the resource
     * @param sizeBytes the observed size in bytes, or {@code null} when unknown
     */
    public static void recordSize(CloudResource resource, Long sizeBytes) {
        if (sizeBytes != null && sizeBytes >= 0) {
            resource.getTechnicalMetadata().put(SIZE_BYTES_KEY, Long.toString(sizeBytes));
        }
    }

    // ---- internals ---------------------------------------------------------------------------

    /** A resource's connection account: Azure keeps its subscription in the account id as well. */
    private static String accountOf(CloudResource resource, AssetProvider provider) {
        return provider == AssetProvider.GCP && resource.getProjectId() != null && !resource.getProjectId().isBlank()
                ? resource.getProjectId()
                : resource.getAccountId();
    }

    /** A job kind refined by a hint (Glue Python shell) still fits; otherwise the kind must match. */
    private static boolean kindFits(CanonicalAssetId id, AssetKind expected) {
        return expected == null
                || id.kind() == expected
                || (expected == AssetKind.PYTHON_JOB && id.kind() == AssetKind.SPARK_JOB)
                || (expected == AssetKind.SPARK_JOB && id.kind() == AssetKind.PYTHON_JOB);
    }

    private static String describe(AssetIdParseResult result) {
        return result instanceof AssetIdParseResult.Rejected r ? "rejected: " + r.reason() : "kind mismatch";
    }
}
