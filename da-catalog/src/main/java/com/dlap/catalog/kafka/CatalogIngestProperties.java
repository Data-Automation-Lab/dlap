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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.CanonicalAssetId;

/**
 * Settings of the catalog event consumers ({@code dlap.catalog.ingest.*}, ADR-0055). The
 * defaults are in {@code da-catalog/src/main/resources/application.properties}.
 */
@ConfigurationProperties(prefix = "dlap.catalog.ingest")
public class CatalogIngestProperties {

    /**
     * Governed prefixes (ADR-0046 section 1): canonical object-URI prefixes, for example
     * {@code gs://finance-bucket/pii/}. An object under one of them gets its own catalog row;
     * every other object event only rolls up to its container. Empty by default, so no
     * object rows are created.
     */
    private List<String> governedPrefixes = new ArrayList<>();

    private final ProcessedEvent processedEvent = new ProcessedEvent();

    private final Match match = new Match();

    /**
     * The governed prefixes.
     *
     * @return the configured prefixes, never {@code null}
     */
    public List<String> getGovernedPrefixes() {
        return governedPrefixes;
    }

    /**
     * Sets the governed prefixes.
     *
     * @param governedPrefixes canonical object-URI prefixes; {@code null} means none
     */
    public void setGovernedPrefixes(List<String> governedPrefixes) {
        this.governedPrefixes = governedPrefixes == null ? new ArrayList<>() : new ArrayList<>(governedPrefixes);
    }

    /**
     * Processed-event retention and pruning.
     *
     * @return the nested settings
     */
    public ProcessedEvent getProcessedEvent() {
        return processedEvent;
    }

    /**
     * Matching of event targets to catalog rows.
     *
     * @return the nested settings
     */
    public Match getMatch() {
        return match;
    }

    /**
     * Whether {@code canonical} is an object under a governed prefix.
     *
     * @param canonical a canonical ID
     * @return {@code true} for an object-level ID whose URI starts with a governed prefix
     */
    public boolean isGoverned(CanonicalAssetId canonical) {
        if (canonical == null || !canonical.isObjectLevel()) {
            return false;
        }
        for (String prefix : governedPrefixes) {
            if (prefix != null && !prefix.isBlank() && canonical.uri().startsWith(prefix.strip())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fails fast on a governed prefix that is not a canonical object-URI prefix
     * ({@code s3://bucket/...}, {@code gs://bucket/...}, {@code az://account/container/...}).
     *
     * @throws IllegalStateException naming the first invalid entry by position, never by value
     */
    public void validate() {
        for (int i = 0; i < governedPrefixes.size(); i++) {
            String prefix = governedPrefixes.get(i) == null ? "" : governedPrefixes.get(i).strip();
            if (prefix.isEmpty()) {
                continue;
            }
            String probe = prefix.endsWith("/") ? prefix + "x" : prefix;
            boolean objectScheme = prefix.toLowerCase(Locale.ROOT).startsWith("s3://")
                    || prefix.toLowerCase(Locale.ROOT).startsWith("gs://")
                    || prefix.toLowerCase(Locale.ROOT).startsWith("az://");
            boolean objectLevel = AssetIdNormalizer.normalize(probe, AssetIdHints.none())
                    .map(CanonicalAssetId::isObjectLevel).orElse(false);
            if (!objectScheme || !objectLevel) {
                throw new IllegalStateException("dlap.catalog.ingest.governed-prefixes[" + i
                        + "] is not a canonical object-URI prefix (s3://bucket/..., gs://bucket/..., "
                        + "az://account/container/...)");
            }
        }
        if (processedEvent.getRetention() == null || processedEvent.getRetention().isNegative()
                || processedEvent.getRetention().isZero()) {
            throw new IllegalStateException("dlap.catalog.ingest.processed-event.retention must be positive");
        }
        if (processedEvent.getPruneBatchSize() <= 0) {
            throw new IllegalStateException("dlap.catalog.ingest.processed-event.prune-batch-size must be positive");
        }
        if (match.getIndexBatchSize() <= 0) {
            throw new IllegalStateException("dlap.catalog.ingest.match.index-batch-size must be positive");
        }
    }

    /** Retention of {@code dlap_catalog.processed_event}. */
    public static class ProcessedEvent {

        /** How long a processed pair is remembered. Must exceed the log store's replay window. */
        private Duration retention = Duration.ofDays(100);

        /** Rows deleted per statement. */
        private int pruneBatchSize = 10_000;

        /**
         * Retention of processed pairs.
         *
         * @return the retention
         */
        public Duration getRetention() {
            return retention;
        }

        /**
         * Sets the retention.
         *
         * @param retention a positive duration
         */
        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        /**
         * Rows deleted per prune statement.
         *
         * @return the batch size
         */
        public int getPruneBatchSize() {
            return pruneBatchSize;
        }

        /**
         * Sets the prune batch size.
         *
         * @param pruneBatchSize a positive row count
         */
        public void setPruneBatchSize(int pruneBatchSize) {
            this.pruneBatchSize = pruneBatchSize;
        }
    }

    /** Matching of event targets to rows, including rows stored before canonical IDs. */
    public static class Match {

        /** Rows normalized per statement when the canonical-URI index is built or caught up. */
        private int indexBatchSize = 500;

        /** Minimum time between two catch-up sweeps of one replica, triggered by a miss. */
        private Duration catchUpInterval = Duration.ofSeconds(30);

        /** Whether a background thread builds the index at startup. A miss builds it anyway. */
        private boolean indexOnStartup = true;

        /**
         * Context for stored forms that need it (for example a Unity Catalog {@code c.s.t} row
         * needs the metastore). Operator-provided for this single-tenant deployment
         * (ADR-0049); empty by default. Used only to normalize stored rows and event targets,
         * never to build a new row's identity on its own.
         */
        private final DefaultHints defaultHints = new DefaultHints();

        /**
         * Rows per index statement.
         *
         * @return the batch size
         */
        public int getIndexBatchSize() {
            return indexBatchSize;
        }

        /**
         * Sets the index batch size.
         *
         * @param indexBatchSize a positive row count
         */
        public void setIndexBatchSize(int indexBatchSize) {
            this.indexBatchSize = indexBatchSize;
        }

        /**
         * Minimum spacing of catch-up sweeps.
         *
         * @return the interval
         */
        public Duration getCatchUpInterval() {
            return catchUpInterval;
        }

        /**
         * Sets the catch-up interval.
         *
         * @param catchUpInterval the interval; zero sweeps on every miss
         */
        public void setCatchUpInterval(Duration catchUpInterval) {
            this.catchUpInterval = catchUpInterval;
        }

        /**
         * Whether the index is built in the background at startup.
         *
         * @return the flag
         */
        public boolean isIndexOnStartup() {
            return indexOnStartup;
        }

        /**
         * Sets the startup build flag.
         *
         * @param indexOnStartup the flag
         */
        public void setIndexOnStartup(boolean indexOnStartup) {
            this.indexOnStartup = indexOnStartup;
        }

        /**
         * Operator-provided normalization context.
         *
         * @return the nested settings
         */
        public DefaultHints getDefaultHints() {
            return defaultHints;
        }
    }

    /** Deployment-wide normalization context (AssetIdHints fields). */
    public static class DefaultHints {

        private String account;
        private String project;
        private String metastoreId;
        private String workspaceId;

        /**
         * AWS account ID, Azure storage account or Snowflake account.
         *
         * @return the value, may be {@code null}
         */
        public String getAccount() {
            return account;
        }

        /**
         * Sets the account hint.
         *
         * @param account the value
         */
        public void setAccount(String account) {
            this.account = account;
        }

        /**
         * Default GCP project.
         *
         * @return the value, may be {@code null}
         */
        public String getProject() {
            return project;
        }

        /**
         * Sets the project hint.
         *
         * @param project the value
         */
        public void setProject(String project) {
            this.project = project;
        }

        /**
         * Unity Catalog metastore ID.
         *
         * @return the value, may be {@code null}
         */
        public String getMetastoreId() {
            return metastoreId;
        }

        /**
         * Sets the metastore hint.
         *
         * @param metastoreId the value
         */
        public void setMetastoreId(String metastoreId) {
            this.metastoreId = metastoreId;
        }

        /**
         * Numeric Databricks workspace ID.
         *
         * @return the value, may be {@code null}
         */
        public String getWorkspaceId() {
            return workspaceId;
        }

        /**
         * Sets the workspace hint.
         *
         * @param workspaceId the value
         */
        public void setWorkspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
        }

        /**
         * Copies the configured values into a hints builder, leaving fields already set alone.
         *
         * @param builder a hints builder
         * @param current the hints the builder was created from
         * @return the builder
         */
        AssetIdHints.Builder applyTo(AssetIdHints.Builder builder, AssetIdHints current) {
            if (current.account() == null && account != null) {
                builder.account(account);
            }
            if (current.project() == null && project != null) {
                builder.project(project);
            }
            if (current.metastoreId() == null && metastoreId != null) {
                builder.metastoreId(metastoreId);
            }
            if (current.workspaceId() == null && workspaceId != null) {
                builder.workspaceId(workspaceId);
            }
            return builder;
        }
    }
}
