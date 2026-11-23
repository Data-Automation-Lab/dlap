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

package com.dlap.logingest.config.sources;

import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.LogRecordParser;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * Small invented families, transports and helpers for the bindings tests. Test data only.
 */
final class TestFamilies {

    static final String AWS = "TEST_AWS";
    static final String GCP = "TEST_GCP";
    static final String AZURE = "TEST_AZURE";
    static final String DBX = "TEST_DBX";

    private TestFamilies() {
    }

    /** Detects the version at {@code /v}. */
    record Detector(EnvelopeKey envelopeKey) implements VersionDetector {
        @Override
        public Optional<FormatKey> detect(RawRecord record) {
            var v = record.body().path("v");
            return v.isTextual() ? Optional.of(envelopeKey.at(v.asText())) : Optional.empty();
        }
    }

    /** Parses everything into one read event keyed by {@code /id}. */
    record Parser(EnvelopeKey envelopeKey, VersionRange supports, SourceProvider sourceProvider) implements LogRecordParser {
        @Override
        public String parserId() {
            return "test-" + envelopeKey.envelope();
        }

        @Override
        public String parserSemver() {
            return "1.0.0";
        }

        @Override
        public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
            return ParseResult.Parsed.of(CanonicalEvent.builder()
                    .nativeKey(record.body().path("id").asText("k"))
                    .provider(sourceProvider)
                    .service("svc")
                    .operation(record.body().path("op").asText("GetObject"))
                    .changeType(ChangeType.ACCESS)
                    .category(EventCategory.EVENT_CATEGORY_DATA_READ)
                    .result(ResultStatus.SUCCESS)
                    .eventTime(Instant.parse("2026-10-01T00:00:00Z"))
                    .build());
        }
    }

    static List<LogRecordParser> parsers() {
        List<LogRecordParser> out = new ArrayList<>();
        out.add(new Parser(new EnvelopeKey(AWS, "s3-file"), VersionRange.majorMinor("1.0", "1.11"), SourceProvider.AWS));
        out.add(new Parser(new EnvelopeKey(AWS, "lookup-events"), VersionRange.majorMinor("1.0", "1.11"),
                SourceProvider.AWS));
        out.add(new Parser(new EnvelopeKey(GCP, "api-json"), VersionRange.named("auditlog", "bq-auditmetadata"),
                SourceProvider.GCP));
        out.add(new Parser(new EnvelopeKey(AZURE, "rest"), VersionRange.majorMinor("1.0", "1.0"), SourceProvider.AZURE));
        out.add(new Parser(new EnvelopeKey(DBX, "system-table-row"), VersionRange.majorMinor("2.0", "2.0"),
                SourceProvider.DATABRICKS));
        return out;
    }

    static DefaultParserRegistry registry() {
        List<LogRecordParser> parsers = parsers();
        List<VersionDetector> detectors = parsers.stream().map(p -> (VersionDetector) new Detector(p.envelopeKey()))
                .toList();
        return new DefaultParserRegistry(parsers, detectors);
    }

    static Map<String, SourceProvider> providers() {
        Map<String, SourceProvider> m = new TreeMap<>();
        parsers().forEach(p -> m.put(p.envelopeKey().sourceKind(), p.sourceProvider()));
        return m;
    }

    static TransportCatalog transports() {
        Map<String, TransportCatalog.Requirements> m = Map.of(
                "S3_TRAIL_FILES", new TransportCatalog.Requirements(Set.of("bucket"), Set.of("prefix")),
                "LOOKUP_EVENTS", new TransportCatalog.Requirements(Set.of(), Set.of()),
                "LOGGING_API", new TransportCatalog.Requirements(Set.of(), Set.of("filter")),
                "ACTIVITY_REST", new TransportCatalog.Requirements(Set.of(), Set.of()),
                "SYSTEM_TABLES", new TransportCatalog.Requirements(Set.of("warehouse"), Set.of()));
        return id -> Optional.ofNullable(m.get(id));
    }

    static LogSourceConfigValidator.Context context(PipelineMode pipeline, List<String> legacy,
                                                    UnaryOperator<String> env) {
        DefaultParserRegistry registry = registry();
        return new LogSourceConfigValidator.Context(registry, providers(), MappingSpecs.empty(), transports(),
                pipeline, legacy, env, registry.describe());
    }

    static LogSourceConfigValidator.Result validate(String yaml) {
        return validate(yaml, PipelineMode.REGISTRY);
    }

    static LogSourceConfigValidator.Result validate(String yaml, PipelineMode pipeline) {
        return new LogSourceConfigValidator(context(pipeline, List.of(), name -> "AWS_ROLE".equals(name) ? "set" : null))
                .validate(bind(yaml));
    }

    /** Binds YAML the way Spring Boot binds the bindings file. */
    static LogSourcesProperties bind(String yaml) {
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
