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

/**
 * Context that lets {@link AssetIdNormalizer} resolve identifiers that do not name their own
 * provider, kind or scope: a bare bucket name, {@code dataset.table}, {@code catalog.schema.table},
 * {@code rds://region/id} without an account, and so on.
 *
 * <p>Hints come from the source binding or the event (the connection's account, project and
 * region; the metastore of a Unity Catalog binding). They never override what a self-describing
 * identifier states: {@code gs://b} stays a GCS bucket whatever the provider hint says. The kind
 * hint only picks between readings of an ambiguous form and refines a job kind (a Glue job ARN is
 * {@link AssetKind#SPARK_JOB} unless the hint says {@link AssetKind#PYTHON_JOB}).
 *
 * <p>Blank strings are treated as absent.
 *
 * @param provider      the provider the identifier belongs to, if known
 * @param kind          the expected kind, if known
 * @param account       the AWS account ID (12 digits), the Azure storage account, or the
 *                      Snowflake account identifier, depending on the provider
 * @param project       the default GCP project
 * @param region        the AWS region
 * @param subscription  the Azure subscription ID
 * @param resourceGroup the Azure resource group
 * @param metastoreId   the Unity Catalog metastore ID
 * @param workspaceId   the numeric Databricks workspace ID
 */
public record AssetIdHints(
        AssetProvider provider,
        AssetKind kind,
        String account,
        String project,
        String region,
        String subscription,
        String resourceGroup,
        String metastoreId,
        String workspaceId) {

    private static final AssetIdHints NONE = new AssetIdHints(null, null, null, null, null, null, null, null, null);

    /**
     * Canonical constructor; blank strings become {@code null} and values are stripped.
     *
     * @param provider      see the record description
     * @param kind          see the record description
     * @param account       see the record description
     * @param project       see the record description
     * @param region        see the record description
     * @param subscription  see the record description
     * @param resourceGroup see the record description
     * @param metastoreId   see the record description
     * @param workspaceId   see the record description
     */
    public AssetIdHints {
        account = clean(account);
        project = clean(project);
        region = clean(region);
        subscription = clean(subscription);
        resourceGroup = clean(resourceGroup);
        metastoreId = clean(metastoreId);
        workspaceId = clean(workspaceId);
    }

    /**
     * No hints. Only self-describing identifiers normalize.
     *
     * @return the empty hint set
     */
    public static AssetIdHints none() {
        return NONE;
    }

    /**
     * Hints that carry only a provider.
     *
     * @param provider the provider
     * @return hints with the provider set
     */
    public static AssetIdHints of(AssetProvider provider) {
        return builder().provider(provider).build();
    }

    /**
     * Hints that carry only a kind; the provider follows from {@link AssetKind#defaultProvider()}.
     *
     * @param kind the expected kind
     * @return hints with the kind set
     */
    public static AssetIdHints of(AssetKind kind) {
        return builder().kind(kind).build();
    }

    /**
     * A new, empty builder.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder pre-filled with these hints.
     *
     * @return a builder
     */
    public Builder toBuilder() {
        return new Builder()
                .provider(provider).kind(kind).account(account).project(project).region(region)
                .subscription(subscription).resourceGroup(resourceGroup)
                .metastoreId(metastoreId).workspaceId(workspaceId);
    }

    /**
     * A copy with the kind replaced.
     *
     * @param newKind the kind, may be {@code null}
     * @return the updated hints
     */
    public AssetIdHints withKind(AssetKind newKind) {
        return toBuilder().kind(newKind).build();
    }

    /**
     * A copy with the provider replaced.
     *
     * @param newProvider the provider, may be {@code null}
     * @return the updated hints
     */
    public AssetIdHints withProvider(AssetProvider newProvider) {
        return toBuilder().provider(newProvider).build();
    }

    /**
     * The provider to use for hint-driven (bare) forms: the provider hint, else the kind's
     * default provider.
     *
     * @return the effective provider, or {@code null} when neither hint is set
     */
    AssetProvider effectiveProvider() {
        if (provider != null) {
            return provider;
        }
        return kind == null ? null : kind.defaultProvider().orElse(null);
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    /** Fluent builder for {@link AssetIdHints}. */
    public static final class Builder {
        private AssetProvider provider;
        private AssetKind kind;
        private String account;
        private String project;
        private String region;
        private String subscription;
        private String resourceGroup;
        private String metastoreId;
        private String workspaceId;

        private Builder() {
        }

        /**
         * Sets the provider.
         *
         * @param value the provider
         * @return this builder
         */
        public Builder provider(AssetProvider value) {
            this.provider = value;
            return this;
        }

        /**
         * Sets the expected kind.
         *
         * @param value the kind
         * @return this builder
         */
        public Builder kind(AssetKind value) {
            this.kind = value;
            return this;
        }

        /**
         * Sets the expected kind from a {@code service_type} value; unknown values are ignored.
         *
         * @param serviceType the service type
         * @return this builder
         */
        public Builder serviceType(String serviceType) {
            AssetKind.fromServiceType(serviceType).ifPresent(k -> this.kind = k);
            return this;
        }

        /**
         * Sets the account (AWS account ID, Azure storage account or Snowflake account).
         *
         * @param value the account
         * @return this builder
         */
        public Builder account(String value) {
            this.account = value;
            return this;
        }

        /**
         * Sets the default GCP project.
         *
         * @param value the project ID
         * @return this builder
         */
        public Builder project(String value) {
            this.project = value;
            return this;
        }

        /**
         * Sets the AWS region.
         *
         * @param value the region
         * @return this builder
         */
        public Builder region(String value) {
            this.region = value;
            return this;
        }

        /**
         * Sets the Azure subscription ID.
         *
         * @param value the subscription ID
         * @return this builder
         */
        public Builder subscription(String value) {
            this.subscription = value;
            return this;
        }

        /**
         * Sets the Azure resource group.
         *
         * @param value the resource group
         * @return this builder
         */
        public Builder resourceGroup(String value) {
            this.resourceGroup = value;
            return this;
        }

        /**
         * Sets the Unity Catalog metastore ID.
         *
         * @param value the metastore ID
         * @return this builder
         */
        public Builder metastoreId(String value) {
            this.metastoreId = value;
            return this;
        }

        /**
         * Sets the numeric Databricks workspace ID.
         *
         * @param value the workspace ID
         * @return this builder
         */
        public Builder workspaceId(String value) {
            this.workspaceId = value;
            return this;
        }

        /**
         * Builds the hints.
         *
         * @return the immutable hints
         */
        public AssetIdHints build() {
            return new AssetIdHints(provider, kind, account, project, region, subscription,
                    resourceGroup, metastoreId, workspaceId);
        }
    }
}
