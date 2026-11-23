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

package com.dlap.catalog.coverage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.dlap.common.logevent.SourceFormats;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The wire form of a read-coverage observation on the log spine (ADR text in the COV-01 result,
 * spec "Read coverage" in {@code docs/specs/asset-descriptor.md}). da-discovery writes it with
 * its own copy of these names; the two copies are held together by the shared example in the
 * spec and by each module's wire test.
 *
 * <p>An observation is a {@code DlapLogEvent} with {@code source_format}
 * {@code DLAP_READ_COVERAGE/config-observation}, {@code change_type} unspecified (so the router
 * never routes it and no lifecycle consumer changes state from it), {@code event_time} the
 * observation time, {@code target_resource.id} the root URI, {@code target_resource.project} the
 * scope and these {@code target_resource.technical_metadata} keys:
 *
 * <table>
 *   <caption>Observation keys</caption>
 *   <tr><th>Key</th><th>Value</th></tr>
 *   <tr><td>{@value #KEY_LEVEL}</td><td>{@code FULL}, {@code PARTIAL}, {@code NONE} or {@code UNKNOWN}</td></tr>
 *   <tr><td>{@value #KEY_REASONS}</td><td>a JSON array of strings</td></tr>
 *   <tr><td>{@value #KEY_APPLIES_TO}</td><td>comma-separated service types</td></tr>
 *   <tr><td>{@value #KEY_CHANNEL}</td><td>the log source kind that carries the reads</td></tr>
 *   <tr><td>{@value #KEY_SCOPE}</td><td>the account, project or subscription id</td></tr>
 * </table>
 */
public final class ReadCoverageWire {

    /** Source kind of an observation. */
    public static final String SOURCE_KIND = "DLAP_READ_COVERAGE";
    /** Envelope of an observation. */
    public static final String ENVELOPE = "config-observation";
    /** {@code target_resource.kind} of an observation. */
    public static final String TARGET_KIND = "DLAP_READ_COVERAGE";

    /** Coverage level key. */
    public static final String KEY_LEVEL = "readCoverage.level";
    /** Reasons key. */
    public static final String KEY_REASONS = "readCoverage.reasons";
    /** Service types key. */
    public static final String KEY_APPLIES_TO = "readCoverage.appliesTo";
    /** Channel key. */
    public static final String KEY_CHANNEL = "readCoverage.channel";
    /** Scope key. */
    public static final String KEY_SCOPE = "readCoverage.scope";

    /** At most this many reasons are kept; the rest are summarised in one more reason. */
    public static final int MAX_REASONS = 20;
    /** A reason longer than this is cut. */
    public static final int MAX_REASON_LENGTH = 500;

    private static final Pattern SOURCE_KIND_SYNTAX = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,255}");

    /** Root scheme, then the service types an observation under that root may name. */
    private static final Map<String, Set<String>> KINDS_BY_SCHEME = Map.of(
            "s3://", Set.of("S3_BUCKET", "S3_OBJECT"),
            "gs://", Set.of("GCS_BUCKET", "GCS_OBJECT"),
            "bq://", Set.of("BIGQUERY_DATASET", "BIGQUERY_TABLE"),
            "az://", Set.of("BLOB_STORAGE", "BLOB_CONTAINER", "BLOB_OBJECT"));

    /** Provider of each root scheme. */
    private static final Map<String, String> PROVIDER_BY_SCHEME = Map.of(
            "s3://", "AWS", "gs://", "GCP", "bq://", "GCP", "az://", "AZURE");

    private ReadCoverageWire() {
    }

    /**
     * A cheap test on the raw record value, before decoding: every observation names its source
     * kind, so a record without the text is not one.
     *
     * @param payload the record value
     * @return false when the record cannot be an observation
     */
    public static boolean mightBeObservation(String payload) {
        return payload != null && payload.contains(SOURCE_KIND);
    }

    /**
     * Whether a decoded event is an observation.
     *
     * @param event the event
     * @return true when its source kind is {@value #SOURCE_KIND}
     */
    public static boolean isObservation(DlapLogEvent event) {
        return SourceFormats.sourceKind(event.getSourceFormat()).filter(SOURCE_KIND::equals).isPresent();
    }

    /**
     * Reads an observation from an event.
     *
     * @param event  an event for which {@link #isObservation} holds
     * @param mapper reads the reasons array
     * @return the observation
     * @throws InvalidReadCoverageObservationException when a field is missing or malformed; the
     *                                                 message names the field, never its value
     */
    public static ReadCoverageObservation decode(DlapLogEvent event, ObjectMapper mapper) {
        if (!event.hasEventTime()) {
            throw invalid("event_time");
        }
        SourceProvider sp = event.getSourceProvider();
        String provider = switch (sp) {
            case AWS, GCP, AZURE -> sp.name();
            default -> throw invalid("source_provider");
        };
        Map<String, String> meta = event.getTargetResource().getTechnicalMetadataMap();
        String root = event.getTargetResource().getId();
        String scheme = scheme(root).orElseThrow(() -> invalid("target_resource.id"));
        if (!provider.equals(PROVIDER_BY_SCHEME.get(scheme))) {
            throw invalid("target_resource.id");
        }
        String scope = text(meta, KEY_SCOPE).orElseThrow(() -> invalid(KEY_SCOPE));
        String channel = text(meta, KEY_CHANNEL).filter(c -> SOURCE_KIND_SYNTAX.matcher(c).matches())
                .orElseThrow(() -> invalid(KEY_CHANNEL));
        Set<String> appliesTo = appliesTo(meta, KINDS_BY_SCHEME.get(scheme));
        ReadCoverageLevel level;
        try {
            level = ReadCoverageLevel.parse(meta.get(KEY_LEVEL)).orElse(null);
        } catch (IllegalArgumentException e) {
            throw invalid(KEY_LEVEL);
        }
        List<String> reasons = reasons(meta.get(KEY_REASONS), mapper);
        Instant observedAt = Instant.ofEpochSecond(event.getEventTime().getSeconds(), event.getEventTime().getNanos());
        return new ReadCoverageObservation(event.getEventId(), provider, scope.strip(), root, appliesTo, channel,
                level, reasons, observedAt, event.getParserVersion());
    }

    /**
     * The scheme of a root URI, when the root is one an observation may name: a scheme, then one
     * name ({@code s3://bucket}, {@code gs://bucket}, {@code az://account}), or for BigQuery a
     * project and optionally a dataset ({@code bq://project}, {@code bq://project/dataset}).
     *
     * @param root the root
     * @return the scheme, or empty when the root is not acceptable
     */
    static Optional<String> scheme(String root) {
        if (root == null) {
            return Optional.empty();
        }
        for (String scheme : KINDS_BY_SCHEME.keySet()) {
            if (!root.startsWith(scheme)) {
                continue;
            }
            String[] parts = root.substring(scheme.length()).split("/", -1);
            int max = "bq://".equals(scheme) ? 2 : 1;
            if (parts.length > max) {
                return Optional.empty();
            }
            for (String part : parts) {
                if (!NAME.matcher(part).matches()) {
                    return Optional.empty();
                }
            }
            return Optional.of(scheme);
        }
        return Optional.empty();
    }

    private static Set<String> appliesTo(Map<String, String> meta, Set<String> allowed) {
        String raw = text(meta, KEY_APPLIES_TO).orElseThrow(() -> invalid(KEY_APPLIES_TO));
        Set<String> kinds = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String kind = part.strip().toUpperCase(Locale.ROOT);
            if (!allowed.contains(kind)) {
                throw invalid(KEY_APPLIES_TO);
            }
            kinds.add(kind);
        }
        return kinds;
    }

    private static List<String> reasons(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw invalid(KEY_REASONS);
        }
        if (node == null || !node.isArray()) {
            throw invalid(KEY_REASONS);
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw invalid(KEY_REASONS);
            }
            String reason = item.asText().strip();
            if (reason.isEmpty()) {
                continue;
            }
            out.add(reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason);
        }
        return bounded(out);
    }

    /**
     * Keeps at most {@link #MAX_REASONS} reasons; the last kept one says how many were dropped.
     *
     * @param reasons the reasons
     * @return the bounded list
     */
    static List<String> bounded(List<String> reasons) {
        if (reasons.size() <= MAX_REASONS) {
            return reasons;
        }
        List<String> out = new ArrayList<>(reasons.subList(0, MAX_REASONS - 1));
        out.add((reasons.size() - MAX_REASONS + 1) + " more reasons not shown");
        return out;
    }

    private static Optional<String> text(Map<String, String> meta, String key) {
        String v = meta.get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.strip());
    }

    private static InvalidReadCoverageObservationException invalid(String field) {
        return new InvalidReadCoverageObservationException("read-coverage observation has a missing or malformed "
                + field);
    }
}
