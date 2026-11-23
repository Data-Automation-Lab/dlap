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

package com.dlap.logingest.parse.core;

import com.dlap.common.assetid.AssetIdHints;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The effective settings of one binding, as parsers and the registry see them (design B2).
 *
 * <p>A binding is one scope (an AWS account, a GCP project, an Azure subscription, a Databricks
 * workspace) of one source kind. LOG-02b builds instances from the bindings file, after merging
 * the family's YAML {@code settings} under the binding's own keys; parsers never read
 * configuration themselves. The record is immutable.
 *
 * @param bindingId          the binding id
 * @param sourceKind         the binding's source kind
 * @param pinnedVersion      the pinned schema version, or empty for {@code version: auto}
 * @param onMismatch         what to do when the detected version differs from the pin
 * @param fieldOverrides     canonical field to replacement paths, tried before the mapping's
 * @param operationOverrides classification rows that win over the mapping's table
 * @param persistRaw         whether parsed events keep their raw record in {@code log_events}
 * @param maxRecordBytes     records above this size are quarantined as {@code OVERSIZE_RECORD}
 * @param assetIdHints       account, project, region, metastore and workspace for
 *                           {@code AssetIdNormalizer}
 */
public record BindingConfig(
        String bindingId,
        String sourceKind,
        Optional<String> pinnedVersion,
        OnMismatch onMismatch,
        Map<CanonicalField, List<FieldPath>> fieldOverrides,
        List<ClassificationRule> operationOverrides,
        PersistRaw persistRaw,
        long maxRecordBytes,
        AssetIdHints assetIdHints) {

    /**
     * Default record size limit: the Kafka default {@code max.message.bytes}, 1 MiB (log-event
     * spec section 2).
     */
    public static final long DEFAULT_MAX_RECORD_BYTES = 1_048_576L;

    /** The pin value that means "detect per record". */
    public static final String AUTO = "auto";

    /**
     * Validates and freezes the settings.
     *
     * @param bindingId          see the record description
     * @param sourceKind         see the record description
     * @param pinnedVersion      see the record description
     * @param onMismatch         see the record description
     * @param fieldOverrides     see the record description
     * @param operationOverrides see the record description
     * @param persistRaw         see the record description
     * @param maxRecordBytes     see the record description
     * @param assetIdHints       see the record description
     */
    public BindingConfig {
        FormatNames.requireBindingId(bindingId);
        FormatNames.requireSourceKind(sourceKind);
        pinnedVersion = pinnedVersion == null ? Optional.empty() : pinnedVersion.filter(v -> !AUTO.equals(v));
        pinnedVersion.ifPresent(FormatNames::requireVersion);
        onMismatch = onMismatch == null ? OnMismatch.DEFAULT : onMismatch;
        Map<CanonicalField, List<FieldPath>> overrides = new EnumMap<>(CanonicalField.class);
        if (fieldOverrides != null) {
            fieldOverrides.forEach((field, paths) -> {
                Objects.requireNonNull(field, "field override key");
                if (paths == null || paths.isEmpty()) {
                    throw new IllegalArgumentException("field override " + field.key() + " needs at least one path");
                }
                overrides.put(field, List.copyOf(paths));
            });
        }
        fieldOverrides = Map.copyOf(overrides);
        operationOverrides = operationOverrides == null ? List.of() : List.copyOf(operationOverrides);
        persistRaw = persistRaw == null ? PersistRaw.DEFAULT : persistRaw;
        if (maxRecordBytes < 1) {
            throw new IllegalArgumentException("maxRecordBytes must be positive");
        }
        assetIdHints = assetIdHints == null ? AssetIdHints.none() : assetIdHints;
    }

    /**
     * A binding with every default: version {@code auto}, {@link OnMismatch#DEFAULT}, no
     * overrides, the default raw-record policy and size limit, no asset-id hints.
     *
     * @param bindingId  the binding id
     * @param sourceKind the source kind
     * @return the settings
     */
    public static BindingConfig auto(String bindingId, String sourceKind) {
        return new BindingConfig(bindingId, sourceKind, Optional.empty(), OnMismatch.DEFAULT, Map.of(), List.of(),
                PersistRaw.DEFAULT, DEFAULT_MAX_RECORD_BYTES, AssetIdHints.none());
    }

    /**
     * The same binding pinned to a version.
     *
     * @param version the pin, or {@value #AUTO}
     * @param policy  the mismatch policy
     * @return a new binding
     */
    public BindingConfig pinned(String version, OnMismatch policy) {
        return new BindingConfig(bindingId, sourceKind, Optional.ofNullable(version), policy, fieldOverrides,
                operationOverrides, persistRaw, maxRecordBytes, assetIdHints);
    }

    /**
     * The same binding with another size limit.
     *
     * @param bytes the limit
     * @return a new binding
     */
    public BindingConfig withMaxRecordBytes(long bytes) {
        return new BindingConfig(bindingId, sourceKind, pinnedVersion, onMismatch, fieldOverrides,
                operationOverrides, persistRaw, bytes, assetIdHints);
    }

    /**
     * The same binding with field overrides.
     *
     * @param overrides canonical field to paths
     * @return a new binding
     */
    public BindingConfig withFieldOverrides(Map<CanonicalField, List<FieldPath>> overrides) {
        return new BindingConfig(bindingId, sourceKind, pinnedVersion, onMismatch, overrides,
                operationOverrides, persistRaw, maxRecordBytes, assetIdHints);
    }

    /**
     * The same binding with operation overrides.
     *
     * @param overrides classification rows
     * @return a new binding
     */
    public BindingConfig withOperationOverrides(List<ClassificationRule> overrides) {
        return new BindingConfig(bindingId, sourceKind, pinnedVersion, onMismatch, fieldOverrides,
                overrides, persistRaw, maxRecordBytes, assetIdHints);
    }

    /**
     * The same binding with asset-id hints.
     *
     * @param hints the hints
     * @return a new binding
     */
    public BindingConfig withAssetIdHints(AssetIdHints hints) {
        return new BindingConfig(bindingId, sourceKind, pinnedVersion, onMismatch, fieldOverrides,
                operationOverrides, persistRaw, maxRecordBytes, hints);
    }
}
