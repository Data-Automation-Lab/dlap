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

package com.dlap.logingest.service.registry;

import com.dlap.logingest.model.LogEventEntity;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores registry-pipeline events in {@code log_events} and applies the republish rule
 * (ADR-0045 decision 4, ADR-0054 decision 5).
 *
 * <p>Per event, in one transaction: lock the event id ({@link LogEventRepository#lockEventId}),
 * check for a stored copy, insert when there is none, then publish unless the id was already
 * stored, except in
 * {@code REPLAY} mode. The publish runs inside the transaction, as on the legacy path, so a
 * synchronous send failure rolls the row back and a retry is not mistaken for a duplicate.
 *
 * <p>One exception to "a stored id is never written again" (Q-363): a {@code REPLAY} write of a
 * full event upgrades a stored {@code PARSE_STATUS_PARTIAL} row of the same {@code event_id}. The
 * pass-through event of a quarantined record and the full event the corrected parser makes of it
 * share one id, and replay republishes the full event, so the stored copy has to follow it or the
 * replay store would keep the pass-through for good. The upgrade keeps the row's original
 * {@code ingest_time} (the partition key and the retention clock). Any other duplicate is dropped
 * as before, including a partial event written over a full row and a full event written in
 * {@code STREAM} or {@code BACKFILL} mode, and a seed-once replay ({@code suppressRepublish}),
 * which publishes nothing and so must not change what was stored.
 *
 * <p>The row keeps {@code raw_event} only when the binding's {@code persist-raw} policy says so
 * (ADR-0054 decision 6). Text longer than its column is cut to the column width in the row only;
 * the published event is complete.
 */
@Component
public class LogEventStore {

    private static final Logger log = LoggerFactory.getLogger(LogEventStore.class);

    private final LogEventRepository repository;
    private final TransactionTemplate tx;

    /**
     * Creates the store.
     *
     * @param repository the replay store
     * @param txManager  the transaction manager
     */
    public LogEventStore(LogEventRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Stores an event once and publishes it per the republish rule.
     *
     * @param event             the event
     * @param wire              the event as its wire map, from {@code DlapLogEventCodec.toMap}
     * @param keepRaw           whether the row keeps {@code raw_event}
     * @param mode              the ingest mode of the record
     * @param suppressRepublish true to skip a stored id even in {@code REPLAY} mode (seed-once replay)
     * @param publisher         sends the event to {@code dlap.log.event.normalized}
     * @return what happened
     */
    public Disposition write(DlapLogEvent event, Map<String, Object> wire, boolean keepRaw, IngestMode mode,
                             boolean suppressRepublish, Consumer<DlapLogEvent> publisher) {
        Disposition d = tx.execute(status -> {
            repository.lockEventId(event.getEventId());
            boolean stored = repository.existsById(event.getEventId());
            if (!stored) {
                repository.save(toEntity(event, wire, keepRaw));
            } else if (mode == IngestMode.REPLAY && !suppressRepublish
                    && event.getParseStatus() != ParseStatus.PARSE_STATUS_PARTIAL) {
                upgradePartialRow(event, wire, keepRaw);
            }
            boolean publish = mode.shouldPublish(stored) && !(stored && suppressRepublish);
            if (publish) {
                publisher.accept(event);
            }
            if (!stored) {
                return Disposition.PUBLISHED;
            }
            return publish ? Disposition.REPUBLISHED : Disposition.DUPLICATE;
        });
        return d;
    }

    /**
     * Replaces a stored pass-through row with the full event (Q-363). Does nothing when the stored
     * row is not {@code PARSE_STATUS_PARTIAL}. Runs inside the write transaction, after the id lock.
     */
    private void upgradePartialRow(DlapLogEvent event, Map<String, Object> wire, boolean keepRaw) {
        repository.findById(event.getEventId())
                .filter(row -> ParseStatus.PARSE_STATUS_PARTIAL.name().equals(row.getParseStatus()))
                .ifPresent(row -> {
                    LogEventEntity full = toEntity(event, wire, keepRaw);
                    // The row keeps the ingest time it was first stored under: it is part of the
                    // primary key of the partitioned table and the clock retention counts from.
                    full.setIngestTime(row.getIngestTime());
                    if (full.getRawEvent() == null) {
                        full.setRawEvent(row.getRawEvent());
                    }
                    repository.save(full);
                    log.info("log_events row of event {} upgraded from {} to {} by a replay", event.getEventId(),
                            row.getParseStatus(), full.getParseStatus());
                });
    }

    /**
     * The row of an event.
     *
     * @param e       the event
     * @param wire    its wire map
     * @param keepRaw whether to keep {@code raw_event}
     * @return the entity
     */
    LogEventEntity toEntity(DlapLogEvent e, Map<String, Object> wire, boolean keepRaw) {
        List<String> cut = new ArrayList<>();
        TargetResource t = e.getTargetResource();
        Object raw = keepRaw && wire != null ? wire.get("rawEvent") : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> rawMap = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        LogEventEntity entity = LogEventEntity.builder()
                .eventId(e.getEventId())
                .eventTime(instant(e.getEventTime()))
                .ingestTime(instant(e.getIngestTime()))
                .sourceProvider(e.getSourceProvider().name())
                .sourceService(fit(e.getSourceService(), 128, "source_service", cut))
                .changeType(e.getChangeType().name())
                .operation(fitRequired(e.getOperation(), 256, "operation", cut))
                .resultStatus(e.getResultStatus().name())
                .principalId(fit(e.getPrincipal().getId(), 256, "principal_id", cut))
                .principalType(e.getPrincipal().getType().name())
                .targetResourceId(fit(t.getId(), 512, "target_resource_id", cut))
                .targetResourceKind(fit(t.getKind(), 128, "target_resource_kind", cut))
                .targetProject(fit(t.getProject(), 128, "target_project", cut))
                .targetRegion(fit(t.getRegion(), 64, "target_region", cut))
                .schemaVersion(e.getSchemaVersion().isEmpty() ? "v1" : e.getSchemaVersion())
                .rawEvent(rawMap)
                .bindingId(fit(e.getBindingId(), 128, "binding_id", cut))
                .sourceFormat(fit(e.getSourceFormat(), 160, "source_format", cut))
                .sourceSchemaVersion(fit(e.getSourceSchemaVersion(), 64, "source_schema_version", cut))
                .parserVersion(fit(e.getParserVersion(), 160, "parser_version", cut))
                .nativeEventId(blankToNull(e.getNativeEventId()))
                .observedTime(e.hasObservedTime() ? instant(e.getObservedTime()) : null)
                .eventCategory(e.getEventCategory().name())
                .origin(e.getOrigin().name())
                .parseStatus(e.getParseStatus().name())
                .parseWarnings(fit(e.getParseWarningsList().stream().map(ParseWarning::name)
                        .collect(Collectors.joining(",")), 512, "parse_warnings", cut))
                .targetCanonicalUri(blankToNull(t.getCanonicalUri()))
                .sharedEventId(fit(e.getSharedEventId(), 256, "shared_event_id", cut))
                .supersedesEventId(fit(e.getSupersedesEventId(), 64, "supersedes_event_id", cut))
                .build();
        if (!cut.isEmpty()) {
            log.warn("log_events row of event {} keeps a shortened copy of {} (the published event is complete)",
                    e.getEventId(), cut);
        }
        return entity;
    }

    private static Instant instant(Timestamp t) {
        return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    }

    private static String fitRequired(String value, int width, String column, List<String> cut) {
        String v = value == null ? "" : value;
        if (v.length() > width) {
            cut.add(column);
            return v.substring(0, width);
        }
        return v;
    }

    private static String fit(String value, int width, String column, List<String> cut) {
        String v = blankToNull(value);
        if (v != null && v.length() > width) {
            cut.add(column);
            return v.substring(0, width);
        }
        return v;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
