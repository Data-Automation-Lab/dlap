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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.model.LogEventEntity;
import com.dlap.logingest.repository.LogEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link LogIngestServiceImpl} - the core ADR-0033 log-spine
 * transform. The repository and Kafka publisher are mocked so the assertions
 * focus on the pure ingest logic: stable event-id derivation, idempotent
 * persistence, the normalized event shape published to Kafka, and that an
 * already-stored event is neither persisted nor published again.
 */
@ExtendWith(MockitoExtension.class)
class LogIngestServiceImplTest {

    @Mock
    private LogEventRepository repository;

    @Mock
    private LogEventPublisher publisher;

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private LogIngestServiceImpl service;

    @Captor
    private ArgumentCaptor<String> partitionKeyCaptor;

    @Captor
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> normalizedCaptor;

    private IngestRequestDTO request;

    @BeforeEach
    void setUp() {
        service = new LogIngestServiceImpl(repository, publisher, metrics);
        request = IngestRequestDTO.builder()
                .sourceProvider("AWS")
                .sourceService("s3.amazonaws.com")
                .operation("CreateBucket")
                .changeType("CREATE")
                .resultStatus("SUCCESS")
                .eventTime(Instant.parse("2026-06-13T12:00:00Z"))
                .principalId("AKIAEXAMPLE")
                .principalType("USER")
                .principalDisplayName("alice")
                .principalAccountId("111122223333")
                .targetResourceKind("AWS::S3::Bucket")
                .targetResourceId("dlap-demo")
                .targetResourceName("dlap-demo")
                .targetProject("proj-1")
                .targetRegion("us-east-1")
                .callerIp("10.0.0.1")
                .userAgent("aws-cli/2.0")
                .requestId("req-abc")
                .traceId("trace-xyz")
                .rawEvent(Map.of("eventVersion", "1.08"))
                .build();
    }

    @Test
    void ingest_persistsAndPublishesNormalizedEvent() {
        when(repository.existsById(anyString())).thenReturn(false);

        String eventId = service.ingest(request);

        // Event id is a 64-char lowercase SHA-256 hex digest.
        assertThat(eventId).isNotBlank();
        assertThat(eventId).hasSize(64);
        assertThat(eventId).matches("[0-9a-f]{64}");

        // Persisted once, with the id and source fields mapped through.
        ArgumentCaptor<LogEventEntity> entityCaptor = ArgumentCaptor.forClass(LogEventEntity.class);
        verify(repository, times(1)).save(entityCaptor.capture());
        LogEventEntity saved = entityCaptor.getValue();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getSourceProvider()).isEqualTo("AWS");
        assertThat(saved.getOperation()).isEqualTo("CreateBucket");
        assertThat(saved.getSchemaVersion()).isEqualTo("v1");

        // Published exactly once with the normalized shape.
        verify(publisher, times(1)).publish(partitionKeyCaptor.capture(), normalizedCaptor.capture());
        assertThat(partitionKeyCaptor.getValue()).isEqualTo("dlap-demo");

        Map<String, Object> normalized = normalizedCaptor.getValue();
        assertThat(normalized).containsEntry("eventId", eventId);
        assertThat(normalized).containsEntry("sourceProvider", "AWS");
        assertThat(normalized).containsEntry("operation", "CreateBucket");
        assertThat(normalized).containsEntry("changeType", "CREATE");
        assertThat(normalized).containsEntry("schemaVersion", "v1");
        assertThat(normalized).containsKey("ingestTime");

        @SuppressWarnings("unchecked")
        Map<String, Object> principal = (Map<String, Object>) normalized.get("principal");
        assertThat(principal).containsEntry("id", "AKIAEXAMPLE");
        assertThat(principal).containsEntry("displayName", "alice");

        @SuppressWarnings("unchecked")
        Map<String, Object> targetResource = (Map<String, Object>) normalized.get("targetResource");
        assertThat(targetResource).containsEntry("id", "dlap-demo");
        assertThat(targetResource).containsEntry("region", "us-east-1");
    }

    @Test
    void ingest_skipsPersistAndPublish_whenEventAlreadyStored() {
        // The same event id was stored (and published) by an earlier call, for
        // example an entry re-read inside a source's overlap window. A repeat
        // must not reach Kafka again, or consumers would count the read twice.
        when(repository.existsById(anyString())).thenReturn(true);

        String eventId = service.ingest(request);

        assertThat(eventId).hasSize(64);
        verify(repository, never()).save(any(LogEventEntity.class));
        verify(publisher, never()).publish(anyString(), any());
        assertThat(metrics.counter(LogIngestServiceImpl.METRIC_DEDUP_HITS).count()).isEqualTo(1.0);
    }

    @Test
    void ingest_countsNoDuplicate_forANewEvent() {
        when(repository.existsById(anyString())).thenReturn(false);

        service.ingest(request);

        verify(publisher, times(1)).publish(eq("dlap-demo"), any());
        assertThat(metrics.counter(LogIngestServiceImpl.METRIC_DEDUP_HITS).count()).isZero();
    }

    @Test
    void ingest_isDeterministic_forIdenticalInput() {
        when(repository.existsById(anyString())).thenReturn(false);

        String first = service.ingest(request);
        String second = service.ingest(request);

        // Stable event id per spec - identical input yields identical id.
        assertThat(first).isEqualTo(second);
    }

    @Test
    void ingest_fallsBackToEventIdAsPartitionKey_whenNoTargetResourceId() {
        when(repository.existsById(anyString())).thenReturn(false);
        IngestRequestDTO noTarget = IngestRequestDTO.builder()
                .sourceProvider("GCP")
                .operation("SetIamPolicy")
                .changeType("PERMISSION")
                .eventTime(Instant.parse("2026-06-13T12:00:00Z"))
                .requestId("req-999")
                .build();

        String eventId = service.ingest(noTarget);

        verify(publisher).publish(partitionKeyCaptor.capture(), any());
        // With no target resource id, the event id becomes the partition key.
        assertThat(partitionKeyCaptor.getValue()).isEqualTo(eventId);
    }
}
