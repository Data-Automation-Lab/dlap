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

package com.dlap.autodelete.retire;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.assetid.CanonicalAssetId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The canonical identity of what a delete acts on (ADR-0047, work-plan rule R8): every asset
 * identifier the executor stores or compares passes through {@link AssetIdNormalizer}.
 */
public final class CanonicalTarget {

    private CanonicalTarget() {
    }

    /**
     * The outcome of resolving a target.
     *
     * @param id     the canonical id, or null when refused
     * @param reason why not, or null
     * @param detail a sentence, or null
     */
    public record Resolution(CanonicalAssetId id, RefusalReason reason, String detail) {

        /** @return true when the target resolved */
        public boolean ok() {
            return id != null;
        }
    }

    /**
     * Resolves the URI of an action against the catalog's URI. Both are normalized with the
     * provider and kind as hints; when both are present they must name the same asset.
     *
     * @param actionUri    the URI the action names, or null
     * @param catalogUri   the URI the catalog stores, or null
     * @param provider     the provider, or null
     * @param serviceType  the asset kind, or null
     * @return the canonical id, or a refusal
     */
    public static Resolution resolve(String actionUri, String catalogUri, String provider, String serviceType) {
        AssetIdHints hints = hints(provider, serviceType);
        Optional<CanonicalAssetId> fromAction = normalize(actionUri, hints);
        Optional<CanonicalAssetId> fromCatalog = normalize(catalogUri, hints);
        if (fromAction.isPresent() && fromCatalog.isPresent()
                && !fromAction.get().uri().equals(fromCatalog.get().uri())) {
            return new Resolution(null, RefusalReason.URI_MISMATCH, "the action names " + fromAction.get().uri()
                    + " but the catalog stores " + fromCatalog.get().uri());
        }
        Optional<CanonicalAssetId> id = fromAction.or(() -> fromCatalog);
        if (id.isEmpty()) {
            return new Resolution(null, RefusalReason.URI_UNRESOLVABLE,
                    "no canonical asset URI for the action (" + actionUri + ") or the catalog (" + catalogUri + ")");
        }
        return new Resolution(id.get(), null, null);
    }

    /**
     * Normalizes a stored canonical URI again (it parses to itself).
     *
     * @param uri the URI
     * @return the id, or empty
     */
    public static Optional<CanonicalAssetId> parse(String uri) {
        return normalize(uri, AssetIdHints.none());
    }

    /**
     * The URI and each of its parents, longest first: {@code s3://b/a/k}, {@code s3://b/a},
     * {@code s3://b}. A proof event on any of them may prove a disposition on the shorter ones.
     *
     * @param uri a canonical URI
     * @return the candidates
     */
    public static List<String> selfAndParents(String uri) {
        List<String> out = new ArrayList<>();
        if (uri == null || uri.isBlank()) {
            return out;
        }
        String current = uri;
        int schemeEnd = uri.indexOf("://");
        int floor = schemeEnd < 0 ? 0 : schemeEnd + 3;
        out.add(current);
        while (true) {
            int slash = current.lastIndexOf('/');
            if (slash <= floor) {
                break;
            }
            current = current.substring(0, slash);
            out.add(current);
        }
        return out;
    }

    private static Optional<CanonicalAssetId> normalize(String raw, AssetIdHints hints) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return AssetIdNormalizer.normalize(raw, hints);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static AssetIdHints hints(String provider, String serviceType) {
        AssetIdHints hints = AssetIdHints.none();
        Optional<AssetProvider> p = provider == null ? Optional.empty() : AssetProvider.fromString(provider);
        if (p.isPresent()) {
            hints = hints.withProvider(p.get());
        }
        Optional<AssetKind> k = serviceType == null ? Optional.empty() : AssetKind.fromServiceType(serviceType);
        if (k.isPresent()) {
            hints = hints.withKind(k.get());
        }
        return hints;
    }
}
