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

package com.dlap.logingest.parse.gcpaudit;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.LogSourceConfigValidator;
import com.dlap.logingest.config.sources.LogSourcesProperties;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.logingest.providers.gcp.logging.GcpTransportsForTests;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * Per-binding version pins (GCP-01 acceptance; coverage spec variant {@code bq1.binding-pinned-v1}):
 * one project pinned to BigQuery v1, another to v2, through the real bindings validator, the real
 * gcpaudit registry and the real GCP transports' requirements. Each binding parses its own version and
 * applies its {@code on-mismatch: QUARANTINE} to the other; the example file
 * {@code deployment/examples/log-sources/gcpaudit.yml} validates cleanly.
 */
class GcpAuditPinnedBindingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TWO_PROJECTS = """
            dlap:
              logingest:
                config-mode: FILE
                sources:
                  - id: gcp
                    kind: GCP_CLOUD_AUDIT
                    bindings:
                      - id: legacy-project
                        scope: { project-id: proj-0d1a0001 }
                        stream:
                          transport: BQ_SINK
                          location: { dataset: audit_sink }
                        format: { envelope: bq-sink-row, version: bq-auditdata-legacy, on-mismatch: QUARANTINE }
                      - id: current-project
                        scope: { project-id: proj-0d1a0002 }
                        stream:
                          transport: BQ_SINK
                          location: { dataset: audit_sink }
                        format: { envelope: bq-sink-row, version: bq-auditmetadata, on-mismatch: QUARANTINE }
            """;

    @Test
    void oneProjectPinnedToV1AndAnotherToV2EachParseTheirOwnVersionAndQuarantineTheOther() {
        LogSourceConfigValidator.Result r = validate(TWO_PROJECTS);
        assertThat(r.report().errors()).isEmpty();
        BindingConfig legacy = stream(r, "legacy-project").parse();
        BindingConfig current = stream(r, "current-project").parse();
        assertThat(legacy.pinnedVersion()).contains(GcpAuditFormat.BQ_AUDIT_DATA_LEGACY);
        assertThat(legacy.onMismatch()).isEqualTo(OnMismatch.QUARANTINE);
        assertThat(current.pinnedVersion()).contains(GcpAuditFormat.BQ_AUDIT_METADATA);

        DefaultParserRegistry registry = GcpAuditTestSupport.registry();
        JsonNode v1 = firstRow("gcpaudit/bq-sink-row/bq-auditdata-legacy/job-completed-query");
        JsonNode v2 = firstRow("gcpaudit/bq-sink-row/bq-auditmetadata/table-reads");

        assertThat(parse(registry, legacy, v1).outcomeCode()).isEqualTo("PARSED");
        assertThat(parse(registry, legacy, v2).outcomeCode()).isEqualTo("QUARANTINED:VERSION_PIN_MISMATCH");
        assertThat(parse(registry, current, v2).outcomeCode()).isEqualTo("PARSED");
        assertThat(parse(registry, current, v1).outcomeCode()).isEqualTo("QUARANTINED:VERSION_PIN_MISMATCH");
    }

    @Test
    void withoutAPinBothVersionsParseOnOneBinding() {
        BindingConfig auto = GcpAuditTestSupport.mapping().defaultBinding("auto-project");
        DefaultParserRegistry registry = GcpAuditTestSupport.registry();
        ParseOutcome v1 = parse(registry, auto, firstRow("gcpaudit/bq-sink-row/bq-auditdata-legacy/job-completed-query"));
        ParseOutcome v2 = parse(registry, auto, firstRow("gcpaudit/bq-sink-row/bq-auditmetadata/table-reads"));
        assertThat(v1.outcomeCode()).isEqualTo("PARSED");
        assertThat(v1.detected().orElseThrow().schemaVersion()).isEqualTo(GcpAuditFormat.BQ_AUDIT_DATA_LEGACY);
        assertThat(v2.outcomeCode()).isEqualTo("PARSED");
        assertThat(v2.detected().orElseThrow().schemaVersion()).isEqualTo(GcpAuditFormat.BQ_AUDIT_METADATA);
    }

    @Test
    void theExampleBindingsFileValidatesWithTheGcpFamilyAndTransports() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("deployment/examples/log-sources/gcpaudit.yml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("deployment/examples/log-sources/gcpaudit.yml above the working directory").isNotNull();
        String yaml = Files.readString(dir.resolve("deployment/examples/log-sources/gcpaudit.yml"), StandardCharsets.UTF_8);
        LogSourceConfigValidator.Result r = validate(yaml);
        assertThat(r.report().errors()).isEmpty();
        assertThat(r.report().warnings()).isEmpty();
        assertThat(r.bindings()).extracting(EffectiveBinding::id)
                .containsExactly("acme-gcp-legacy-bq", "acme-gcp-analytics", "acme-gcp-lake");
        assertThat(stream(r, "acme-gcp-legacy-bq").parse().pinnedVersion()).contains(GcpAuditFormat.BQ_AUDIT_DATA_LEGACY);
        assertThat(stream(r, "acme-gcp-analytics").parse().pinnedVersion()).contains(GcpAuditFormat.BQ_AUDIT_METADATA);
        assertThat(stream(r, "acme-gcp-lake").parse().pinnedVersion()).isEmpty();
        assertThat(stream(r, "acme-gcp-lake").transport()).isEqualTo("GCS_SINK");
    }

    @Test
    void aProfileNameIsReadButNeverDetected() {
        BindingConfig pinnedProfile = GcpAuditTestSupport.mapping().defaultBinding("profile-pin")
                .pinned(GcpAuditFormat.AUDITLOG_2025, OnMismatch.QUARANTINE);
        DefaultParserRegistry registry = GcpAuditTestSupport.registry();
        JsonNode gcs = firstRow("gcpaudit/bq-sink-row/auditlog-2025/gcs-objects");
        assertThat(registry.rangeFor(GcpAuditFormat.key(GcpAuditFormat.BQ_SINK_ROW), GcpAuditFormat.AUDITLOG_2025))
                .isPresent();
        assertThat(parse(registry, pinnedProfile, gcs).outcomeCode()).isEqualTo("QUARANTINED:VERSION_PIN_MISMATCH");
        BindingConfig pinnedAuditlog = GcpAuditTestSupport.mapping().defaultBinding("auditlog-pin")
                .pinned(GcpAuditFormat.AUDITLOG, OnMismatch.QUARANTINE);
        assertThat(parse(registry, pinnedAuditlog, gcs).outcomeCode()).isEqualTo("PARSED");
    }

    private static LogSourceConfigValidator.Result validate(String yaml) {
        DefaultParserRegistry registry = GcpAuditTestSupport.registry();
        LogSourceConfigValidator.Context ctx = new LogSourceConfigValidator.Context(registry,
                Map.of(GcpAuditFormat.SOURCE_KIND, SourceProvider.GCP),
                new MappingSpecs(List.of(GcpAuditTestSupport.mapping())), GcpTransportsForTests.catalog(),
                PipelineMode.REGISTRY, List.of(),
                name -> "ACME_GCP_AUDIT_READER_CREDENTIALS".equals(name) ? "/etc/dlap/gcp-reader.json" : null,
                registry.describe());
        return new LogSourceConfigValidator(ctx).validate(bind(yaml));
    }

    private static EffectiveStream stream(LogSourceConfigValidator.Result r, String binding) {
        return r.bindings().stream().filter(b -> b.id().equals(binding)).findFirst().orElseThrow().streams().get(0);
    }

    private static ParseOutcome parse(DefaultParserRegistry registry, BindingConfig binding, JsonNode row) {
        RawRecord record = RawRecord.builder().bindingId(binding.bindingId()).sourceKind(GcpAuditFormat.SOURCE_KIND)
                .envelope(GcpAuditFormat.BQ_SINK_ROW).ref(RawRef.of("TEST", "test:row", 0)).mode(IngestMode.STREAM)
                .fetchedAt(Instant.parse("2030-01-01T00:00:00Z")).json(row).build();
        return registry.parse(record, binding);
    }

    private static JsonNode firstRow(String caseId) {
        FixtureCase c = FixtureCatalog.fromClasspath().cases(GcpAuditFormat.FAMILY).stream()
                .filter(x -> x.id().equals(caseId)).findFirst().orElseThrow();
        try {
            return JSON.readTree(c.inputText().lines().findFirst().orElseThrow());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static LogSourcesProperties bind(String yaml) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("bindings", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
            return new Binder(ConfigurationPropertySources.from(sources))
                    .bind(LogSourcesProperties.PREFIX, LogSourcesProperties.class)
                    .orElseGet(LogSourcesProperties::new);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
