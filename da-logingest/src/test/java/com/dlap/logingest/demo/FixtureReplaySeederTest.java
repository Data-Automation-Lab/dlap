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

package com.dlap.logingest.demo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.service.ILogIngestService;
import com.dlap.logingest.service.registry.Disposition;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link FixtureReplaySeeder} - the offline cross-cloud
 * replay driver. The {@link ILogIngestService} is mocked so the assertions
 * focus on the replay mechanics: every event in every matching fixture file
 * reaches {@code ingest}, and the provider filter narrows replay to a single
 * cloud's fixtures. On the registry pipeline every DTO takes the fixture-replay
 * channel instead, once per start unless republishing is asked for.
 */
@ExtendWith(MockitoExtension.class)
class FixtureReplaySeederTest {

    @Mock
    private ILogIngestService logIngestService;

    @Mock
    private RegistryIngestService registry;

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void replaysEveryEventInFixtureFile(@TempDir Path dir) throws Exception {
        writeFixture(dir.resolve("aws-events.json"), List.of(
                event("AWS", "CreateBucket"),
                event("AWS", "PutObject"),
                event("AWS", "DeleteBucket")));

        FixtureReplaySeeder seeder = new FixtureReplaySeeder(logIngestService);
        ReflectionTestUtils.setField(seeder, "replayDir", dir.toString());
        ReflectionTestUtils.setField(seeder, "providerFilter", "");

        seeder.run(new DefaultApplicationArguments());

        verify(logIngestService, times(3)).ingest(any());
    }

    @Test
    void aBlankReplayDirReplaysNothing() {
        FixtureReplaySeeder seeder = new FixtureReplaySeeder(logIngestService);
        ReflectionTestUtils.setField(seeder, "replayDir", " ");
        ReflectionTestUtils.setField(seeder, "providerFilter", "");

        seeder.run(new DefaultApplicationArguments());

        verify(logIngestService, never()).ingest(any());
    }

    @Test
    void aReplayDirThatIsNotADirectoryReplaysNothing(@TempDir Path dir) throws Exception {
        Path notADirectory = dir.resolve("plain-file.json");
        java.nio.file.Files.writeString(notADirectory, "[]");

        FixtureReplaySeeder seeder = new FixtureReplaySeeder(logIngestService);
        ReflectionTestUtils.setField(seeder, "replayDir", notADirectory.toString());
        ReflectionTestUtils.setField(seeder, "providerFilter", "");

        seeder.run(new DefaultApplicationArguments());

        verify(logIngestService, never()).ingest(any());
    }

    @Test
    void providerFilterReplaysOnlyMatchingFixtures(@TempDir Path dir) throws Exception {
        writeFixture(dir.resolve("aws-x.json"), List.of(event("AWS", "CreateBucket")));
        writeFixture(dir.resolve("gcp-y.json"), List.of(event("GCP", "storage.buckets.create")));

        FixtureReplaySeeder seeder = new FixtureReplaySeeder(logIngestService);
        ReflectionTestUtils.setField(seeder, "replayDir", dir.toString());
        ReflectionTestUtils.setField(seeder, "providerFilter", "aws");

        seeder.run(new DefaultApplicationArguments());

        verify(logIngestService, times(1)).ingest(any());
    }

    @Test
    void onTheRegistryPipelineEveryDtoTakesTheReplayChannelOnce(@TempDir Path dir) throws Exception {
        writeFixture(dir.resolve("aws-events.json"), List.of(event("AWS", "CreateBucket"), event("AWS", "PutObject")));
        when(registry.ingestDto(any(), eq(BuiltinBindings.Channel.FIXTURE_REPLAY), anyString(), anyLong(),
                anyBoolean())).thenReturn(new RecordReport("PARSED_WITH_WARNINGS",
                List.of(new RecordReport.EventReport("id", Disposition.PUBLISHED)), Optional.empty()));
        FixtureReplaySeeder seeder = new FixtureReplaySeeder(logIngestService, Optional.of(registry));
        ReflectionTestUtils.setField(seeder, "replayDir", dir.toString());
        ReflectionTestUtils.setField(seeder, "providerFilter", "");

        seeder.run(new DefaultApplicationArguments());

        verify(registry).ingestDto(any(), eq(BuiltinBindings.Channel.FIXTURE_REPLAY), eq("fixture:aws-events.json"),
                eq(0L), eq(true));
        verify(registry).ingestDto(any(), eq(BuiltinBindings.Channel.FIXTURE_REPLAY), eq("fixture:aws-events.json"),
                eq(1L), eq(true));
        verify(logIngestService, never()).ingest(any());
    }

    @Test
    void republishStoredReDrivesStoredFixturesOnPurpose(@TempDir Path dir) throws Exception {
        writeFixture(dir.resolve("gcp-events.json"), List.of(event("GCP", "storage.objects.get")));
        when(registry.ingestDto(any(), eq(BuiltinBindings.Channel.FIXTURE_REPLAY), anyString(), anyLong(),
                anyBoolean())).thenReturn(new RecordReport("PARSED_WITH_WARNINGS",
                List.of(new RecordReport.EventReport("id", Disposition.REPUBLISHED)), Optional.empty()));
        FixtureReplaySeeder seeder = new FixtureReplaySeeder(logIngestService, Optional.of(registry));
        ReflectionTestUtils.setField(seeder, "replayDir", dir.toString());
        ReflectionTestUtils.setField(seeder, "providerFilter", "");
        ReflectionTestUtils.setField(seeder, "republishStored", true);

        seeder.run(new DefaultApplicationArguments());

        verify(registry).ingestDto(any(), eq(BuiltinBindings.Channel.FIXTURE_REPLAY), eq("fixture:gcp-events.json"),
                eq(0L), eq(false));
    }

    private void writeFixture(Path file, List<IngestRequestDTO> events) throws Exception {
        Files.writeString(file, objectMapper.writeValueAsString(events));
    }

    private IngestRequestDTO event(String provider, String operation) {
        return IngestRequestDTO.builder()
                .sourceProvider(provider)
                .operation(operation)
                .changeType("CREATE")
                .resultStatus("SUCCESS")
                .eventTime(Instant.parse("2026-07-05T10:00:00Z"))
                .principalId("tester")
                .targetResourceId("resource-" + operation)
                .requestId("req-" + operation)
                .rawEvent(Map.of("synthetic", true))
                .build();
    }
}
