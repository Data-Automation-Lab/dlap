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

package com.dlap.logingest.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistent record of a normalized log event for replay + audit.
 *
 * <p>One row per published {@code DlapLogEvent}. Retention default is
 * 90 days (configurable via {@code dlap.logingest.retention-days});
 * older rows are pruned by
 * {@link com.dlap.logingest.retention.LogEventRetentionSweeper} on a daily cron.
 *
 * <p>The {@code raw_event} jsonb column stores the original cloud
 * payload verbatim per spec (docs/specs/log-event.md "raw_event"
 * paragraph).
 *
 * <p>Since Flyway V3 the table is partitioned by month of {@code ingest_time}
 * (ADR-0060): its primary key is {@code (event_id, ingest_time)}, and
 * {@code event_id} stays unique because writers lock the id first
 * ({@link com.dlap.logingest.repository.LogEventIdLock}). The entity keeps
 * {@code event_id} as its identifier, which is what lookups use. The columns
 * after {@code raw_event} are written by the registry pipeline only and stay
 * {@code null} on legacy rows.
 */
@Entity
@Table(
    name = "log_events",
    indexes = {
        @Index(name = "idx_log_events_event_time", columnList = "event_time"),
        @Index(name = "idx_log_events_source_provider", columnList = "source_provider"),
        @Index(name = "idx_log_events_change_type", columnList = "change_type"),
        @Index(name = "idx_log_events_target_resource_id", columnList = "target_resource_id")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogEventEntity {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "ingest_time", nullable = false)
    private Instant ingestTime;

    @Column(name = "source_provider", length = 32, nullable = false)
    private String sourceProvider;

    @Column(name = "source_service", length = 128)
    private String sourceService;

    @Column(name = "change_type", length = 32, nullable = false)
    private String changeType;

    @Column(name = "operation", length = 256, nullable = false)
    private String operation;

    @Column(name = "result_status", length = 32)
    private String resultStatus;

    @Column(name = "principal_id", length = 256)
    private String principalId;

    @Column(name = "principal_type", length = 32)
    private String principalType;

    @Column(name = "target_resource_id", length = 512)
    private String targetResourceId;

    @Column(name = "target_resource_kind", length = 128)
    private String targetResourceKind;

    @Column(name = "target_project", length = 128)
    private String targetProject;

    @Column(name = "target_region", length = 64)
    private String targetRegion;

    @Column(name = "schema_version", length = 8, nullable = false)
    private String schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_event", columnDefinition = "jsonb")
    private Map<String, Object> rawEvent;

    /** The binding the record came through ({@code binding_id}); registry pipeline only. */
    @Column(name = "binding_id", length = 128)
    private String bindingId;

    /** {@code <SOURCE_KIND>/<envelope>} ({@code source_format}); registry pipeline only. */
    @Column(name = "source_format", length = 160)
    private String sourceFormat;

    /** Version detected on the record ({@code source_schema_version}); registry pipeline only. */
    @Column(name = "source_schema_version", length = 64)
    private String sourceSchemaVersion;

    /** {@code <parser-id>@<semver>} ({@code parser_version}); registry pipeline only. */
    @Column(name = "parser_version", length = 160)
    private String parserVersion;

    /** The native key before hashing ({@code native_event_id}); registry pipeline only. */
    @Column(name = "native_event_id", columnDefinition = "text")
    private String nativeEventId;

    /** Provider receipt or delivery time ({@code observed_time}); registry pipeline only. */
    @Column(name = "observed_time")
    private Instant observedTime;

    /** {@code EventCategory} name ({@code event_category}); registry pipeline only. */
    @Column(name = "event_category", length = 48)
    private String eventCategory;

    /** {@code Origin} name ({@code origin}); registry pipeline only. */
    @Column(name = "origin", length = 32)
    private String origin;

    /** {@code ParseStatus} name ({@code parse_status}); registry pipeline only. */
    @Column(name = "parse_status", length = 32)
    private String parseStatus;

    /** Comma-separated {@code ParseWarning} names ({@code parse_warnings}); registry pipeline only. */
    @Column(name = "parse_warnings", length = 512)
    private String parseWarnings;

    /** {@code target_resource.canonical_uri}; registry pipeline only. */
    @Column(name = "target_canonical_uri", columnDefinition = "text")
    private String targetCanonicalUri;

    /** CloudTrail {@code sharedEventID} ({@code shared_event_id}); registry pipeline only. */
    @Column(name = "shared_event_id", length = 256)
    private String sharedEventId;

    /** The event id this event replaces ({@code supersedes_event_id}); registry pipeline only. */
    @Column(name = "supersedes_event_id", length = 64)
    private String supersedesEventId;

    /**
     * Removes U+0000 from every text column and escapes it in {@code raw_event} before the row is
     * written. PostgreSQL refuses a NUL character in a text column and in any jsonb string or key; the
     * insert would fail and stop a stream on the record at every poll (Q-661). Text columns drop the
     * character (the value only gets shorter, so it still fits its column); the evidence document keeps
     * it as its six-character escape text. Every writer of the table (registry path, legacy sources,
     * demo seeder) goes through this one callback.
     */
    @PrePersist
    @PreUpdate
    void scrubNul() {
        eventId = NulSafe.strip(eventId);
        sourceProvider = NulSafe.strip(sourceProvider);
        sourceService = NulSafe.strip(sourceService);
        changeType = NulSafe.strip(changeType);
        operation = NulSafe.strip(operation);
        resultStatus = NulSafe.strip(resultStatus);
        principalId = NulSafe.strip(principalId);
        principalType = NulSafe.strip(principalType);
        targetResourceId = NulSafe.strip(targetResourceId);
        targetResourceKind = NulSafe.strip(targetResourceKind);
        targetProject = NulSafe.strip(targetProject);
        targetRegion = NulSafe.strip(targetRegion);
        schemaVersion = NulSafe.strip(schemaVersion);
        bindingId = NulSafe.strip(bindingId);
        sourceFormat = NulSafe.strip(sourceFormat);
        sourceSchemaVersion = NulSafe.strip(sourceSchemaVersion);
        parserVersion = NulSafe.strip(parserVersion);
        nativeEventId = NulSafe.strip(nativeEventId);
        eventCategory = NulSafe.strip(eventCategory);
        origin = NulSafe.strip(origin);
        parseStatus = NulSafe.strip(parseStatus);
        parseWarnings = NulSafe.strip(parseWarnings);
        targetCanonicalUri = NulSafe.strip(targetCanonicalUri);
        sharedEventId = NulSafe.strip(sharedEventId);
        supersedesEventId = NulSafe.strip(supersedesEventId);
        rawEvent = NulSafe.escapeDeep(rawEvent);
    }
}
