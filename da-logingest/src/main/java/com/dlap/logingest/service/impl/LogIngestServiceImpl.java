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

package com.dlap.logingest.service.impl;

import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.model.LogEventEntity;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.logingest.service.ILogIngestService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of the log-ingest pipeline.
 *
 * <p>Three steps per event:
 *
 * <ol>
 *   <li>Compute a stable {@code event_id} per spec
 *       (docs/specs/log-event.md): SHA-256 of the provider's native
 *       event id concatenated with {@code source_provider}. Fallback
 *       to SHA-256 over the operation + timestamp + target id when
 *       the provider didn't supply a native id.</li>
 *   <li>Persist a {@link LogEventEntity} row for replay/audit. Idempotent
 *       on {@code event_id} as the primary key.</li>
 *   <li>Publish the canonical normalized event to Kafka via
 *       {@link LogEventPublisher}.</li>
 * </ol>
 *
 * <p>An {@code event_id} that is already stored is a duplicate: it is not
 * persisted again and it is not published again. Pull sources re-read an
 * overlap window on every poll to catch late entries, and this is what
 * keeps that overlap from delivering the same read to consumers twice. Each
 * skipped duplicate increments {@value #METRIC_DEDUP_HITS}.
 *
 * <p>Since Flyway V3 partitions {@code log_events} by {@code ingest_time}, the
 * primary key no longer makes {@code event_id} unique by itself, so the check
 * and insert run under the event id's advisory lock
 * ({@link LogEventRepository#lockEventId}):
 * two concurrent ingests of one event store and publish it once (ADR-0060).
 * This is the legacy pipeline ({@code dlap.logingest.pipeline=legacy}); the
 * registry pipeline goes through
 * {@code com.dlap.logingest.service.registry.RegistryIngestService}.
 */
@Service
@Slf4j
public class LogIngestServiceImpl implements ILogIngestService {

    /** Counter of events dropped because their {@code event_id} was already stored. */
    public static final String METRIC_DEDUP_HITS = "dlap.logingest.dedup.hits";

    private static final String SCHEMA_VERSION = "v1";

    private final LogEventRepository repository;
    private final LogEventPublisher publisher;
    private final Counter dedupHits;

    /**
     * Creates the service.
     *
     * @param repository persistence for the replay/audit rows.
     * @param publisher Kafka publisher for the normalized event.
     * @param meterRegistry registry for the duplicate counter.
     */
    public LogIngestServiceImpl(LogEventRepository repository,
                                LogEventPublisher publisher,
                                MeterRegistry meterRegistry) {
        this.repository = repository;
        this.publisher = publisher;
        this.dedupHits = meterRegistry.counter(METRIC_DEDUP_HITS);
    }

    @Override
    @Transactional
    public String ingest(IngestRequestDTO request) {
        Instant ingestTime = Instant.now();
        String eventId = computeEventId(request);

        // An event_id that is already stored was persisted and published by an
        // earlier call. Re-pulled overlap windows land here; do not publish again.
        // The lock makes a concurrent ingest of the same id wait and see this row.
        repository.lockEventId(eventId);
        if (repository.existsById(eventId)) {
            dedupHits.increment();
            log.debug("Duplicate skipped: event_id {} already persisted and published", eventId);
            return eventId;
        }

        LogEventEntity entity = LogEventEntity.builder()
                .eventId(eventId)
                .eventTime(request.getEventTime())
                .ingestTime(ingestTime)
                .sourceProvider(request.getSourceProvider())
                .sourceService(request.getSourceService())
                .changeType(request.getChangeType())
                .operation(request.getOperation())
                .resultStatus(request.getResultStatus())
                .principalId(request.getPrincipalId())
                .principalType(request.getPrincipalType())
                .targetResourceId(request.getTargetResourceId())
                .targetResourceKind(request.getTargetResourceKind())
                .targetProject(request.getTargetProject())
                .targetRegion(request.getTargetRegion())
                .schemaVersion(SCHEMA_VERSION)
                .rawEvent(request.getRawEvent())
                .build();
        repository.save(entity);

        // Publish to dlap.log.event.normalized.
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("eventId", eventId);
        normalized.put("eventTime", request.getEventTime() != null ? request.getEventTime().toString() : null);
        normalized.put("ingestTime", ingestTime.toString());
        normalized.put("sourceProvider", request.getSourceProvider());
        normalized.put("sourceService", request.getSourceService());
        normalized.put("changeType", request.getChangeType());
        normalized.put("operation", request.getOperation());
        normalized.put("resultStatus", request.getResultStatus());
        normalized.put("principal", Map.of(
                "id", nullToEmpty(request.getPrincipalId()),
                "type", nullToEmpty(request.getPrincipalType()),
                "displayName", nullToEmpty(request.getPrincipalDisplayName()),
                "accountId", nullToEmpty(request.getPrincipalAccountId())
        ));
        normalized.put("targetResource", Map.of(
                "kind", nullToEmpty(request.getTargetResourceKind()),
                "id", nullToEmpty(request.getTargetResourceId()),
                "name", nullToEmpty(request.getTargetResourceName()),
                "project", nullToEmpty(request.getTargetProject()),
                "region", nullToEmpty(request.getTargetRegion())
        ));
        normalized.put("requestMetadata", Map.of(
                "callerIp", nullToEmpty(request.getCallerIp()),
                "userAgent", nullToEmpty(request.getUserAgent()),
                "requestId", nullToEmpty(request.getRequestId()),
                "traceId", nullToEmpty(request.getTraceId())
        ));
        normalized.put("rawEvent", request.getRawEvent());
        normalized.put("schemaVersion", SCHEMA_VERSION);

        String partitionKey = request.getTargetResourceId() != null
                ? request.getTargetResourceId() : eventId;
        publisher.publish(partitionKey, normalized);

        log.info("Ingested log event id={} provider={} operation={} target={}",
                eventId, request.getSourceProvider(), request.getOperation(),
                request.getTargetResourceId());
        return eventId;
    }

    /**
     * SHA-256 of source-provider + native-event-fingerprint. Idempotent
     * across re-processing per spec rule.
     */
    private static String computeEventId(IngestRequestDTO req) {
        String requestKey = (req.getRequestId() != null && !req.getRequestId().isBlank())
                ? req.getRequestId()
                : (nullToEmpty(req.getOperation())
                    + "|" + (req.getEventTime() != null ? req.getEventTime().toString() : "")
                    + "|" + nullToEmpty(req.getTargetResourceId())
                    + "|" + nullToEmpty(req.getPrincipalId()));
        String input = nullToEmpty(req.getSourceProvider()) + "|" + requestKey;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; this branch is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
