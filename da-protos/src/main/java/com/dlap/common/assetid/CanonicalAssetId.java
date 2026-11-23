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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One asset identity in canonical form (ADR-0047, {@code docs/specs/asset-id.md}).
 *
 * <p>Identity is the {@link #uri()}: two instances are equal exactly when their URIs are equal.
 * The URI is what {@code dlap_assets.asset_uri}, Kafka keys and lineage endpoints carry.
 * {@link #provider()} and {@link #kind()} are derived from it. {@link #attributes()} carry facts
 * that were parsed from the input but are not identity (an S3 bucket's partition, a BigQuery
 * job's location, an Azure blob's subscription).
 *
 * <p>Instances come only from {@link AssetIdNormalizer}; there is no public constructor.
 */
public final class CanonicalAssetId {

    /** Attribute names. Values are informational and never part of identity. */
    public static final class Attribute {
        /** AWS partition, for example {@code aws} or {@code aws-cn}. */
        public static final String PARTITION = "partition";
        /** Region parsed from the input (AWS region). */
        public static final String REGION = "region";
        /** Account parsed from the input (AWS account ID). */
        public static final String ACCOUNT = "account";
        /** GCP project parsed from a GCS resource name or URL. */
        public static final String PROJECT = "project";
        /** BigQuery job location. */
        public static final String LOCATION = "location";
        /** Azure subscription ID. */
        public static final String SUBSCRIPTION = "subscription";
        /** Azure resource group. */
        public static final String RESOURCE_GROUP = "resourceGroup";
        /** Databricks job ID that owns a run. */
        public static final String JOB_ID = "jobId";
        /** BigQuery table decorator that was stripped (partition {@code $...} or snapshot {@code @...}). */
        public static final String DECORATOR = "decorator";

        private Attribute() {
        }
    }

    private final AssetProvider provider;
    private final AssetKind kind;
    private final List<String> parts;
    private final String uri;
    private final Map<String, String> attributes;
    private final CanonicalAssetId parent;

    CanonicalAssetId(AssetProvider provider, AssetKind kind, List<String> parts, String uri,
                     Map<String, String> attributes, CanonicalAssetId parent) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.parts = List.copyOf(parts);
        this.uri = Objects.requireNonNull(uri, "uri");
        TreeMap<String, String> sorted = new TreeMap<>();
        if (attributes != null) {
            attributes.forEach((k, v) -> {
                if (k != null && v != null && !v.isEmpty()) {
                    sorted.put(k, v);
                }
            });
        }
        this.attributes = Collections.unmodifiableMap(sorted);
        this.parent = parent;
    }

    /**
     * The owning provider.
     *
     * @return the provider
     */
    public AssetProvider provider() {
        return provider;
    }

    /**
     * The asset kind.
     *
     * @return the kind
     */
    public AssetKind kind() {
        return kind;
    }

    /**
     * The identity components in the per-kind order documented on {@link AssetKind}. Object keys
     * and paths are raw UTF-8, never percent-encoded.
     *
     * @return an immutable list of parts
     */
    public List<String> parts() {
        return parts;
    }

    /**
     * The canonical URI, the identity of the asset.
     *
     * @return the canonical URI
     */
    public String uri() {
        return uri;
    }

    /**
     * Non-identity facts parsed from the input; see {@link Attribute}.
     *
     * @return an immutable, key-sorted map
     */
    public Map<String, String> attributes() {
        return attributes;
    }

    /**
     * One attribute.
     *
     * @param name an {@link Attribute} name
     * @return the value, or empty
     */
    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    /**
     * The {@code dlap_assets.cloud_provider} value.
     *
     * @return the provider name
     */
    public String cloudProvider() {
        return provider.cloudProvider();
    }

    /**
     * The {@code dlap_assets.service_type} value.
     *
     * @return the service type
     */
    public String serviceType() {
        return kind.serviceType();
    }

    /**
     * The container one level up: an object's bucket, a table's dataset or schema, a schema's
     * catalog, a container's storage account, a Databricks run's job (when the job ID is known).
     *
     * @return the parent, or empty for a top-level asset
     */
    public Optional<CanonicalAssetId> parent() {
        return Optional.ofNullable(parent);
    }

    /**
     * The asset that events about this asset are attributed to under ADR-0046: the parent
     * container for an object-level kind, otherwise this asset.
     *
     * @return the roll-up target, never {@code null}
     */
    public CanonicalAssetId rollUp() {
        if (kind.isObjectLevel() && parent != null) {
            return parent;
        }
        return this;
    }

    /**
     * Whether this is an object-level asset (S3 object, GCS object, blob).
     *
     * @return true for an object-level kind
     */
    public boolean isObjectLevel() {
        return kind.isObjectLevel();
    }

    /**
     * Equality is URI equality.
     *
     * @param o the other object
     * @return true when both are canonical IDs with the same URI
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof CanonicalAssetId other && uri.equals(other.uri);
    }

    /**
     * Hash of the URI.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return uri.hashCode();
    }

    /**
     * The canonical URI.
     *
     * @return {@link #uri()}
     */
    @Override
    public String toString() {
        return uri;
    }
}
