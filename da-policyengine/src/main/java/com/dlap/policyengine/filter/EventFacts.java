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

package com.dlap.policyengine.filter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.policyengine.dto.EventStreamDTO;

/**
 * Reads the asset identity and the labels an Event Center event carries (SEC-04, ADR-0057).
 *
 * <p>Event Center events come from several topics with different payload shapes (catalog
 * change events, discovery events, policy actions, label assignment changes). This class
 * is the one place that knows where each shape keeps the asset identifier and the label
 * names, so the typed subscription filter can stay a plain comparison.
 *
 * <p>Asset identifiers pass through {@link AssetIdNormalizer} (plan rule R8): only canonical
 * URIs (docs/specs/asset-id.md) are returned. An identifier the normalizer rejects is left
 * out, so an asset-prefix filter never matches an event whose asset it cannot name.
 */
public final class EventFacts {

    /** Top-level keys that hold an asset identifier, in the order they are read. */
    private static final List<String> ID_KEYS = List.of(
            "assetUri", "asset_uri", "canonicalUri", "canonical_uri",
            "targetResourceId", "nativeAssetId", "native_asset_id");

    /** Nested objects that may hold an identifier and the provider and type hints. */
    private static final List<String> NESTED_KEYS = List.of(
            "matchSnapshot", "assetSnapshot", "targetResource", "target_resource");

    /** Keys under {@code targetResource} that hold an identifier. */
    private static final List<String> TARGET_ID_KEYS = List.of("canonicalUri", "canonical_uri", "id");

    /** Keys that hold one label name, a list of names, or a comma-separated list. */
    private static final List<String> LABEL_KEYS = List.of("labels", "labelNames", "labelName", "label");

    /** Keys of {@code labelDetails.details} (da-catalog label assignment events). */
    private static final List<String> LABEL_DETAIL_KEYS = List.of("labels_assigned", "label_removed");

    private EventFacts() {
    }

    /**
     * The canonical URIs of the asset the event is about.
     *
     * @param event the event; may be {@code null}
     * @return the distinct canonical URIs, in discovery order; empty when none normalizes
     */
    public static Set<String> canonicalAssetUris(EventStreamDTO event) {
        Set<String> uris = new LinkedHashSet<>();
        if (event == null || event.getEventData() == null) {
            return uris;
        }
        Map<String, Object> data = event.getEventData();
        AssetIdHints topHints = hintsFrom(data);
        for (String key : ID_KEYS) {
            addCanonical(uris, data.get(key), topHints);
        }
        for (String nestedKey : NESTED_KEYS) {
            Map<String, Object> nested = asMap(data.get(nestedKey));
            if (nested == null) {
                continue;
            }
            AssetIdHints hints = merge(hintsFrom(nested), topHints);
            List<String> keys = nestedKey.startsWith("target") ? TARGET_ID_KEYS : ID_KEYS;
            for (String key : keys) {
                addCanonical(uris, nested.get(key), hints);
            }
        }
        return uris;
    }

    /**
     * The label names the event carries, lower-cased with {@link Locale#ROOT}.
     *
     * @param event the event; may be {@code null}
     * @return the distinct label names; empty when the event names no label
     */
    public static Set<String> labels(EventStreamDTO event) {
        Set<String> labels = new LinkedHashSet<>();
        if (event == null || event.getEventData() == null) {
            return labels;
        }
        Map<String, Object> data = event.getEventData();
        addLabels(labels, data);
        for (String nestedKey : List.of("matchSnapshot", "assetSnapshot", "actionParameters")) {
            Map<String, Object> nested = asMap(data.get(nestedKey));
            if (nested != null) {
                addLabels(labels, nested);
            }
        }
        Map<String, Object> labelDetails = asMap(data.get("labelDetails"));
        Map<String, Object> details = labelDetails != null ? asMap(labelDetails.get("details")) : null;
        if (details != null) {
            for (String key : LABEL_DETAIL_KEYS) {
                addLabelValue(labels, details.get(key));
            }
        }
        return labels;
    }

    private static void addLabels(Set<String> out, Map<String, Object> map) {
        for (String key : LABEL_KEYS) {
            addLabelValue(out, map.get(key));
        }
    }

    private static void addLabelValue(Set<String> out, Object value) {
        if (value instanceof Collection<?> c) {
            for (Object item : c) {
                addLabelValue(out, item);
            }
        } else if (value instanceof String s) {
            for (String part : s.split(",")) {
                String label = part.strip();
                if (!label.isEmpty()) {
                    out.add(label.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private static void addCanonical(Set<String> out, Object raw, AssetIdHints hints) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return;
        }
        Optional<CanonicalAssetId> id = AssetIdNormalizer.normalize(s, hints);
        id.map(CanonicalAssetId::uri).ifPresent(out::add);
    }

    private static AssetIdHints hintsFrom(Map<String, Object> map) {
        AssetIdHints.Builder b = AssetIdHints.builder();
        Object provider = map.get("cloudProvider");
        if (provider instanceof String p) {
            AssetProvider.fromString(p).ifPresent(b::provider);
        }
        Object type = map.get("serviceType") != null ? map.get("serviceType") : map.get("assetType");
        if (type instanceof String t) {
            b.serviceType(t);
        }
        return b.build();
    }

    /** Fills the provider and kind of {@code primary} from {@code fallback} when absent. */
    private static AssetIdHints merge(AssetIdHints primary, AssetIdHints fallback) {
        AssetIdHints out = primary;
        if (out.provider() == null && fallback.provider() != null) {
            out = out.withProvider(fallback.provider());
        }
        if (out.kind() == null && fallback.kind() != null) {
            out = out.withKind(fallback.kind());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
