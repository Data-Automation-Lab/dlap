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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.model.LogEventEntity;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.logingest.repository.SourceWatermarkRepository;
import com.dlap.logingest.service.ILogIngestService;
import com.dlap.logingest.service.impl.LogIngestServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.MonitoredResource;
import com.google.cloud.audit.AuditLog;
import com.google.cloud.audit.AuthenticationInfo;
import com.google.cloud.audit.AuthorizationInfo;
import com.google.cloud.audit.ServiceAccountDelegationInfo;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Payload;
import com.google.cloud.logging.Severity;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link GcpLogSource}: the {@code Any} to {@code AuditLog}
 * unpack that previously dropped every entry, the field mapping for GCS and
 * both BigQuery audit generations, the native event key, the event-time and
 * result rules, and the overlap-window poll with dedup. Entries are built the
 * way the gRPC Cloud Logging client delivers them
 * ({@code Payload.ProtoPayload.of(Any.pack(auditLog))}); no network, no
 * credentials and no emulator are involved.
 */
class GcpLogSourceTest {

    private static final String PROJECT = "dlap-gcp-test";
    private static final String DATA_ACCESS_LOG =
            "projects/" + PROJECT + "/logs/cloudaudit.googleapis.com%2Fdata_access";
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final String BQ_V1_TYPE_URL =
            "type.googleapis.com/google.cloud.bigquery.logging.v1.AuditData";

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicReference<SourceWatermarkEntity> watermark = new AtomicReference<>();
    private final SourceWatermarkRepository watermarkRepo = mock(SourceWatermarkRepository.class);
    private final ILogIngestService ingest = mock(ILogIngestService.class);

    private List<LogEntry> feed = new ArrayList<>();
    private final AtomicReference<String> lastFilter = new AtomicReference<>();
    private GcpLogSource source;

    @BeforeEach
    void setUp() {
        when(watermarkRepo.findByProviderAndAccountId(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(watermark.get()));
        when(watermarkRepo.save(any(SourceWatermarkEntity.class))).thenAnswer(inv -> {
            SourceWatermarkEntity saved = inv.getArgument(0);
            watermark.set(saved);
            return saved;
        });
        source = newSource(ingest);
    }

    private GcpLogSource newSource(ILogIngestService service) {
        return new GcpLogSource(service, watermarkRepo, metrics, clock, replayingFeed());
    }

    private GcpLogSource.EntryFetcher replayingFeed() {
        return (projectId, filter, pageSize, sink) -> {
            lastFilter.set(filter);
            for (LogEntry entry : feed) {
                if (!sink.test(entry)) {
                    return;
                }
            }
        };
    }

    // ---- unpack and mapping: the four shapes named by the package ----

    @Test
    void storageObjectsGet_unpacksAndMapsToAccess() throws Exception {
        AuditLog audit = auditFromJson("""
                {
                  "serviceName": "storage.googleapis.com",
                  "methodName": "storage.objects.get",
                  "resourceName": "projects/_/buckets/dlap-raw/objects/orders/2026-10-17.csv",
                  "authenticationInfo": {"principalEmail": "etl@dlap-gcp-test.iam.gserviceaccount.com"},
                  "authorizationInfo": [{
                    "resource": "projects/_/buckets/dlap-raw/objects/orders/2026-10-17.csv",
                    "permission": "storage.objects.get", "granted": true}],
                  "requestMetadata": {"callerIp": "203.0.113.7", "callerSuppliedUserAgent": "gcloud-java"}
                }""");
        Instant when = Instant.parse("2026-10-18T11:50:00.123456Z");

        IngestRequestDTO dto = source.mapToIngestRequest(entry(audit, "ins-1", when), PROJECT);

        assertThat(dto).isNotNull();
        assertThat(dto.getSourceProvider()).isEqualTo("GCP");
        assertThat(dto.getSourceService()).isEqualTo("storage.googleapis.com");
        assertThat(dto.getOperation()).isEqualTo("storage.objects.get");
        assertThat(dto.getChangeType()).isEqualTo("ACCESS");
        assertThat(dto.getResultStatus()).isEqualTo("SUCCESS");
        assertThat(dto.getEventTime()).isEqualTo(when);
        assertThat(dto.getPrincipalId()).isEqualTo("etl@dlap-gcp-test.iam.gserviceaccount.com");
        assertThat(dto.getPrincipalType()).isEqualTo("SERVICE_ACCOUNT");
        assertThat(dto.getTargetResourceKind()).isEqualTo("gcs_bucket");
        assertThat(dto.getTargetResourceId())
                .isEqualTo("projects/_/buckets/dlap-raw/objects/orders/2026-10-17.csv");
        assertThat(dto.getTargetProject()).isEqualTo(PROJECT);
        assertThat(dto.getTargetRegion()).isEqualTo("us-central1");
        assertThat(dto.getCallerIp()).isEqualTo("203.0.113.7");
        assertThat(dto.getUserAgent()).isEqualTo("gcloud-java");

        Map<String, Object> raw = dto.getRawEvent();
        assertThat(raw).containsEntry("insertId", "ins-1").containsEntry("logName", DATA_ACCESS_LOG);
        Map<String, Object> payload = payload(dto);
        assertThat(payload).containsEntry("@type", "type.googleapis.com/google.cloud.audit.AuditLog");
        assertThat(payload).containsEntry("methodName", "storage.objects.get");
        assertThat(map(payload.get("authenticationInfo")))
                .containsEntry("principalEmail", "etl@dlap-gcp-test.iam.gserviceaccount.com");
    }

    @Test
    void storageObjectsCreate_mapsToCreate_andKeepsRequestAndResponse() throws Exception {
        AuditLog audit = auditFromJson("""
                {
                  "serviceName": "storage.googleapis.com",
                  "methodName": "storage.objects.create",
                  "resourceName": "projects/_/buckets/dlap-raw/objects/new.parquet",
                  "authenticationInfo": {"principalEmail": "loader@example.com"},
                  "authorizationInfo": [
                    {"resource": "projects/_/buckets/dlap-raw/objects/new.parquet",
                     "permission": "storage.objects.create", "granted": true}],
                  "request": {"name": "new.parquet"},
                  "response": {"size": "2048", "generation": "1760000000000000"}
                }""");

        IngestRequestDTO dto = source.mapToIngestRequest(
                entry(audit, "ins-2", Instant.parse("2026-10-18T11:51:00Z")), PROJECT);

        assertThat(dto).isNotNull();
        assertThat(dto.getChangeType()).isEqualTo("CREATE");
        assertThat(dto.getResultStatus()).isEqualTo("SUCCESS");
        Map<String, Object> payload = payload(dto);
        assertThat(map(payload.get("response"))).containsEntry("size", "2048");
        assertThat(map(payload.get("request"))).containsEntry("name", "new.parquet");
    }

    @Test
    void bigQueryV2_metadataStruct_isPrintedAsJson_andJobMethodIsNotCreate() throws Exception {
        AuditLog audit = auditFromJson("""
                {
                  "serviceName": "bigquery.googleapis.com",
                  "methodName": "google.cloud.bigquery.v2.JobService.InsertJob",
                  "resourceName": "projects/dlap-gcp-test/datasets/sales/tables/orders",
                  "authenticationInfo": {"principalEmail": "analyst@example.com"},
                  "authorizationInfo": [
                    {"resource": "projects/dlap-gcp-test/datasets/sales/tables/orders",
                     "permission": "bigquery.tables.getData", "granted": true}],
                  "metadata": {
                    "@type": "type.googleapis.com/google.cloud.audit.BigQueryAuditMetadata",
                    "tableDataRead": {"reason": "JOB", "jobName": "projects/dlap-gcp-test/jobs/job_abc",
                                      "fields": ["order_id", "amount"]}
                  }
                }""");

        IngestRequestDTO dto = source.mapToIngestRequest(
                entry(audit, "ins-3", Instant.parse("2026-10-18T11:52:00Z"), "bigquery_dataset"), PROJECT);

        assertThat(dto).isNotNull();
        assertThat(dto.getOperation()).isEqualTo("google.cloud.bigquery.v2.JobService.InsertJob");
        // "InsertJob" contains "insert", but a query job is not an asset creation.
        assertThat(dto.getChangeType()).isEqualTo("ACCESS");
        assertThat(dto.getTargetResourceKind()).isEqualTo("bigquery_dataset");
        assertThat(dto.getTargetResourceId())
                .isEqualTo("projects/dlap-gcp-test/datasets/sales/tables/orders");

        // The raw event is what gets persisted as jsonb and published to Kafka.
        String json = new ObjectMapper().writeValueAsString(dto.getRawEvent());
        assertThat(json).contains("\"insertId\":\"ins-3\"").contains("\"tableDataRead\"");

        Map<String, Object> metadata = map(payload(dto).get("metadata"));
        assertThat(metadata).containsEntry("@type",
                "type.googleapis.com/google.cloud.audit.BigQueryAuditMetadata");
        Map<String, Object> read = map(metadata.get("tableDataRead"));
        assertThat(read).containsEntry("reason", "JOB");
        assertThat(read.get("fields")).isEqualTo(List.of("order_id", "amount"));
    }

    @Test
    void bigQueryV1_serviceData_isKeptAsTypeUrlAndBase64() {
        byte[] bytes = "v1-audit-data-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // The v1 AuditData class is not on the classpath, so JsonFormat has no
        // type for it. This is the case that used to throw.
        Any serviceData = Any.newBuilder()
                .setTypeUrl(BQ_V1_TYPE_URL)
                .setValue(ByteString.copyFrom(bytes))
                .build();
        AuditLog audit = AuditLog.newBuilder()
                .setServiceName("bigquery.googleapis.com")
                .setMethodName("jobservice.jobcompleted")
                .setResourceName("projects/dlap-gcp-test/jobs/job_xyz")
                .setAuthenticationInfo(AuthenticationInfo.newBuilder().setPrincipalEmail("bi@example.com"))
                .setServiceData(serviceData)
                .build();

        IngestRequestDTO dto = source.mapToIngestRequest(
                entry(audit, "ins-4", Instant.parse("2026-10-18T11:53:00Z"), "bigquery_resource"), PROJECT);

        assertThat(dto).isNotNull();
        assertThat(dto.getChangeType()).isEqualTo("ACCESS");
        Map<String, Object> sd = map(payload(dto).get("serviceData"));
        assertThat(sd).containsEntry("typeUrl", BQ_V1_TYPE_URL);
        assertThat(sd).containsEntry("base64", Base64.getEncoder().encodeToString(bytes));
        assertThat(payload(dto)).containsEntry("methodName", "jobservice.jobcompleted");
    }

    // ---- identity, time, result ----

    @Test
    void nativeEventId_isLogNameTimestampInsertId() {
        Instant when = Instant.parse("2026-10-18T11:50:00.5Z");
        IngestRequestDTO dto = source.mapToIngestRequest(entry(defaultAudit(), "ins-9", when), PROJECT);

        assertThat(dto.getRequestId()).isEqualTo(DATA_ACCESS_LOG + "|" + when + "|ins-9");
    }

    @Test
    void entryWithoutInsertId_hasNoNativeKey_soTheIngestFallbackHashApplies() {
        LogEntry noInsertId = LogEntry.newBuilder(Payload.ProtoPayload.of(Any.pack(defaultAudit())))
                .setLogName(DATA_ACCESS_LOG)
                .setTimestamp(Instant.parse("2026-10-18T11:50:00Z"))
                .build();

        IngestRequestDTO dto = source.mapToIngestRequest(noInsertId, PROJECT);

        assertThat(dto).isNotNull();
        assertThat(dto.getRequestId()).isNull();
    }

    @Test
    void eventTime_usesTimestamp_thenReceiveTimestamp_neverTheClock() {
        Instant ts = Instant.parse("2026-10-18T10:00:00Z");
        Instant received = Instant.parse("2026-10-18T10:00:07Z");
        LogEntry both = base(defaultAudit(), "a").setTimestamp(ts).setReceiveTimestamp(received).build();
        LogEntry onlyReceived = base(defaultAudit(), "b").setReceiveTimestamp(received).build();
        LogEntry neither = base(defaultAudit(), "c").build();

        assertThat(source.mapToIngestRequest(both, PROJECT).getEventTime()).isEqualTo(ts);
        assertThat(metrics.find(GcpLogSource.METRIC_TIME_FALLBACK).counter()).isNull();

        IngestRequestDTO fallback = source.mapToIngestRequest(onlyReceived, PROJECT);
        assertThat(fallback.getEventTime()).isEqualTo(received);
        assertThat(metrics.counter(GcpLogSource.METRIC_TIME_FALLBACK).count()).isEqualTo(1.0);

        assertThat(source.mapToIngestRequest(neither, PROJECT)).isNull();
        assertThat(skipped(GcpLogSource.REASON_NO_TIMESTAMP)).isEqualTo(1.0);
    }

    @Test
    void resultStatus_failsOnNonZeroStatusCode() {
        AuditLog denied = defaultAudit().toBuilder()
                .setStatus(Status.newBuilder().setCode(7).setMessage("PERMISSION_DENIED"))
                .build();
        assertThat(GcpLogSource.deriveResultStatus(denied)).isEqualTo("FAILURE");
    }

    @Test
    void resultStatus_failsWhenAnyAuthorizationWasNotGranted() {
        AuditLog notGranted = defaultAudit().toBuilder()
                .addAuthorizationInfo(AuthorizationInfo.newBuilder()
                        .setPermission("storage.objects.delete").setGranted(false))
                .build();
        assertThat(GcpLogSource.deriveResultStatus(notGranted)).isEqualTo("FAILURE");
    }

    @Test
    void resultStatus_succeedsWhenStatusIsOkOrAbsentAndEverythingWasGranted() {
        assertThat(GcpLogSource.deriveResultStatus(defaultAudit())).isEqualTo("SUCCESS");
        AuditLog okStatus = defaultAudit().toBuilder().setStatus(Status.newBuilder().setCode(0)).build();
        assertThat(GcpLogSource.deriveResultStatus(okStatus)).isEqualTo("SUCCESS");
        AuditLog noAuthInfo = AuditLog.newBuilder().setMethodName("m").setResourceName("r").build();
        assertThat(GcpLogSource.deriveResultStatus(noAuthInfo)).isEqualTo("SUCCESS");
    }

    @Test
    void failedEntry_isMappedWithFailureStatus() {
        AuditLog denied = defaultAudit().toBuilder()
                .setStatus(Status.newBuilder().setCode(7))
                .build();
        IngestRequestDTO dto = source.mapToIngestRequest(
                entry(denied, "ins-d", Instant.parse("2026-10-18T11:50:00Z")), PROJECT);
        assertThat(dto.getResultStatus()).isEqualTo("FAILURE");
    }

    @Test
    void principal_prefersEmail_thenSubject_thenDelegation() {
        AuthenticationInfo email = AuthenticationInfo.newBuilder()
                .setPrincipalEmail("a@example.com").setPrincipalSubject("user:b@example.com").build();
        AuthenticationInfo subject = AuthenticationInfo.newBuilder()
                .setPrincipalSubject("serviceAccount:sa@p.iam.gserviceaccount.com").build();
        AuthenticationInfo delegated = AuthenticationInfo.newBuilder()
                .addServiceAccountDelegationInfo(ServiceAccountDelegationInfo.newBuilder()
                        .setFirstPartyPrincipal(ServiceAccountDelegationInfo.FirstPartyPrincipal.newBuilder()
                                .setPrincipalEmail("real@example.com")))
                .build();
        AuthenticationInfo delegatedSubject = AuthenticationInfo.newBuilder()
                .addServiceAccountDelegationInfo(ServiceAccountDelegationInfo.newBuilder()
                        .setPrincipalSubject("principal://iam.googleapis.com/pool/subject/x"))
                .build();

        assertThat(GcpLogSource.resolvePrincipal(email)).isEqualTo("a@example.com");
        assertThat(GcpLogSource.resolvePrincipal(subject))
                .isEqualTo("serviceAccount:sa@p.iam.gserviceaccount.com");
        assertThat(GcpLogSource.resolvePrincipal(delegated)).isEqualTo("real@example.com");
        assertThat(GcpLogSource.resolvePrincipal(delegatedSubject))
                .isEqualTo("principal://iam.googleapis.com/pool/subject/x");
        assertThat(GcpLogSource.resolvePrincipal(AuthenticationInfo.getDefaultInstance())).isNull();
    }

    @Test
    void principalType_recognisesServiceAccountsUsersAndTheMaskedSystemActor() {
        assertThat(GcpLogSource.derivePrincipalType("etl@p.iam.gserviceaccount.com")).isEqualTo("SERVICE_ACCOUNT");
        assertThat(GcpLogSource.derivePrincipalType("1234-compute@developer.gserviceaccount.com"))
                .isEqualTo("SERVICE_ACCOUNT");
        assertThat(GcpLogSource.derivePrincipalType("serviceAccount:sa@p.iam.gserviceaccount.com"))
                .isEqualTo("SERVICE_ACCOUNT");
        assertThat(GcpLogSource.derivePrincipalType("alice@example.com")).isEqualTo("USER");
        assertThat(GcpLogSource.derivePrincipalType("google-internal")).isEqualTo("SYSTEM");
        assertThat(GcpLogSource.derivePrincipalType(null)).isNull();
    }

    @Test
    void entryWithoutAnyPrincipal_isStillMapped() {
        AuditLog anonymous = AuditLog.newBuilder()
                .setServiceName("storage.googleapis.com").setMethodName("storage.objects.get")
                .setResourceName("projects/_/buckets/b/objects/o").build();

        IngestRequestDTO dto = source.mapToIngestRequest(
                entry(anonymous, "ins-n", Instant.parse("2026-10-18T11:50:00Z")), PROJECT);

        assertThat(dto).isNotNull();
        assertThat(dto.getPrincipalId()).isNull();
        assertThat(dto.getPrincipalType()).isNull();
    }

    @Test
    void changeTypeTable() {
        assertThat(GcpLogSource.deriveChangeType("storage.objects.get")).isEqualTo("ACCESS");
        assertThat(GcpLogSource.deriveChangeType("storage.objects.list")).isEqualTo("ACCESS");
        assertThat(GcpLogSource.deriveChangeType("storage.objects.create")).isEqualTo("CREATE");
        assertThat(GcpLogSource.deriveChangeType("storage.objects.delete")).isEqualTo("DELETE");
        assertThat(GcpLogSource.deriveChangeType("storage.setIamPermissions")).isEqualTo("PERMISSION");
        assertThat(GcpLogSource.deriveChangeType("google.cloud.bigquery.v2.TableService.InsertTable"))
                .isEqualTo("CREATE");
        assertThat(GcpLogSource.deriveChangeType("google.cloud.bigquery.v2.TableService.DeleteTable"))
                .isEqualTo("DELETE");
        assertThat(GcpLogSource.deriveChangeType("google.cloud.bigquery.v2.TableDataService.List"))
                .isEqualTo("ACCESS");
        // BigQuery job activity is access, whatever the verb says.
        assertThat(GcpLogSource.deriveChangeType("google.cloud.bigquery.v2.JobService.InsertJob"))
                .isEqualTo("ACCESS");
        assertThat(GcpLogSource.deriveChangeType("google.cloud.bigquery.v2.JobService.Query"))
                .isEqualTo("ACCESS");
        assertThat(GcpLogSource.deriveChangeType("google.cloud.bigquery.v2.JobService.DeleteJob"))
                .isEqualTo("ACCESS");
        assertThat(GcpLogSource.deriveChangeType("jobservice.insert")).isEqualTo("ACCESS");
        assertThat(GcpLogSource.deriveChangeType("jobservice.jobcompleted")).isEqualTo("ACCESS");
        assertThat(GcpLogSource.deriveChangeType("tableservice.insert")).isEqualTo("CREATE");
        assertThat(GcpLogSource.deriveChangeType(null)).isEqualTo("UPDATE");
    }

    // ---- skipped entries are counted, never thrown ----

    @Test
    void nonAuditPayloads_areSkippedAndCounted() {
        LogEntry text = LogEntry.newBuilder(Payload.StringPayload.of("hello")).build();
        LogEntry json = LogEntry.newBuilder(Payload.JsonPayload.of(Map.of("methodName", "x"))).build();
        LogEntry otherProto = LogEntry.newBuilder(
                Payload.ProtoPayload.of(Any.pack(Struct.getDefaultInstance()))).build();
        LogEntry noPayload = LogEntry.newBuilder(null).build();

        assertThat(source.mapToIngestRequest(text, PROJECT)).isNull();
        assertThat(source.mapToIngestRequest(json, PROJECT)).isNull();
        assertThat(source.mapToIngestRequest(otherProto, PROJECT)).isNull();
        assertThat(source.mapToIngestRequest(noPayload, PROJECT)).isNull();
        assertThat(skipped(GcpLogSource.REASON_NOT_AUDIT)).isEqualTo(4.0);
    }

    @Test
    void corruptAuditPayload_isSkippedAndCounted() {
        Any corrupt = Any.newBuilder()
                .setTypeUrl(GcpLogSource.AUDIT_LOG_TYPE_URL)
                .setValue(ByteString.copyFrom(new byte[] {0x0A, 0x05, 0x41}))
                .build();
        LogEntry entry = LogEntry.newBuilder(Payload.ProtoPayload.of(corrupt))
                .setTimestamp(NOW).build();

        assertThat(source.mapToIngestRequest(entry, PROJECT)).isNull();
        assertThat(skipped(GcpLogSource.REASON_UNPACK_FAILED)).isEqualTo(1.0);
    }

    @Test
    void entryMissingMethodOrResource_isSkippedAndCounted() {
        AuditLog noMethod = AuditLog.newBuilder().setResourceName("r").build();
        AuditLog noResource = AuditLog.newBuilder().setMethodName("m").build();

        assertThat(source.mapToIngestRequest(entry(noMethod, "x", NOW), PROJECT)).isNull();
        assertThat(source.mapToIngestRequest(entry(noResource, "y", NOW), PROJECT)).isNull();
        assertThat(skipped(GcpLogSource.REASON_INCOMPLETE)).isEqualTo(2.0);
    }

    // ---- polling: overlap window, watermark, failures ----

    @Test
    void firstPoll_startsAtTheInitialLookback_andStoresTheNewestEventTime() {
        Instant t1 = Instant.parse("2026-10-18T11:30:00Z");
        Instant t2 = Instant.parse("2026-10-18T11:45:00.250Z");
        feed = List.of(entry(defaultAudit(), "e1", t1), entry(defaultAudit(), "e2", t2));

        int polled = source.pollProject(PROJECT);

        assertThat(polled).isEqualTo(2);
        assertThat(lastFilter.get()).endsWith("AND timestamp >= \"2026-10-18T11:00:00Z\"");
        verify(ingest, times(2)).ingest(any(IngestRequestDTO.class));
        // Stored as the newest event time, not newest + 1 ms.
        assertThat(watermark.get().getLastEventTime()).isEqualTo(t2);
        assertThat(watermark.get().getProvider()).isEqualTo("GCP");
        assertThat(watermark.get().getAccountId()).isEqualTo(PROJECT);
        assertThat(watermark.get().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void laterPoll_rereadsTheOverlapWindowBeforeTheWatermark() {
        watermark.set(SourceWatermarkEntity.builder().provider("GCP").accountId(PROJECT)
                .lastEventTime(Instant.parse("2026-10-18T11:40:00Z"))
                .updatedAt(NOW.minusSeconds(60)).build());
        feed = List.of();

        source.pollProject(PROJECT);

        // Default overlap is 10 minutes.
        assertThat(lastFilter.get()).endsWith("AND timestamp >= \"2026-10-18T11:30:00Z\"");
        verify(watermarkRepo, never()).save(any());
    }

    @Test
    void overlapIsConfigurable() {
        ReflectionTestUtils.setField(source, "overlapMinutes", 25L);
        watermark.set(SourceWatermarkEntity.builder().provider("GCP").accountId(PROJECT)
                .lastEventTime(Instant.parse("2026-10-18T11:40:00Z")).updatedAt(NOW).build());

        source.pollProject(PROJECT);

        assertThat(lastFilter.get()).endsWith("AND timestamp >= \"2026-10-18T11:15:00Z\"");
    }

    @Test
    void watermarkNeverMovesBackwards_whenOnlyOverlapEntriesAreReread() {
        Instant stored = Instant.parse("2026-10-18T11:40:00Z");
        watermark.set(SourceWatermarkEntity.builder().provider("GCP").accountId(PROJECT)
                .lastEventTime(stored).updatedAt(NOW).build());
        feed = List.of(entry(defaultAudit(), "old", Instant.parse("2026-10-18T11:35:00Z")));

        int polled = source.pollProject(PROJECT);

        assertThat(polled).isEqualTo(1);
        assertThat(watermark.get().getLastEventTime()).isEqualTo(stored);
        verify(watermarkRepo, never()).save(any());
    }

    @Test
    void failedIngest_stopsThePoll_andTheWatermarkStaysBeforeTheFailedEntry() {
        Instant t1 = Instant.parse("2026-10-18T11:30:00Z");
        Instant t2 = Instant.parse("2026-10-18T11:31:00Z");
        Instant t3 = Instant.parse("2026-10-18T11:32:00Z");
        feed = List.of(entry(defaultAudit(), "e1", t1), entry(defaultAudit(), "e2", t2), entry(defaultAudit(), "e3", t3));
        doThrow(new IllegalStateException("db down")).when(ingest)
                .ingest(org.mockito.ArgumentMatchers.argThat(dto -> dto != null && dto.getEventTime().equals(t2)));

        int polled = source.pollProject(PROJECT);

        assertThat(polled).isEqualTo(1);
        assertThat(watermark.get().getLastEventTime()).isEqualTo(t1);
        // e3 is not attempted after e2 failed.
        verify(ingest, times(2)).ingest(any(IngestRequestDTO.class));
    }

    @Test
    void clientErrorMidStream_keepsTheProgressMadeBeforeIt() {
        Instant t1 = Instant.parse("2026-10-18T11:30:00Z");
        GcpLogSource.EntryFetcher failing = (projectId, filter, size, sink) -> {
            sink.test(entry(defaultAudit(), "e1", t1));
            throw new IllegalStateException("429 rate limited");
        };
        GcpLogSource failingSource = new GcpLogSource(ingest, watermarkRepo, metrics, clock, failing);

        int polled = failingSource.pollProject(PROJECT);

        assertThat(polled).isEqualTo(1);
        assertThat(watermark.get().getLastEventTime()).isEqualTo(t1);
    }

    @Test
    void skippedEntry_doesNotBlockLaterEntriesOrMoveTheWatermark() {
        Instant t2 = Instant.parse("2026-10-18T11:31:00Z");
        LogEntry noTime = base(defaultAudit(), "no-time").build();
        feed = List.of(noTime, entry(defaultAudit(), "e2", t2));

        int polled = source.pollProject(PROJECT);

        assertThat(polled).isEqualTo(1);
        assertThat(watermark.get().getLastEventTime()).isEqualTo(t2);
        assertThat(skipped(GcpLogSource.REASON_NO_TIMESTAMP)).isEqualTo(1.0);
    }

    @Test
    void poll_isolatesProjects_andSkipsWhenNoneAreConfigured() {
        AtomicReference<List<String>> polledProjects = new AtomicReference<>(new ArrayList<>());
        GcpLogSource.EntryFetcher fetcher = (projectId, filter, size, sink) -> {
            polledProjects.get().add(projectId);
            if (projectId.equals("bad")) {
                throw new IllegalStateException("no access");
            }
        };
        GcpLogSource multi = new GcpLogSource(ingest, watermarkRepo, metrics, clock, fetcher);

        multi.poll();
        assertThat(polledProjects.get()).isEmpty();

        ReflectionTestUtils.setField(multi, "projectsCsv", " bad , good ,, ");
        multi.poll();
        assertThat(polledProjects.get()).containsExactly("bad", "good");
    }

    // ---- overlap + dedup through the real ingest service ----

    @Test
    void overlapWindow_doesNotRepublishEntriesAlreadyStored() {
        LogEventRepository repository = mock(LogEventRepository.class);
        LogEventPublisher publisher = mock(LogEventPublisher.class);
        Set<String> stored = new HashSet<>();
        when(repository.existsById(anyString())).thenAnswer(inv -> stored.contains(inv.<String>getArgument(0)));
        when(repository.save(any(LogEventEntity.class))).thenAnswer(inv -> {
            LogEventEntity row = inv.getArgument(0);
            stored.add(row.getEventId());
            return row;
        });
        LogIngestServiceImpl service = new LogIngestServiceImpl(repository, publisher, metrics);
        GcpLogSource real = newSource(service);

        Instant t1 = Instant.parse("2026-10-18T11:30:00Z");
        Instant t2 = Instant.parse("2026-10-18T11:35:00Z");
        Instant t3 = Instant.parse("2026-10-18T11:41:00Z");
        LogEntry e1 = entry(defaultAudit(), "e1", t1);
        LogEntry e2 = entry(defaultAudit(), "e2", t2);
        // Same timestamp and resource as e2, different insertId: a distinct read.
        LogEntry e2b = entry(defaultAudit(), "e2b", t2);
        LogEntry e3 = entry(defaultAudit(), "e3", t3);

        feed = List.of(e1, e2, e2b);
        real.pollProject(PROJECT);
        // Second poll re-reads e2 and e2b (inside the overlap) and sees one new entry.
        feed = List.of(e2, e2b, e3);
        real.pollProject(PROJECT);

        verify(publisher, times(4)).publish(anyString(), anyMap());
        verify(repository, times(4)).save(any(LogEventEntity.class));
        assertThat(metrics.counter(LogIngestServiceImpl.METRIC_DEDUP_HITS).count()).isEqualTo(2.0);
        assertThat(stored).hasSize(4);
    }

    // ---- helpers ----

    private double skipped(String reason) {
        return metrics.counter(GcpLogSource.METRIC_SKIPPED, "reason", reason).count();
    }

    private static AuditLog defaultAudit() {
        return AuditLog.newBuilder()
                .setServiceName("storage.googleapis.com")
                .setMethodName("storage.objects.get")
                .setResourceName("projects/_/buckets/dlap-raw/objects/o.csv")
                .setAuthenticationInfo(AuthenticationInfo.newBuilder().setPrincipalEmail("u@example.com"))
                .addAuthorizationInfo(AuthorizationInfo.newBuilder()
                        .setPermission("storage.objects.get").setGranted(true))
                .build();
    }

    private static AuditLog auditFromJson(String json) throws InvalidProtocolBufferException {
        AuditLog.Builder builder = AuditLog.newBuilder();
        JsonFormat.parser().merge(json, builder);
        return builder.build();
    }

    private static LogEntry.Builder base(AuditLog audit, String insertId) {
        return LogEntry.newBuilder(Payload.ProtoPayload.of(Any.pack(audit)))
                .setLogName(DATA_ACCESS_LOG)
                .setInsertId(insertId)
                .setSeverity(Severity.INFO)
                .setResource(MonitoredResource.newBuilder("gcs_bucket")
                        .addLabel("project_id", PROJECT)
                        .addLabel("bucket_name", "dlap-raw")
                        .addLabel("location", "us-central1")
                        .build());
    }

    private static LogEntry entry(AuditLog audit, String insertId, Instant timestamp) {
        return base(audit, insertId).setTimestamp(timestamp).build();
    }

    private static LogEntry entry(AuditLog audit, String insertId, Instant timestamp, String resourceType) {
        return base(audit, insertId)
                .setResource(MonitoredResource.newBuilder(resourceType)
                        .addLabel("project_id", PROJECT).build())
                .setTimestamp(timestamp).build();
    }

    private static Map<String, Object> payload(IngestRequestDTO dto) {
        return map(dto.getRawEvent().get("protoPayload"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
