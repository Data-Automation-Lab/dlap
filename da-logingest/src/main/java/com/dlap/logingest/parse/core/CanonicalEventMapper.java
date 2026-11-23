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

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.RequestMetadata;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Writes parse outcomes onto the wire contract: canonical events become {@code DlapLogEvent}s,
 * quarantined records become {@code LogQuarantineRecord}s (log-event spec sections 4, 5 and 9).
 *
 * <p>What the mapper adds to a parser's event:
 * <ul>
 *   <li>{@code event_id} from {@link CanonicalEvent#eventId()}, {@code native_event_id},
 *       {@code supersedes_event_id} from the superseded native key;</li>
 *   <li>{@code source_format} and {@code source_schema_version} from the detected format,
 *       {@code parser_version}, {@code binding_id}, and {@code origin} from the
 *       {@link IngestMode};</li>
 *   <li>{@code parse_status}: {@code PARTIAL} for a pass-through, {@code WARNINGS} when the record
 *       or the event carries a warning, otherwise {@code FULL}; {@code parse_warnings} sorted;</li>
 *   <li>{@code raw_event}: the parser's raw value or the record body, as a {@code Struct}
 *       ({@code {"value": ...}} for a non-object);</li>
 *   <li>{@code ingest_time} from the injected {@link Clock}, the only DLAP clock on the event, and
 *       {@code schema_version = v1}.</li>
 * </ul>
 * Output always passes {@link DlapLogEventCodec#decodeStrict}; the mapper tests assert it.
 */
public final class CanonicalEventMapper {

    private static final Comparator<ParseWarning> BY_NUMBER = Comparator.comparingInt(ParseWarning::getNumber);

    private final Clock clock;
    private final int maxExcerptBytes;

    /**
     * Creates a mapper with the default excerpt bound.
     *
     * @param clock the clock for {@code ingest_time} and {@code quarantined_time}
     */
    public CanonicalEventMapper(Clock clock) {
        this(clock, RawExcerpts.DEFAULT_MAX_BYTES);
    }

    /**
     * Creates a mapper.
     *
     * @param clock           the clock for {@code ingest_time} and {@code quarantined_time}
     * @param maxExcerptBytes bound of {@code raw_excerpt} in UTF-8 bytes
     */
    public CanonicalEventMapper(Clock clock, int maxExcerptBytes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxExcerptBytes < 1) {
            throw new IllegalArgumentException("maxExcerptBytes must be positive");
        }
        this.maxExcerptBytes = maxExcerptBytes;
    }

    /**
     * The events an outcome publishes: every event of a parsed record, the pass-through event of a
     * quarantined record, nothing for a skipped one.
     *
     * @param outcome the outcome
     * @return the wire events, in record order
     */
    public List<DlapLogEvent> events(ParseOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        Instant ingest = clock.instant();
        return switch (outcome.result()) {
            case ParseResult.Parsed p -> p.events().stream()
                    .map(e -> toProto(e, p.warningsOf(e), outcome, ingest))
                    .toList();
            case ParseResult.Quarantined q -> q.passThrough()
                    .map(e -> List.of(toProto(e, e.warnings(), outcome, ingest)))
                    .orElse(List.of());
            case ParseResult.Skipped s -> List.of();
        };
    }

    /**
     * The quarantine record of a quarantined outcome.
     *
     * @param outcome the outcome
     * @return the record, or empty when the outcome is not quarantined
     */
    public Optional<LogQuarantineRecord> quarantine(ParseOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (!(outcome.result() instanceof ParseResult.Quarantined q)) {
            return Optional.empty();
        }
        RawRecord record = outcome.record();
        String sha = record.sha256();
        RawExcerpts.Excerpt excerpt = RawExcerpts.excerpt(record.lenientText(), maxExcerptBytes);
        LogQuarantineRecord.Builder b = LogQuarantineRecord.newBuilder()
                .setQuarantineId(LogEventIds.quarantineId(outcome.bindingId(), record.ref().toProto(), sha))
                .setBindingId(outcome.bindingId())
                .setSourceProvider(outcome.sourceProvider().orElse(SourceProvider.SOURCE_PROVIDER_UNSPECIFIED))
                .setSourceKind(record.sourceKind())
                .setEnvelope(record.envelope())
                .setDetectedVersion(outcome.detected().map(FormatKey::schemaVersion).orElse(""))
                .setPinnedVersion(outcome.pinnedVersion().orElse(""))
                .setParserVersion(outcome.parserVersion().orElse(""))
                .setReason(q.reason())
                .setReasonDetail(q.detail())
                .setRawRef(record.ref().toProto())
                .setRawExcerpt(excerpt.text())
                .setRawTruncated(excerpt.truncated())
                .setRawSha256(sha)
                .setRawSizeBytes(record.sizeBytes())
                .setQuarantinedTime(timestamp(clock.instant()))
                .setSchemaVersion(DlapLogEventCodec.SCHEMA_VERSION);
        q.passThrough().ifPresent(e -> b
                .setEventTime(timestamp(e.eventTime()))
                .setNativeEventId(e.nativeKey())
                .setPassThroughEventId(e.eventId()));
        return Optional.of(b.build());
    }

    /**
     * The usage-dedup key of a wire event (ADR-0045 decision 4, critic item 14): copies of one
     * action that share a CloudTrail {@code sharedEventID} count once per target, everything else
     * once per {@code event_id}. Delegates to {@code LogEventIds.usageDedupKey}, the one
     * implementation.
     *
     * @param event the event
     * @return the key
     */
    public static String usageDedupKey(DlapLogEvent event) {
        return LogEventIds.usageDedupKey(event);
    }

    private DlapLogEvent toProto(CanonicalEvent e, Set<ParseWarning> warnings, ParseOutcome outcome, Instant ingest) {
        FormatKey detected = outcome.detected().orElseThrow(() ->
                new IllegalStateException("an outcome with events always has a detected format"));
        DlapLogEvent.Builder b = DlapLogEvent.newBuilder()
                .setEventId(e.eventId())
                .setEventTime(timestamp(e.eventTime()))
                .setIngestTime(timestamp(ingest))
                .setSourceProvider(e.provider())
                .setSourceService(e.service())
                .setChangeType(e.changeType())
                .setOperation(e.operation())
                .setResultStatus(e.result())
                .setPrincipal(principal(e.principal()))
                .setTargetResource(resource(e.target()))
                .setRequestMetadata(request(e.request()))
                .setRawEvent(rawEvent(e.raw() != null ? e.raw() : outcome.record().body()))
                .setSchemaVersion(DlapLogEventCodec.SCHEMA_VERSION)
                .setSourceFormat(detected.sourceFormat())
                .setSourceSchemaVersion(detected.schemaVersion())
                .setParserVersion(outcome.parserVersion().orElse(""))
                .setBindingId(outcome.bindingId())
                .setNativeEventId(e.nativeKey())
                .setEventCategory(e.category())
                .setOrigin(outcome.record().mode().origin())
                .setParseStatus(e.passThrough()
                        ? ParseStatus.PARSE_STATUS_PARTIAL
                        : warnings.isEmpty() ? ParseStatus.PARSE_STATUS_FULL : ParseStatus.PARSE_STATUS_WARNINGS)
                .setSharedEventId(e.sharedEventId());
        Instant observed = e.observedTime() != null
                ? e.observedTime()
                : outcome.record().observedTime().orElse(null);
        if (observed != null) {
            b.setObservedTime(timestamp(observed));
        }
        e.supersedesEventId().ifPresent(b::setSupersedesEventId);
        e.related().forEach(r -> b.addRelatedResources(resource(r)));
        warnings.stream().sorted(BY_NUMBER).forEach(b::addParseWarnings);
        return b.build();
    }

    private static Principal principal(CanonicalEvent.Actor a) {
        return Principal.newBuilder()
                .setType(a.type())
                .setId(a.id())
                .setDisplayName(a.displayName())
                .setAccountId(a.accountId())
                .build();
    }

    private static RequestMetadata request(CanonicalEvent.Request r) {
        return RequestMetadata.newBuilder()
                .setCallerIp(r.callerIp())
                .setUserAgent(r.userAgent())
                .setRequestId(r.requestId())
                .setTraceId(r.traceId())
                .build();
    }

    private static TargetResource resource(CanonicalEvent.Resource r) {
        TargetResource.Builder b = TargetResource.newBuilder()
                .setKind(r.kind())
                .setId(r.id())
                .setName(r.name())
                .setProject(r.project())
                .setRegion(r.region())
                .setResourcePath(r.resourcePath())
                .putAllLabels(r.labels())
                .setCanonicalUri(r.canonicalUri())
                .putAllTechnicalMetadata(r.technicalMetadata())
                .setRelation(r.relation());
        if (r.createdTime() != null) {
            b.setCreatedTime(timestamp(r.createdTime()));
        }
        if (r.sizeBytes() != null) {
            b.setSizeBytes(r.sizeBytes());
        }
        return b.build();
    }

    /**
     * Converts an instant to a protobuf timestamp.
     *
     * @param instant the instant, inside the protobuf range (years 1 to 9999)
     * @return the timestamp
     */
    static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    /**
     * Converts a JSON value to the {@code raw_event} Struct. Numbers become doubles, as on the wire.
     *
     * @param raw the value
     * @return the struct; a non-object is wrapped as {@code {"value": raw}}
     */
    static Struct rawEvent(JsonNode raw) {
        if (raw == null || raw.isMissingNode()) {
            return Struct.getDefaultInstance();
        }
        if (raw.isObject()) {
            return struct(raw);
        }
        return Struct.newBuilder().putFields("value", value(raw)).build();
    }

    private static Struct struct(JsonNode object) {
        Struct.Builder b = Struct.newBuilder();
        for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            b.putFields(f.getKey(), value(f.getValue()));
        }
        return b.build();
    }

    private static Value value(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (node.isObject()) {
            return Value.newBuilder().setStructValue(struct(node)).build();
        }
        if (node.isArray()) {
            ListValue.Builder list = ListValue.newBuilder();
            node.forEach(n -> list.addValues(value(n)));
            return Value.newBuilder().setListValue(list).build();
        }
        if (node.isBoolean()) {
            return Value.newBuilder().setBoolValue(node.booleanValue()).build();
        }
        if (node.isNumber()) {
            double d = node.doubleValue();
            if (Double.isFinite(d)) {
                return Value.newBuilder().setNumberValue(d).build();
            }
            return Value.newBuilder().setStringValue(node.asText()).build();
        }
        return Value.newBuilder().setStringValue(node.asText()).build();
    }
}
