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

import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.service.ILogIngestService;
import com.dlap.logingest.service.registry.Disposition;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Offline cross-cloud replay driver. Reads per-cloud normalized-event
 * fixture files ({@code *.json} arrays of {@link IngestRequestDTO}) from
 * a directory and replays them through {@link ILogIngestService#ingest}
 * in-process, so the cross-cloud E2E harness can drive
 * discovery&nbsp;&rarr;&nbsp;catalog&nbsp;&rarr;&nbsp;lifecycle with no
 * cloud credentials and no auth token - it bypasses the REST layer and
 * its {@code @PreAuthorize} gates entirely.
 *
 * <p>Enabled only when {@code dlap.logingest.replay.enabled=true} and
 * {@code dlap.logingest.replay.dir=<path>} is set; runs once on startup.
 * Safe to leave the property off in production - the bean is not
 * instantiated. Set {@code dlap.logingest.replay.provider=<prefix>} to
 * replay only the fixtures for a single cloud (case-insensitive filename
 * prefix, e.g. {@code aws}).
 *
 * <p>Each fixture file is a JSON array of {@link IngestRequestDTO}. The
 * local {@link ObjectMapper} registers {@link JavaTimeModule} so the
 * {@code eventTime} field parses from ISO-8601 into {@link java.time.Instant}.
 *
 * <p>Pipelines (ADR-0060). With {@code dlap.logingest.pipeline=legacy}, the
 * default, every DTO goes to {@link ILogIngestService#ingest} as before. With
 * {@code registry}, every DTO is a {@code DLAP_NORMALIZED_<PROVIDER>} record of
 * the built-in binding {@code dlap-replay-<provider>} in {@code REPLAY} mode
 * (origin {@code ORIGIN_REPLAY}) and takes the parser registry path. The seeder
 * runs on every start, so it replays once: an event already stored is not
 * published again unless {@code dlap.logingest.replay.republish-stored=true},
 * which re-drives the stored fixtures on purpose. Only the DTO path exists; raw
 * provider fixtures are a later package (design B8 step 8.1).
 */
@Component
@Profile({"demo", "dev"})
@ConditionalOnProperty(name = "dlap.logingest.replay.enabled", havingValue = "true")
@Slf4j
public class FixtureReplaySeeder implements ApplicationRunner {

    private final ILogIngestService logIngestService;

    private final Optional<RegistryIngestService> registry;

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Value("${dlap.logingest.replay.dir:}")
    private String replayDir;

    @Value("${dlap.logingest.replay.provider:}")
    private String providerFilter;

    @Value("${dlap.logingest.replay.republish-stored:false}")
    private boolean republishStored;

    /**
     * Creates the seeder.
     *
     * @param logIngestService the legacy ingest pipeline
     * @param registry         the registry ingest path; present only with
     *                         {@code dlap.logingest.pipeline=registry}
     */
    @Autowired
    public FixtureReplaySeeder(ILogIngestService logIngestService, Optional<RegistryIngestService> registry) {
        this.logIngestService = logIngestService;
        this.registry = registry;
    }

    /**
     * Creates a seeder on the legacy pipeline.
     *
     * @param logIngestService the legacy ingest pipeline
     */
    FixtureReplaySeeder(ILogIngestService logIngestService) {
        this(logIngestService, Optional.empty());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (replayDir == null || replayDir.isBlank()) {
            log.warn("FixtureReplaySeeder enabled but dlap.logingest.replay.dir is blank - nothing to replay.");
            return;
        }

        Path dir = Paths.get(replayDir);
        List<Path> files;
        try {
            files = listFixtureFiles(dir);
        } catch (IOException e) {
            log.warn("FixtureReplaySeeder could not list fixtures in {}: {}", replayDir, e.getMessage());
            return;
        }

        boolean filtered = providerFilter != null && !providerFilter.isBlank();
        String prefix = filtered ? providerFilter.toLowerCase(Locale.ROOT) : null;

        log.info("FixtureReplaySeeder replaying from {} ({} file(s)){}",
                replayDir, files.size(),
                filtered ? " filtered to provider prefix '" + providerFilter + "'" : "");

        int grandTotal = 0;
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (filtered && !fileName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                log.debug("FixtureReplaySeeder skipping {} (provider filter '{}')", fileName, providerFilter);
                continue;
            }

            List<IngestRequestDTO> events;
            try {
                events = objectMapper.readValue(file.toFile(),
                        new TypeReference<List<IngestRequestDTO>>() {});
            } catch (IOException e) {
                log.warn("FixtureReplaySeeder could not parse {}: {}", fileName, e.getMessage());
                continue;
            }

            int sent = 0;
            int skipped = 0;
            for (int index = 0; index < events.size(); index++) {
                IngestRequestDTO dto = events.get(index);
                try {
                    if (registry.isEmpty()) {
                        logIngestService.ingest(dto);
                        sent++;
                    } else if (published(registry.get().ingestDto(dto, BuiltinBindings.Channel.FIXTURE_REPLAY,
                            "fixture:" + fileName, index, !republishStored))) {
                        sent++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    log.warn("FixtureReplaySeeder failed to ingest an event from {}: {}",
                            fileName, e.getMessage());
                }
            }
            grandTotal += sent;
            log.info("FixtureReplaySeeder replayed {} event(s) from {}{}", sent, fileName,
                    skipped == 0 ? "" : " (" + skipped + " already stored, quarantined or skipped)");
        }

        log.info("FixtureReplaySeeder finished. Replayed {} event(s) total; watch Kafka topic "
                + "dlap.log.event.normalized + the typed lifecycle topics for the cascade.", grandTotal);
    }

    /**
     * Whether the registry path published the record's primary event.
     *
     * @param report the record's outcome
     * @return true for a published or republished event
     */
    private static boolean published(RecordReport report) {
        return report.primaryDisposition()
                .filter(d -> d == Disposition.PUBLISHED || d == Disposition.REPUBLISHED)
                .isPresent() && !report.quarantined();
    }

    /**
     * List {@code *.json} fixture files in the directory, sorted by name
     * for deterministic replay order.
     *
     * @param dir directory to scan.
     * @return sorted list of {@code .json} files; empty if the directory
     *     is missing or not a directory.
     * @throws IOException if the directory listing fails.
     */
    private List<Path> listFixtureFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            log.warn("FixtureReplaySeeder replay dir {} is not a directory - nothing to replay.", dir);
            return new ArrayList<>();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }
}
