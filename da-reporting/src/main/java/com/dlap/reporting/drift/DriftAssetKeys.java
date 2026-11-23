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

package com.dlap.reporting.drift;

import java.util.Optional;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.assetid.CanonicalAssetId;

/**
 * The asset side of the drift join key (enforcement-contract.md section 6.1).
 *
 * <p>Both inputs go through {@link AssetIdNormalizer} (ADR-0047, plan rule R8) and are rolled up
 * to the catalog asset under ADR-0046 ({@link CanonicalAssetId#rollUp()}: an object read counts
 * against its bucket or container), so a decision the PDP made for {@code bq://p/d/t} and a read
 * the audit log records as {@code projects/p/datasets/d/tables/t} share one key. An identifier the
 * normalizer rejects is kept as written, so producers that already agree on an id (a catalog UUID
 * on both sides) still join; such keys are reported as {@code native}.
 */
public final class DriftAssetKeys {

    /**
     * An asset key.
     *
     * @param key       the join key
     * @param canonical whether {@code key} is a canonical URI
     */
    public record AssetKey(String key, boolean canonical) {
    }

    private DriftAssetKeys() {
    }

    /**
     * The key of a read's target.
     *
     * @param canonicalUri the event's {@code targetResource.canonicalUri}; may be blank
     * @param nativeId     the event's {@code targetResource.id}; may be blank
     * @param provider     the event's provider, the hint for native ids; may be {@code null}
     * @return the key, or empty when the event names no target
     */
    public static Optional<AssetKey> forRead(String canonicalUri, String nativeId, AssetProvider provider) {
        AssetIdHints hints = provider == null ? AssetIdHints.none() : AssetIdHints.of(provider);
        return canonical(canonicalUri, AssetIdHints.none())
                .or(() -> canonical(nativeId, hints))
                .or(() -> raw(canonicalUri))
                .or(() -> raw(nativeId));
    }

    /**
     * The key of a decision's resource.
     *
     * @param assetUri the event's {@code assetUri}; may be blank
     * @param assetId  the event's {@code assetId} (usually the catalog UUID); may be blank
     * @return the key, or empty when the event names no resource
     */
    public static Optional<AssetKey> forDecision(String assetUri, String assetId) {
        return canonical(assetUri, AssetIdHints.none())
                .or(() -> canonical(assetId, AssetIdHints.none()))
                .or(() -> raw(assetUri))
                .or(() -> raw(assetId));
    }

    private static Optional<AssetKey> canonical(String id, AssetIdHints hints) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return AssetIdNormalizer.normalize(id.strip(), hints)
                .map(c -> new AssetKey(c.rollUp().uri(), true));
    }

    private static Optional<AssetKey> raw(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AssetKey(id.strip(), false));
    }
}
