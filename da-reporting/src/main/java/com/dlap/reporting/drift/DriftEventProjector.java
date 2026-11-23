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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.stereotype.Component;

import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventDecodeException;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.reporting.identity.IdentityProviderScope;
import com.dlap.reporting.identity.PrincipalIdentityResolver;
import com.dlap.reporting.identity.ResolvedPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Turns the two drift inputs into join records (enforcement-contract.md section 6.1).
 *
 * <ul>
 *   <li>A read is a {@code DlapLogEvent} on {@code dlap.discovery.asset.accessed}, read with the
 *       shared {@link DlapLogEventCodec} (every encoding and legacy shape). Only a recorded,
 *       successful data read joins; its time is {@code eventTime}, never the consumer's clock.</li>
 *   <li>A decision is the section 6 event on {@code dlap.access.decision}; its time is
 *       {@code timestamp}.</li>
 * </ul>
 *
 * <p>Both sides get canonical asset keys ({@link DriftAssetKeys}); the read's principal is
 * attributed to a DLAP subject through the identity map ({@link PrincipalIdentityResolver}). The
 * injected {@link Clock} is used for one check only: a business time more than
 * {@value #MAX_FUTURE_SKEW_PROPERTY} seconds ahead of it is rejected, because a future-stamped
 * decision would authorize reads that have not happened. Every rejection is a
 * {@link DriftSkipReason}.
 */
@Component
public class DriftEventProjector {

    /** Tolerated clock skew, in seconds, for business times ahead of the service clock. Negative disables the check. */
    public static final String MAX_FUTURE_SKEW_PROPERTY = "dlap.drift.max-future-skew-seconds";

    /** Default of {@value #MAX_FUTURE_SKEW_PROPERTY}. */
    public static final long DEFAULT_MAX_FUTURE_SKEW_SECONDS = 300L;

    /** Counter of join keys built, tagged {@code stream=read|decision} and {@code form=canonical|native}. */
    public static final String ASSET_KEYS_COUNTER = "dlap.drift.asset.keys";

    /** Width of the buffered event and decision id columns. */
    static final int MAX_EVENT_ID_LENGTH = 200;

    /** Width of the buffered asset and subject columns. */
    static final int MAX_KEY_LENGTH = 512;

    /**
     * The outcome of projecting one event: a record, or the reason it was skipped.
     *
     * @param record the join record, or {@code null} when skipped
     * @param skip   the skip reason, or {@code null} when accepted
     * @param ref    the event's own id for logs ({@code eventId} or {@code decisionId}); may be {@code null}
     * @param <T>    {@link ReadRecord} or {@link DecisionRecord}
     */
    public record Projection<T>(T record, DriftSkipReason skip, String ref) {

        static <T> Projection<T> accepted(T record, String ref) {
            return new Projection<>(record, null, ref);
        }

        static <T> Projection<T> skipped(DriftSkipReason reason, String ref) {
            return new Projection<>(null, reason, ref);
        }

        /**
         * Whether the event joins.
         *
         * @return true when {@link #record()} is set
         */
        public boolean isAccepted() {
            return record != null;
        }
    }

    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();
    private final ObjectMapper json = new ObjectMapper();
    private final PrincipalIdentityResolver identities;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final long maxFutureSkewSeconds;

    /**
     * Creates the projector.
     *
     * @param identities           the principal identity map
     * @param meterRegistry        where key forms are counted
     * @param clocks               the service clock; the system UTC clock when none is defined
     * @param maxFutureSkewSeconds see {@link #MAX_FUTURE_SKEW_PROPERTY}
     */
    public DriftEventProjector(PrincipalIdentityResolver identities,
                               MeterRegistry meterRegistry,
                               ObjectProvider<Clock> clocks,
                               @Value("${" + MAX_FUTURE_SKEW_PROPERTY + ":" + DEFAULT_MAX_FUTURE_SKEW_SECONDS + "}")
                               long maxFutureSkewSeconds) {
        this.identities = identities;
        this.meterRegistry = meterRegistry;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
        this.maxFutureSkewSeconds = maxFutureSkewSeconds;
    }

    /**
     * Projects a read event.
     *
     * @param payload the record value: a JSON object as a {@code Map}, JSON text, or a decoded event
     * @return the read record, or the skip reason
     */
    public Projection<ReadRecord> projectRead(Object payload) {
        if (payload == null || payload instanceof KafkaNull) {
            return Projection.skipped(DriftSkipReason.NULL_PAYLOAD, null);
        }
        DlapLogEvent event;
        try {
            event = codec.decode(payload);
        } catch (LogEventDecodeException | IllegalArgumentException e) {
            return Projection.skipped(DriftSkipReason.UNDECODABLE, null);
        }
        String eventId = event.getEventId().strip();
        if (eventId.isEmpty()) {
            return Projection.skipped(DriftSkipReason.MISSING_EVENT_ID, null);
        }
        if (eventId.length() > MAX_EVENT_ID_LENGTH) {
            return Projection.skipped(DriftSkipReason.FIELD_TOO_LONG, null);
        }
        Optional<DriftSkipReason> notARead = notARead(event);
        if (notARead.isPresent()) {
            return Projection.skipped(notARead.get(), eventId);
        }
        Optional<Instant> readAt = LogEventSemantics.businessTime(event);
        if (readAt.isEmpty()) {
            return Projection.skipped(DriftSkipReason.MISSING_EVENT_TIME, eventId);
        }
        if (inFuture(readAt.get())) {
            return Projection.skipped(DriftSkipReason.FUTURE_EVENT_TIME, eventId);
        }
        Optional<DriftAssetKeys.AssetKey> asset = DriftAssetKeys.forRead(
                event.getTargetResource().getCanonicalUri(), event.getTargetResource().getId(),
                assetProvider(event.getSourceProvider()));
        if (asset.isEmpty()) {
            return Projection.skipped(DriftSkipReason.MISSING_ASSET, eventId);
        }
        if (asset.get().key().length() > MAX_KEY_LENGTH) {
            return Projection.skipped(DriftSkipReason.FIELD_TOO_LONG, eventId);
        }
        Optional<ResolvedPrincipal> principal =
                identities.resolve(scope(event.getSourceProvider()), event.getPrincipal().getId());
        if (principal.isEmpty()) {
            return Projection.skipped(DriftSkipReason.MISSING_PRINCIPAL, eventId);
        }
        if (principal.get().subjectId().length() > MAX_KEY_LENGTH) {
            return Projection.skipped(DriftSkipReason.FIELD_TOO_LONG, eventId);
        }
        countKey(DriftEventSkips.Stream.READ, asset.get());
        return Projection.accepted(
                new ReadRecord(eventId, principal.get().subjectId(), asset.get().key(), readAt.get()), eventId);
    }

    /**
     * Projects a decision event.
     *
     * @param payload the record value: a JSON object as a {@code Map}, or JSON text
     * @return the decision record, or the skip reason
     */
    public Projection<DecisionRecord> projectDecision(Object payload) {
        if (payload == null || payload instanceof KafkaNull) {
            return Projection.skipped(DriftSkipReason.NULL_PAYLOAD, null);
        }
        Optional<Map<?, ?>> object = jsonObject(payload);
        if (object.isEmpty()) {
            return Projection.skipped(DriftSkipReason.UNDECODABLE, null);
        }
        Map<?, ?> event = object.get();
        String decisionId = text(event.get("decisionId"));
        if (decisionId == null) {
            return Projection.skipped(DriftSkipReason.MISSING_DECISION_ID, null);
        }
        if (decisionId.length() > MAX_EVENT_ID_LENGTH) {
            return Projection.skipped(DriftSkipReason.FIELD_TOO_LONG, null);
        }
        String subjectId = text(event.get("subjectId"));
        if (subjectId == null) {
            return Projection.skipped(DriftSkipReason.MISSING_SUBJECT, decisionId);
        }
        String label = text(event.get("decision"));
        boolean allow = "ALLOW".equalsIgnoreCase(label);
        if (!allow && !"DENY".equalsIgnoreCase(label)) {
            return Projection.skipped(DriftSkipReason.UNKNOWN_DECISION, decisionId);
        }
        String timestamp = text(event.get("timestamp"));
        if (timestamp == null) {
            return Projection.skipped(DriftSkipReason.MISSING_EVENT_TIME, decisionId);
        }
        Optional<Instant> decidedAt = parseInstant(timestamp);
        if (decidedAt.isEmpty()) {
            return Projection.skipped(DriftSkipReason.UNPARSABLE_EVENT_TIME, decisionId);
        }
        if (inFuture(decidedAt.get())) {
            return Projection.skipped(DriftSkipReason.FUTURE_EVENT_TIME, decisionId);
        }
        Optional<DriftAssetKeys.AssetKey> asset =
                DriftAssetKeys.forDecision(text(event.get("assetUri")), text(event.get("assetId")));
        if (asset.isEmpty()) {
            return Projection.skipped(DriftSkipReason.MISSING_ASSET, decisionId);
        }
        if (asset.get().key().length() > MAX_KEY_LENGTH || subjectId.length() > MAX_KEY_LENGTH) {
            return Projection.skipped(DriftSkipReason.FIELD_TOO_LONG, decisionId);
        }
        countKey(DriftEventSkips.Stream.DECISION, asset.get());
        return Projection.accepted(
                new DecisionRecord(decisionId, subjectId, asset.get().key(), allow, decidedAt.get()), decisionId);
    }

    /** Why a decoded event is not a recorded successful data read, if it is not. */
    private static Optional<DriftSkipReason> notARead(DlapLogEvent event) {
        if (LogEventSemantics.effectiveCategory(event) != EventCategory.EVENT_CATEGORY_DATA_READ) {
            return Optional.of(DriftSkipReason.NOT_A_DATA_READ);
        }
        ResultStatus status = event.getResultStatus();
        if (status == ResultStatus.FAILURE || status == ResultStatus.PENDING) {
            return Optional.of(DriftSkipReason.NOT_SUCCESSFUL);
        }
        if (event.getParseStatus() == ParseStatus.PARSE_STATUS_PARTIAL) {
            return Optional.of(DriftSkipReason.PARTIAL_PARSE);
        }
        return Optional.empty();
    }

    private boolean inFuture(Instant businessTime) {
        return maxFutureSkewSeconds >= 0
                && businessTime.isAfter(clock.instant().plusSeconds(maxFutureSkewSeconds));
    }

    private void countKey(DriftEventSkips.Stream stream, DriftAssetKeys.AssetKey key) {
        meterRegistry.counter(ASSET_KEYS_COUNTER,
                "stream", stream == DriftEventSkips.Stream.READ ? "read" : "decision",
                "form", key.canonical() ? "canonical" : "native").increment();
    }

    /** The decision as a JSON object: a Map as is, JSON text parsed (one string wrap unwrapped). */
    private Optional<Map<?, ?>> jsonObject(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            return Optional.of(map);
        }
        String text;
        if (payload instanceof CharSequence chars) {
            text = chars.toString();
        } else if (payload instanceof byte[] bytes) {
            text = new String(bytes, StandardCharsets.UTF_8);
        } else {
            return Optional.empty();
        }
        try {
            JsonNode node = json.readTree(text);
            if (node != null && node.isTextual()) {
                node = json.readTree(node.asText());
            }
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            return Optional.of(json.convertValue(node, Map.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> parseInstant(String iso) {
        try {
            return Optional.of(Instant.parse(iso));
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(OffsetDateTime.parse(iso).toInstant());
            } catch (DateTimeParseException e2) {
                return Optional.empty();
            }
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }

    private static AssetProvider assetProvider(SourceProvider provider) {
        return switch (provider) {
            case AWS -> AssetProvider.AWS;
            case GCP -> AssetProvider.GCP;
            case AZURE -> AssetProvider.AZURE;
            case OCI -> AssetProvider.OCI;
            case UNITY -> AssetProvider.UNITY;
            case DATABRICKS -> AssetProvider.DATABRICKS;
            case SNOWFLAKE -> AssetProvider.SNOWFLAKE;
            default -> null;
        };
    }

    private static IdentityProviderScope scope(SourceProvider provider) {
        AssetProvider p = assetProvider(provider);
        return p == null ? null : IdentityProviderScope.valueOf(p.name());
    }
}
