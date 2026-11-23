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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.kafka.StubKafka;
import com.dlap.logingest.model.LogEventEntity;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedDetector;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedParser;
import com.dlap.logingest.repository.FormatObservationRepository;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.logingest.service.impl.LogIngestServiceImpl;
import com.dlap.proto.log.v1.DlapLogEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The wire contract of every da-logingest producer of {@code dlap.log.event.normalized}
 * (SPINE-01, log-event.md section 8): whatever a producer sends is, as the broker receives it,
 * the JSON object of one {@code DlapLogEvent} that {@link DlapLogEventCodec#decodeStrict} accepts
 * (not string-wrapped, no unknown field, canonical enum names), with the identity the caller
 * stored. The bytes come from the real publisher and a real {@code StringSerializer}; only the
 * broker and the database are stand-ins.
 *
 * <p>Producers covered here: the legacy pipeline ({@link LogIngestServiceImpl}) and the registry
 * pipeline ({@link RegistryIngestService}, which every parser and transport feeds), each with a
 * GCP, an AWS and an Azure event. The stream parsers' own events go through the same registry path;
 * {@code GcpAuditWireContractTest} runs the whole gcpaudit corpus through it.
 */
class LogEventWireContractTest {

    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);

    private final LogEventRepository repo = mock(LogEventRepository.class);
    private final StubKafka kafka = new StubKafka();

    @BeforeEach
    void emptyStore() {
        when(repo.existsById(anyString())).thenReturn(false);
        when(repo.save(any(LogEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static List<IngestRequestDTO> samples() {
        return List.of(
                IngestRequestDTO.builder().sourceProvider("GCP").sourceService("storage.googleapis.com")
                        .operation("storage.objects.get").changeType("ACCESS").resultStatus("SUCCESS")
                        .eventTime(Instant.parse("2026-10-19T10:15:30Z")).principalId("alice@example.com")
                        .principalType("USER").targetResourceKind("gcs_bucket").targetResourceId("gs://demo-bucket/a.csv")
                        .targetProject("demo-project").requestId("gcp-insert-1")
                        .rawEvent(Map.of("insertId", "gcp-insert-1", "size", 2048)).build(),
                IngestRequestDTO.builder().sourceProvider("AWS").sourceService("s3.amazonaws.com")
                        .operation("PutObject").changeType("UPDATE").resultStatus("SUCCESS")
                        .eventTime(Instant.parse("2026-10-19T10:16:30Z")).principalId("arn:aws:iam::111122223333:role/etl")
                        .principalType("ROLE").principalAccountId("111122223333").targetResourceKind("AWS::S3::Object")
                        .targetResourceId("s3://demo-bucket/b.csv").targetRegion("us-east-1").callerIp("10.0.0.1")
                        .userAgent("aws-cli/2.0").requestId("aws-event-1").traceId("trace-1")
                        .rawEvent(Map.of("eventVersion", "1.11")).build(),
                IngestRequestDTO.builder().sourceProvider("AZURE").sourceService("Microsoft.Storage")
                        .operation("Microsoft.Storage/storageAccounts/blobServices/containers/delete")
                        .changeType("DELETE").resultStatus("FAILURE")
                        .eventTime(Instant.parse("2026-10-19T10:17:30Z")).principalId("sp-1").principalType("SERVICE_ACCOUNT")
                        .targetResourceKind("blob_container")
                        .targetResourceId("https://acct.blob.core.windows.net/data").targetRegion("westeurope")
                        .requestId("azure-event-1").rawEvent(Map.of("category", "Administrative")).build());
    }

    private void assertEveryRecordIsStrictlyCanonical(int expectedRecords) {
        assertThat(kafka.received()).hasSize(expectedRecords);
        for (StubKafka.Wire wire : kafka.received()) {
            assertThat(wire.topic()).isEqualTo(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED);
            assertThat((char) wire.value()[0]).as("a JSON object, not a JSON string holding one").isEqualTo('{');
            DlapLogEvent event = CODEC.decodeStrict(wire.text());
            assertThat(event.getEventId()).matches("[0-9a-f]{64}");
            assertThat(event.getSchemaVersion()).isEqualTo("v1");
            assertThat(event.getSourceProvider().name()).isIn("GCP", "AWS", "AZURE");
            assertThat(event.getChangeType().name()).isIn("ACCESS", "UPDATE", "DELETE");
            assertThat(event.hasEventTime()).isTrue();
            assertThat(event.getTargetResource().getId()).isNotBlank();
            assertThat(wire.key()).as("partition key").isNotBlank();
        }
    }

    @Test
    void theLegacyPipelineSendsStrictlyDecodableObjectsWithTheStoredEventId() {
        LogIngestServiceImpl service = new LogIngestServiceImpl(repo, kafka.publisher(Duration.ofSeconds(2)),
                new SimpleMeterRegistry());
        List<String> ids = new ArrayList<>();

        for (IngestRequestDTO dto : samples()) {
            ids.add(service.ingest(dto));
        }

        assertEveryRecordIsStrictlyCanonical(3);
        assertThat(kafka.received().stream().map(w -> CODEC.decodeStrict(w.text()).getEventId()).toList())
                .containsExactlyElementsOf(ids);
        assertThat(kafka.received().stream().map(StubKafka.Wire::key).toList())
                .containsExactly("gs://demo-bucket/a.csv", "s3://demo-bucket/b.csv",
                        "https://acct.blob.core.windows.net/data");
    }

    @Test
    void theRegistryPipelineSendsStrictlyDecodableObjectsWithTheStoredEventId() {
        EffectiveLogSources sources = new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", BuiltinBindings.all(),
                com.dlap.logingest.config.sources.ValidationReport.CLEAN);
        DefaultParserRegistry registry = new DefaultParserRegistry(
                DlapNormalizedFormat.providers().stream().map(DlapNormalizedParser::new).toList(),
                DlapNormalizedFormat.providers().stream().map(p -> (VersionDetector) new DlapNormalizedDetector(p))
                        .toList());
        StaticListableBeanFactory clocks = new StaticListableBeanFactory();
        clocks.addBean("clock", CLOCK);
        RegistryIngestService service = new RegistryIngestService(registry, new CanonicalEventMapper(CLOCK), sources,
                new LogEventStore(repo, new RegistryIngestServiceTest.NoTransactions()),
                kafka.publisher(Duration.ofSeconds(2)), new StaticListableBeanFactory().getBeanProvider(
                        com.dlap.logingest.service.QuarantineSink.class),
                mock(FormatObservationRepository.class), new SimpleMeterRegistry(), clocks.getBeanProvider(Clock.class));
        List<String> ids = new ArrayList<>();

        for (IngestRequestDTO dto : samples()) {
            ids.add(service.ingestDto(dto, BuiltinBindings.Channel.REST, RegistryIngestService.REST_SOURCE_URI, -1, false)
                    .primaryEventId().orElseThrow());
        }

        assertEveryRecordIsStrictlyCanonical(3);
        assertThat(kafka.received().stream().map(w -> CODEC.decodeStrict(w.text()).getEventId()).toList())
                .containsExactlyElementsOf(ids);
        // The registry pipeline fills the fields the legacy one cannot.
        DlapLogEvent first = CODEC.decodeStrict(kafka.received().get(0).text());
        assertThat(first.getOrigin().name()).isNotEqualTo("ORIGIN_UNSPECIFIED");
        assertThat(first.getEventCategory().name()).isNotEqualTo("EVENT_CATEGORY_UNSPECIFIED");
    }
}
