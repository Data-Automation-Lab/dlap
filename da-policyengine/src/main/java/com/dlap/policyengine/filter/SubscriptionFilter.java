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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.policyengine.dto.EventStreamDTO;
import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventType;

/**
 * The typed filter of an event subscription (SEC-04, ADR-0057). It replaces the MVEL rule
 * conditions, which were executable code.
 *
 * <p>Five fields, each a list of accepted values:
 * <ul>
 *   <li>{@code eventTypes}: the event type is one of them;</li>
 *   <li>{@code severities}: the event severity is one of them (an event without a severity
 *       counts as {@link EventSeverity#INFO}, as the event ledger stores it);</li>
 *   <li>{@code sourceServices}: the emitting service is one of them;</li>
 *   <li>{@code assetPrefixes}: a canonical asset URI of the event (docs/specs/asset-id.md)
 *       starts with one of them, as plain string prefixes;</li>
 *   <li>{@code labels}: the event names at least one of them, compared without case.</li>
 * </ul>
 * An empty list does not constrain. All non-empty fields must hold (conjunction). A field
 * the event cannot answer, such as an asset prefix for an event with no resolvable asset,
 * does not match: a filter only ever narrows.
 *
 * @param eventTypes     accepted event types
 * @param severities     accepted severities
 * @param sourceServices accepted source services
 * @param assetPrefixes  accepted canonical asset URI prefixes
 * @param labels         accepted label names, lower-cased
 */
public record SubscriptionFilter(
        Set<EventType> eventTypes,
        Set<EventSeverity> severities,
        Set<String> sourceServices,
        Set<String> assetPrefixes,
        Set<String> labels) {

    /** The most values one filter field may hold. */
    public static final int MAX_VALUES = 50;

    /** The longest label name, matching {@code event_subscription_labels.label}. */
    public static final int MAX_LABEL_LENGTH = 255;

    /** The longest asset prefix: no canonical URI is longer, so a longer prefix never matches. */
    public static final int MAX_ASSET_PREFIX_LENGTH = AssetIdNormalizer.MAX_URI_LENGTH;

    /**
     * Creates a filter; {@code null} fields become empty, {@code null} values are dropped and
     * every set is copied.
     *
     * @param eventTypes     accepted event types
     * @param severities     accepted severities
     * @param sourceServices accepted source services
     * @param assetPrefixes  accepted canonical asset URI prefixes
     * @param labels         accepted label names; lower-cased here
     */
    public SubscriptionFilter {
        eventTypes = copy(eventTypes);
        severities = copy(severities);
        sourceServices = copy(sourceServices);
        assetPrefixes = copy(assetPrefixes);
        labels = labels == null ? Set.of()
                : labels.stream().filter(Objects::nonNull).map(l -> l.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Builds a filter from the stored subscription fields.
     *
     * @param eventTypes     stored event types; may be {@code null}
     * @param severities     stored severities; may be {@code null}
     * @param sourceServices stored source services; may be {@code null}
     * @param assetPrefixes  stored asset prefixes; may be {@code null}
     * @param labels         stored labels; may be {@code null}
     * @return the filter
     */
    public static SubscriptionFilter of(Collection<EventType> eventTypes,
                                        Collection<EventSeverity> severities,
                                        Collection<String> sourceServices,
                                        Collection<String> assetPrefixes,
                                        Collection<String> labels) {
        return new SubscriptionFilter(asSet(eventTypes), asSet(severities), asSet(sourceServices),
                asSet(assetPrefixes), asSet(labels));
    }

    /**
     * Tests the event against every non-empty field.
     *
     * @param event the event; {@code null} never matches
     * @return {@code true} when the event passes the filter
     */
    public boolean matches(EventStreamDTO event) {
        if (event == null) {
            return false;
        }
        if (!eventTypes.isEmpty() && !eventTypes.contains(event.getEventType())) {
            return false;
        }
        if (!severities.isEmpty()) {
            EventSeverity severity = event.getSeverity() != null ? event.getSeverity() : EventSeverity.INFO;
            if (!severities.contains(severity)) {
                return false;
            }
        }
        if (!sourceServices.isEmpty()
                && (event.getSourceService() == null || !sourceServices.contains(event.getSourceService()))) {
            return false;
        }
        if (!assetPrefixes.isEmpty() && !matchesAssetPrefix(EventFacts.canonicalAssetUris(event))) {
            return false;
        }
        if (!labels.isEmpty() && EventFacts.labels(event).stream().noneMatch(labels::contains)) {
            return false;
        }
        return true;
    }

    private boolean matchesAssetPrefix(Set<String> uris) {
        for (String uri : uris) {
            for (String prefix : assetPrefixes) {
                if (uri.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks the size of an enum-valued filter field and drops nulls and duplicates.
     *
     * @param values the supplied values; may be {@code null}
     * @param field  the field name, for the error message
     * @param <T>    the value type
     * @return the distinct non-null values in input order
     * @throws IllegalArgumentException when there are more than {@link #MAX_VALUES} values
     */
    public static <T> List<T> cleanValues(Collection<T> values, String field) {
        if (values == null) {
            return List.of();
        }
        requireAtMost(values.size(), field);
        return List.copyOf(asSet(values));
    }

    /**
     * Checks and cleans the asset prefixes a caller supplied.
     *
     * <p>Each prefix is stripped of surrounding white space and its URI scheme (the part
     * before {@code ://}) is lower-cased, because canonical URIs use lower-case schemes.
     * Nothing else changes: object keys and names are case-sensitive.
     *
     * @param prefixes the supplied prefixes; may be {@code null}
     * @return the cleaned, distinct prefixes in input order
     * @throws IllegalArgumentException when there are more than {@link #MAX_VALUES} prefixes,
     *     or one is blank, longer than {@link #MAX_ASSET_PREFIX_LENGTH} or holds a control
     *     character
     */
    public static List<String> cleanAssetPrefixes(Collection<String> prefixes) {
        if (prefixes == null) {
            return List.of();
        }
        requireAtMost(prefixes.size(), "assetPrefixes");
        Set<String> out = new LinkedHashSet<>();
        for (String raw : prefixes) {
            String prefix = requireText(raw, "assetPrefixes", MAX_ASSET_PREFIX_LENGTH);
            int scheme = prefix.indexOf("://");
            if (scheme > 0) {
                prefix = prefix.substring(0, scheme).toLowerCase(Locale.ROOT) + prefix.substring(scheme);
            }
            out.add(prefix);
        }
        return List.copyOf(out);
    }

    /**
     * Checks and cleans the label names a caller supplied.
     *
     * @param labels the supplied label names; may be {@code null}
     * @return the stripped, distinct names (compared without case) in input order
     * @throws IllegalArgumentException when there are more than {@link #MAX_VALUES} labels,
     *     or one is blank, longer than {@link #MAX_LABEL_LENGTH} or holds a control character
     */
    public static List<String> cleanLabels(Collection<String> labels) {
        if (labels == null) {
            return List.of();
        }
        requireAtMost(labels.size(), "labels");
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : labels) {
            String label = requireText(raw, "labels", MAX_LABEL_LENGTH);
            if (seen.add(label.toLowerCase(Locale.ROOT))) {
                out.add(label);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Checks the source services a caller supplied against the services that emit events.
     *
     * @param sourceServices the supplied names; may be {@code null}
     * @param known          the services that emit Event Center events
     * @return the distinct names in input order
     * @throws IllegalArgumentException when there are more than {@link #MAX_VALUES} names or
     *     one is not a known source service
     */
    public static List<String> cleanSourceServices(Collection<String> sourceServices, Collection<String> known) {
        if (sourceServices == null) {
            return List.of();
        }
        requireAtMost(sourceServices.size(), "sourceServices");
        Set<String> out = new LinkedHashSet<>();
        for (String raw : sourceServices) {
            String name = raw == null ? "" : raw.strip();
            if (!known.contains(name)) {
                throw new IllegalArgumentException("Unknown source service '" + truncate(name)
                        + "'. Known source services: " + String.join(", ", known) + ".");
            }
            out.add(name);
        }
        return List.copyOf(out);
    }

    private static String requireText(String raw, String field, int maxLength) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Entries in " + field + " must not be blank.");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("Entries in " + field + " may have at most "
                    + maxLength + " characters.");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Entries in " + field + " must not contain control characters.");
        }
        return value;
    }

    private static void requireAtMost(int size, String field) {
        if (size > MAX_VALUES) {
            throw new IllegalArgumentException(field + " may hold at most " + MAX_VALUES + " entries.");
        }
    }

    private static String truncate(String s) {
        return s.length() <= 64 ? s : s.substring(0, 64) + "...";
    }

    private static <T> Set<T> copy(Set<T> values) {
        return values == null ? Set.of()
                : values.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    private static <T> Set<T> asSet(Collection<T> values) {
        if (values == null) {
            return Set.of();
        }
        Set<T> out = new LinkedHashSet<>();
        for (T v : values) {
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }
}
