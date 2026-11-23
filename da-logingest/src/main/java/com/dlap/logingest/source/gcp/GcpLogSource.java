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

package com.dlap.logingest.source.gcp;

import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.repository.SourceWatermarkRepository;
import com.dlap.logingest.service.ILogIngestService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.paging.Page;
import com.google.cloud.audit.AuditLog;
import com.google.cloud.audit.AuthenticationInfo;
import com.google.cloud.audit.AuthorizationInfo;
import com.google.cloud.audit.ServiceAccountDelegationInfo;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Operation;
import com.google.cloud.logging.Payload;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GCP Cloud Audit Log source for da-logingest (ADR-0021 + ADR-0023).
 *
 * <p>Pull-based, Kafka-only. Polls Google Cloud Logging via the
 * {@code entries.list} API on a configurable schedule, filtered to the
 * audit-log family ({@code logName:"cloudaudit.googleapis.com"}),
 * normalizes each entry to an {@link IngestRequestDTO}, and hands it
 * to {@link ILogIngestService#ingest} (which then publishes the
 * canonical {@code DlapLogEvent} to {@code dlap.log.event.normalized}
 * - a DLAP-owned Kafka topic, not a cloud-native pub-sub).
 *
 * <h2>Payload handling</h2>
 *
 * <p>The Cloud Logging client returns an audit entry as
 * {@code Payload.ProtoPayload}, whose data is a {@link Any} wrapping a
 * {@code google.cloud.audit.AuditLog}. The source unpacks it with
 * {@code Any.unpack(AuditLog.class)}; the typed message drives the field
 * mapping (method, resource, principal, status, authorization). The
 * {@code rawEvent} follows the LOG-01 convention: the {@code LogEntry}
 * as JSON, with the {@code AuditLog} printed through {@link JsonFormat}
 * under {@code protoPayload} and the deprecated {@code serviceData} (the
 * BigQuery v1 {@code AuditData} message) kept as
 * {@code {typeUrl, base64}} because no Java class for it ships with the
 * client. BigQuery v2 {@code BigQueryAuditMetadata} arrives as the
 * {@code metadata} Struct and prints as plain JSON.
 *
 * <h2>Identity, time and result</h2>
 *
 * <ul>
 *   <li>Native event key {@code logName|timestamp|insertId} goes into
 *       {@code requestId}; the ingest service hashes it into the
 *       {@code event_id}.</li>
 *   <li>Event time is the entry {@code timestamp}, then
 *       {@code receiveTimestamp}. An entry with neither is skipped and
 *       counted; the clock is never used as an event time.</li>
 *   <li>The result is {@code FAILURE} when {@code status.code != 0} or
 *       any {@code authorizationInfo[].granted} is false.</li>
 * </ul>
 *
 * <h2>Resume semantics</h2>
 *
 * <p>The newest ingested event time is persisted in
 * {@code dlap_logingest.source_watermarks} keyed by
 * {@code (provider, accountId)}. Every poll re-reads from
 * {@code watermark - overlap} (default 10 minutes), so an entry that
 * Cloud Logging delivers late is still picked up. The duplicates this
 * produces are dropped by the ingest service, which does not republish
 * an {@code event_id} it has already stored. The watermark only moves
 * to the newest entry that was ingested successfully; entries are read
 * in ascending timestamp order and a failed ingest stops the poll, so
 * the watermark never passes a failed entry. On the first run the source
 * starts from {@code now - initialLookbackMinutes}.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code dlap.logingest.gcp.enabled=true}</li>
 *   <li>{@code dlap.logingest.gcp.projects} - comma-separated list of
 *       GCP project ids to poll.</li>
 *   <li>{@code dlap.logingest.gcp.poll-cron} - Quartz cron; default
 *       {@code 0/60 * * * * ?} (every 60s).</li>
 *   <li>{@code dlap.logingest.gcp.page-size} - number of entries per
 *       page; default 500.</li>
 *   <li>{@code dlap.logingest.gcp.initial-lookback-minutes} - first-run
 *       window; default 60.</li>
 *   <li>{@code dlap.logingest.gcp.overlap-minutes} - re-read window
 *       before the watermark; default 10.</li>
 *   <li>{@code dlap.logingest.gcp.filter} - override the default
 *       audit-log filter (advanced).</li>
 * </ul>
 *
 * <p>Credentials resolve via Google Application Default Credentials
 * (set {@code GOOGLE_APPLICATION_CREDENTIALS} or run on a GCE VM /
 * GKE workload identity).
 */
@Component
@Profile({"gcp", "prod"})
@ConditionalOnProperty(name = "dlap.logingest.gcp.enabled", havingValue = "true")
@Slf4j
public class GcpLogSource {

    private static final String PROVIDER = "GCP";

    /**
     * Name of the ShedLock row that keeps this poll a cluster-wide singleton (Q-550, plan rule R7).
     * One lock for the whole source: with two replicas only one reads Cloud Logging per tick.
     */
    public static final String LOCK_NAME = "logingest-legacy-gcp-poll";

    /**
     * Longest a poll may hold the lock: well over twice a normal run, and the wait a crashed holder
     * can cost the others.
     */
    public static final String LOCK_AT_MOST_FOR = "PT30M";

    /** Shortest hold, so a replica whose clock fires a moment later does not re-poll the same tick. */
    public static final String LOCK_AT_LEAST_FOR = "PT15S";
    private static final String DEFAULT_FILTER =
            "logName:\"cloudaudit.googleapis.com\"";

    /** Type URL of the audit payload; also written as {@code protoPayload.@type} in the raw event. */
    static final String AUDIT_LOG_TYPE_URL = "type.googleapis.com/google.cloud.audit.AuditLog";

    /** Counter of entries the source could not map, tagged with {@code reason}. */
    static final String METRIC_SKIPPED = "dlap.logingest.gcp.entries.skipped";

    /** Counter of entries whose event time came from {@code receiveTimestamp}. */
    static final String METRIC_TIME_FALLBACK = "dlap.logingest.gcp.entries.time_fallback";

    static final String REASON_NOT_AUDIT = "not_audit_log";
    static final String REASON_UNPACK_FAILED = "unpack_failed";
    static final String REASON_INCOMPLETE = "missing_method_or_resource";
    static final String REASON_NO_TIMESTAMP = "no_timestamp";

    private static final String PRINCIPAL_GOOGLE_INTERNAL = "google-internal";
    private static final String SERVICE_ACCOUNT_DOMAIN = "gserviceaccount.com";
    private static final String SERVICE_ACCOUNT_SUBJECT_PREFIX = "serviceaccount:";

    private static final JsonFormat.Printer AUDIT_PRINTER =
            JsonFormat.printer().omittingInsignificantWhitespace();
    private static final ObjectMapper RAW_MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() { };

    /**
     * Seam over the Cloud Logging client so the polling logic can be
     * tested without credentials or a network.
     */
    @FunctionalInterface
    interface EntryFetcher {
        /**
         * Streams the entries matching {@code filter}, oldest first, to
         * {@code sink} until the sink returns {@code false} or the
         * entries run out.
         *
         * @param projectId GCP project to read.
         * @param filter Cloud Logging filter.
         * @param pageSize entries per API page.
         * @param sink receives each entry; return {@code false} to stop.
         * @throws Exception when the client call fails.
         */
        void fetch(String projectId, String filter, int pageSize, Predicate<LogEntry> sink)
                throws Exception;
    }

    private final ILogIngestService logIngestService;
    private final SourceWatermarkRepository watermarkRepo;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final EntryFetcher fetcher;

    @Value("${dlap.logingest.gcp.projects:}")
    private String projectsCsv;

    @Value("${dlap.logingest.gcp.page-size:500}")
    private int pageSize = 500;

    @Value("${dlap.logingest.gcp.initial-lookback-minutes:60}")
    private long initialLookbackMinutes = 60;

    @Value("${dlap.logingest.gcp.overlap-minutes:10}")
    private long overlapMinutes = 10;

    @Value("${dlap.logingest.gcp.filter:" + DEFAULT_FILTER + "}")
    private String baseFilter = DEFAULT_FILTER;

    /**
     * Spring constructor: reads the real Cloud Logging API on the system clock.
     *
     * @param logIngestService ingest pipeline entry point.
     * @param watermarkRepo persistence for the per-project watermark.
     * @param meterRegistry registry for the skip and fallback counters.
     */
    @Autowired
    public GcpLogSource(ILogIngestService logIngestService,
                        SourceWatermarkRepository watermarkRepo,
                        MeterRegistry meterRegistry) {
        this.logIngestService = logIngestService;
        this.watermarkRepo = watermarkRepo;
        this.meterRegistry = meterRegistry;
        this.clock = Clock.systemUTC();
        this.fetcher = this::fetchFromCloudLogging;
    }

    /**
     * Test constructor: injects the clock and the entry source.
     */
    GcpLogSource(ILogIngestService logIngestService,
                 SourceWatermarkRepository watermarkRepo,
                 MeterRegistry meterRegistry,
                 Clock clock,
                 EntryFetcher fetcher) {
        this.logIngestService = logIngestService;
        this.watermarkRepo = watermarkRepo;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.fetcher = fetcher;
    }

    /**
     * Polled once per cron tick, a cluster-wide singleton under ShedLock (Q-550). Iterates over
     * configured projects; each iteration is independent - a failure on one project doesn't
     * halt the others.
     */
    @Scheduled(cron = "${dlap.logingest.gcp.poll-cron:0/60 * * * * ?}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR, lockAtLeastFor = LOCK_AT_LEAST_FOR)
    public void poll() {
        List<String> projects = parseProjects();
        if (projects.isEmpty()) {
            log.debug("GcpLogSource enabled but no projects configured; skipping poll");
            return;
        }

        for (String projectId : projects) {
            try {
                int polled = pollProject(projectId);
                if (polled > 0) {
                    log.info("GCP audit-log poll: project={} handed-to-ingest={} (overlap duplicates "
                            + "are dropped by the ingest service)", projectId, polled);
                }
            } catch (Exception e) {
                log.warn("GCP audit-log poll failed for project {}: {}", projectId, e.getMessage());
            }
        }
    }

    /**
     * Pulls entries for one project from {@code watermark - overlap}
     * (or from the initial lookback on the first run) and hands each to
     * the ingest service. Advances the watermark to the newest event
     * time that was ingested successfully; never past a failed entry.
     *
     * @param projectId GCP project id.
     * @return number of entries handed to the ingest service in this call.
     */
    int pollProject(String projectId) {
        Optional<Instant> stored = readWatermark(projectId);
        Instant from = stored
                .map(w -> w.minus(Duration.ofMinutes(overlapMinutes)))
                .orElseGet(() -> clock.instant().minus(Duration.ofMinutes(initialLookbackMinutes)));
        String filter = baseFilter + " AND timestamp >= \"" + from + "\"";

        PollState state = new PollState();
        try {
            fetcher.fetch(projectId, filter, pageSize, entry -> {
                IngestRequestDTO dto = mapToIngestRequest(entry, projectId);
                if (dto == null) {
                    return true;
                }
                try {
                    logIngestService.ingest(dto);
                } catch (Exception e) {
                    log.warn("Ingest failed for GCP entry insertId={}: {}",
                            entry.getInsertId(), e.getMessage());
                    // Entries arrive oldest first, so everything before this one is
                    // ingested. Stop here; the overlap window re-reads this entry.
                    return false;
                }
                state.ingested++;
                if (state.newest == null || dto.getEventTime().isAfter(state.newest)) {
                    state.newest = dto.getEventTime();
                }
                return true;
            });
        } catch (Exception e) {
            log.warn("GCP Cloud Logging client error for project {}: {}", projectId, e.getMessage());
        }

        // Entries before a failure or a client error were ingested, so the
        // watermark can move to the newest of them.
        if (state.newest != null && (stored.isEmpty() || state.newest.isAfter(stored.get()))) {
            advanceWatermark(projectId, state.newest);
        }
        return state.ingested;
    }

    /**
     * Map a single Cloud Logging {@link LogEntry} to an
     * {@link IngestRequestDTO}. The {@code protoPayload} is unpacked to a
     * {@link AuditLog}; entries that are not audit logs, cannot be
     * unpacked, lack a method or resource name, or carry no usable
     * timestamp are skipped and counted under {@value #METRIC_SKIPPED}.
     *
     * @param entry the Cloud Logging entry.
     * @param projectId the polled project, used when the entry has no project label.
     * @return the ingest request, or {@code null} when the entry is skipped.
     */
    IngestRequestDTO mapToIngestRequest(LogEntry entry, String projectId) {
        Payload<?> payload = entry.getPayload();
        if (!(payload instanceof Payload.ProtoPayload protoPayload)) {
            countSkipped(REASON_NOT_AUDIT);
            return null;
        }
        Any any = protoPayload.getData();
        if (any == null || !any.is(AuditLog.class)) {
            countSkipped(REASON_NOT_AUDIT);
            return null;
        }
        AuditLog audit;
        try {
            audit = any.unpack(AuditLog.class);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Could not unpack AuditLog for GCP entry insertId={}: {}",
                    entry.getInsertId(), e.getMessage());
            countSkipped(REASON_UNPACK_FAILED);
            return null;
        }
        String methodName = audit.getMethodName();
        String resourceName = audit.getResourceName();
        if (methodName.isBlank() || resourceName.isBlank()) {
            countSkipped(REASON_INCOMPLETE);
            return null;
        }
        Instant eventTime = resolveEventTime(entry);
        if (eventTime == null) {
            log.warn("GCP entry insertId={} has no timestamp or receiveTimestamp; skipped",
                    entry.getInsertId());
            countSkipped(REASON_NO_TIMESTAMP);
            return null;
        }

        Map<String, String> resourceLabels = entry.getResource() != null
                && entry.getResource().getLabels() != null
                ? entry.getResource().getLabels() : Map.of();
        String entryProject = resourceLabels.getOrDefault("project_id", projectId);
        String principal = resolvePrincipal(audit.getAuthenticationInfo());

        return IngestRequestDTO.builder()
                .sourceProvider(PROVIDER)
                .sourceService(blankToNull(audit.getServiceName()))
                .operation(methodName)
                .changeType(deriveChangeType(methodName))
                .resultStatus(deriveResultStatus(audit))
                .eventTime(eventTime)
                .principalId(principal)
                .principalType(derivePrincipalType(principal))
                .principalDisplayName(principal)
                .principalAccountId(entryProject)
                .targetResourceKind(entry.getResource() != null ? entry.getResource().getType() : null)
                .targetResourceId(resourceName)
                .targetResourceName(resourceName)
                .targetProject(entryProject)
                .targetRegion(resourceLabels.get("location"))
                .callerIp(blankToNull(audit.getRequestMetadata().getCallerIp()))
                .userAgent(blankToNull(audit.getRequestMetadata().getCallerSuppliedUserAgent()))
                .requestId(nativeEventId(entry, eventTime))
                .traceId(blankToNull(entry.getTrace()))
                .rawEvent(buildRawEvent(entry, audit, any, eventTime))
                .build();
    }

    /**
     * Event time is the entry {@code timestamp}, then
     * {@code receiveTimestamp}. Returns {@code null} when neither is set;
     * the clock is never an event time.
     */
    private Instant resolveEventTime(LogEntry entry) {
        Instant timestamp = entry.getInstantTimestamp();
        if (timestamp != null) {
            return timestamp;
        }
        Instant received = entry.getInstantReceiveTimestamp();
        if (received != null) {
            meterRegistry.counter(METRIC_TIME_FALLBACK).increment();
            log.debug("GCP entry insertId={} has no timestamp; using receiveTimestamp", entry.getInsertId());
            return received;
        }
        return null;
    }

    /**
     * Native event key {@code logName|timestamp|insertId}. Returns
     * {@code null} when the entry has no {@code insertId}, so the ingest
     * service falls back to its operation, time, target and principal hash
     * instead of keying every id-less entry of a log to the same value.
     */
    static String nativeEventId(LogEntry entry, Instant eventTime) {
        String insertId = entry.getInsertId();
        if (insertId == null || insertId.isBlank()) {
            return null;
        }
        String logName = entry.getLogName() == null ? "" : entry.getLogName();
        return logName + "|" + eventTime + "|" + insertId;
    }

    /**
     * Builds the {@code rawEvent}: the {@code LogEntry} as JSON, with the
     * {@code AuditLog} printed through {@link JsonFormat} under
     * {@code protoPayload}. {@code serviceData} is kept as
     * {@code {typeUrl, base64}}. If JsonFormat cannot print the message
     * (an unresolvable nested {@link Any}), the exact payload bytes are kept
     * as {@code {typeUrl, base64}} so no evidence is lost.
     */
    private Map<String, Object> buildRawEvent(LogEntry entry, AuditLog audit, Any packed, Instant eventTime) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("insertId", entry.getInsertId());
        raw.put("logName", entry.getLogName());
        raw.put("severity", entry.getSeverity() != null ? entry.getSeverity().toString() : null);
        raw.put("timestamp", entry.getInstantTimestamp() != null
                ? entry.getInstantTimestamp().toString() : null);
        if (entry.getInstantReceiveTimestamp() != null) {
            raw.put("receiveTimestamp", entry.getInstantReceiveTimestamp().toString());
        }
        if (entry.getResource() != null) {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", entry.getResource().getType());
            resource.put("labels", entry.getResource().getLabels());
            raw.put("resource", resource);
        }
        if (entry.getLabels() != null && !entry.getLabels().isEmpty()) {
            raw.put("labels", entry.getLabels());
        }
        Operation operation = entry.getOperation();
        if (operation != null) {
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("id", operation.getId());
            op.put("producer", operation.getProducer());
            op.put("first", operation.first());
            op.put("last", operation.last());
            raw.put("operation", op);
        }
        if (entry.getTrace() != null) {
            raw.put("trace", entry.getTrace());
        }
        raw.put("protoPayload", printAuditLog(audit, packed, eventTime));
        return raw;
    }

    @SuppressWarnings("deprecation") // serviceData is deprecated in AuditLog but BigQuery v1 still fills it
    private Map<String, Object> printAuditLog(AuditLog audit, Any packed, Instant eventTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("@type", AUDIT_LOG_TYPE_URL);
        try {
            String json = AUDIT_PRINTER.print(audit.toBuilder().clearServiceData());
            payload.putAll(RAW_MAPPER.readValue(json, MAP_TYPE));
            if (audit.hasServiceData()) {
                payload.put("serviceData", typeUrlAndBase64(audit.getServiceData()));
            }
        } catch (InvalidProtocolBufferException | JsonProcessingException e) {
            log.warn("Could not print AuditLog as JSON ({}); keeping the packed bytes. method={} time={}",
                    e.getClass().getSimpleName(), audit.getMethodName(), eventTime);
            payload.clear();
            payload.put("@type", AUDIT_LOG_TYPE_URL);
            payload.put("serviceName", audit.getServiceName());
            payload.put("methodName", audit.getMethodName());
            payload.put("resourceName", audit.getResourceName());
            payload.put("packed", typeUrlAndBase64(packed));
        }
        return payload;
    }

    private static Map<String, Object> typeUrlAndBase64(Any any) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("typeUrl", any.getTypeUrl());
        out.put("base64", Base64.getEncoder().encodeToString(any.getValue().toByteArray()));
        return out;
    }

    /**
     * Actor order: {@code principalEmail}, then {@code principalSubject},
     * then the first usable {@code serviceAccountDelegationInfo}. A masked
     * value such as {@code google-internal} is a valid actor, not an error.
     */
    static String resolvePrincipal(AuthenticationInfo auth) {
        if (!auth.getPrincipalEmail().isBlank()) {
            return auth.getPrincipalEmail();
        }
        if (!auth.getPrincipalSubject().isBlank()) {
            return auth.getPrincipalSubject();
        }
        for (ServiceAccountDelegationInfo delegation : auth.getServiceAccountDelegationInfoList()) {
            if (delegation.hasFirstPartyPrincipal()
                    && !delegation.getFirstPartyPrincipal().getPrincipalEmail().isBlank()) {
                return delegation.getFirstPartyPrincipal().getPrincipalEmail();
            }
            if (!delegation.getPrincipalSubject().isBlank()) {
                return delegation.getPrincipalSubject();
            }
        }
        return null;
    }

    /**
     * Classifies the principal: any {@code *gserviceaccount.com} address or
     * {@code serviceAccount:} subject is a service account, the masked
     * {@code google-internal} actor is a system principal, everything else
     * is a user. Returns {@code null} when the entry names no principal.
     */
    static String derivePrincipalType(String principal) {
        if (principal == null || principal.isBlank()) {
            return null;
        }
        String lower = principal.toLowerCase(Locale.ROOT);
        if (lower.endsWith(SERVICE_ACCOUNT_DOMAIN) || lower.startsWith(SERVICE_ACCOUNT_SUBJECT_PREFIX)) {
            return "SERVICE_ACCOUNT";
        }
        if (lower.equals(PRINCIPAL_GOOGLE_INTERNAL)) {
            return "SYSTEM";
        }
        return "USER";
    }

    private List<String> parseProjects() {
        if (projectsCsv == null || projectsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(projectsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private Optional<Instant> readWatermark(String projectId) {
        return watermarkRepo.findByProviderAndAccountId(PROVIDER, projectId)
                .map(SourceWatermarkEntity::getLastEventTime);
    }

    private void advanceWatermark(String projectId, Instant newWatermark) {
        SourceWatermarkEntity wm = watermarkRepo.findByProviderAndAccountId(PROVIDER, projectId)
                .orElseGet(() -> SourceWatermarkEntity.builder()
                        .provider(PROVIDER)
                        .accountId(projectId)
                        .build());
        wm.setLastEventTime(newWatermark);
        wm.setUpdatedAt(clock.instant());
        watermarkRepo.save(wm);
    }

    /** Production {@link EntryFetcher}: Cloud Logging {@code entries.list}, oldest first. */
    private void fetchFromCloudLogging(String projectId, String filter, int pageSize,
                                       Predicate<LogEntry> sink) throws Exception {
        try (Logging logging = LoggingOptions.newBuilder()
                .setProjectId(projectId)
                .build()
                .getService()) {
            Page<LogEntry> page = logging.listLogEntries(
                    Logging.EntryListOption.filter(filter),
                    Logging.EntryListOption.sortOrder(
                            Logging.SortingField.TIMESTAMP, Logging.SortingOrder.ASCENDING),
                    Logging.EntryListOption.pageSize(pageSize));
            for (LogEntry entry : page.iterateAll()) {
                if (!sink.test(entry)) {
                    return;
                }
            }
        }
    }

    private void countSkipped(String reason) {
        meterRegistry.counter(METRIC_SKIPPED, "reason", reason).increment();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * GCP method names follow {@code <service>.<resource>.<verb>}.
     * Map the verb to the {@code DlapLogEvent.changeType} enum.
     *
     * <p>BigQuery job methods ({@code JobService.InsertJob}, {@code Query},
     * {@code jobservice.jobcompleted} and the like) are job activity, not
     * asset creation or modification, so they map to {@code ACCESS}. This
     * is a verb heuristic; the per-source classification table replaces it.
     *
     * @param methodName the audit {@code methodName}.
     * @return one of CREATE, UPDATE, DELETE, ACCESS or PERMISSION.
     */
    static String deriveChangeType(String methodName) {
        if (methodName == null) return "UPDATE";
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("google.cloud.bigquery.v2.jobservice.") || lower.startsWith("jobservice.")) {
            return "ACCESS";
        }
        if (lower.contains("create") || lower.contains("insert")) return "CREATE";
        if (lower.contains("delete")) return "DELETE";
        if (lower.contains("setiam") || lower.contains("setpolicy")) return "PERMISSION";
        if (lower.contains("get") || lower.contains("list") || lower.contains("read")) return "ACCESS";
        return "UPDATE";
    }

    /**
     * {@code FAILURE} when {@code status.code != 0} or any
     * {@code authorizationInfo[].granted} is false, {@code SUCCESS}
     * otherwise. In proto3 a denied check and a missing {@code granted}
     * field both read as false, which is how Cloud Logging prints a denial.
     *
     * @param audit the unpacked audit log.
     * @return {@code SUCCESS} or {@code FAILURE}.
     */
    static String deriveResultStatus(AuditLog audit) {
        if (audit.hasStatus() && audit.getStatus().getCode() != 0) {
            return "FAILURE";
        }
        for (AuthorizationInfo info : audit.getAuthorizationInfoList()) {
            if (!info.getGranted()) {
                return "FAILURE";
            }
        }
        return "SUCCESS";
    }

    /** Mutable result of one poll: entries handed to ingest and the newest ingested event time. */
    private static final class PollState {
        private int ingested;
        private Instant newest;
    }
}
