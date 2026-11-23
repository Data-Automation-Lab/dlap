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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.LogRecordParser;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.proto.log.v1.SourceProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The example in {@code deployment/examples/log-sources/README.md} says what it does: with the
 * CloudTrail and Cloud Audit families and their transports registered it validates cleanly and
 * resolves the mixed versions it describes; in today's build, which has neither, its bindings are
 * {@code UNKNOWN_SOURCE_KIND} and {@code UNKNOWN_TRANSPORT}. The families here are stand-ins.
 */
class ExampleBindingsFileTest {

    private static String exampleYaml() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("deployment/examples/log-sources/README.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("deployment/examples/log-sources/README.md above the working directory").isNotNull();
        String md = Files.readString(dir.resolve("deployment/examples/log-sources/README.md"));
        Matcher m = Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL).matcher(md.replace("\r\n", "\n"));
        assertThat(m.find()).as("a yaml block in the example").isTrue();
        return m.group(1);
    }

    private static LogSourceConfigValidator.Context withFamilies() {
        List<LogRecordParser> parsers = List.of(
                new TestFamilies.Parser(new EnvelopeKey("AWS_CLOUDTRAIL", "s3-file"),
                        VersionRange.majorMinor("1.0", "1.11"), SourceProvider.AWS),
                new TestFamilies.Parser(new EnvelopeKey("AWS_CLOUDTRAIL", "lookup-events"),
                        VersionRange.majorMinor("1.0", "1.11"), SourceProvider.AWS),
                new TestFamilies.Parser(new EnvelopeKey("GCP_CLOUD_AUDIT", "bq-sink-row"),
                        VersionRange.named("auditlog", "bq-auditmetadata", "bq-auditdata-legacy"), SourceProvider.GCP),
                new TestFamilies.Parser(new EnvelopeKey("GCP_CLOUD_AUDIT", "api-proto"),
                        VersionRange.named("auditlog", "bq-auditmetadata", "bq-auditdata-legacy"), SourceProvider.GCP));
        DefaultParserRegistry registry = new DefaultParserRegistry(parsers,
                parsers.stream().map(p -> (VersionDetector) new TestFamilies.Detector(p.envelopeKey())).toList());
        Map<String, TransportCatalog.Requirements> transports = Map.of(
                "S3_TRAIL_FILES", new TransportCatalog.Requirements(Set.of("bucket"), Set.of("prefix")),
                "LOOKUP_EVENTS", new TransportCatalog.Requirements(Set.of(), Set.of()),
                "BQ_SINK", new TransportCatalog.Requirements(Set.of("dataset"), Set.of("table")),
                "LOGGING_API", new TransportCatalog.Requirements(Set.of(), Set.of("filter")));
        return new LogSourceConfigValidator.Context(registry,
                Map.of("AWS_CLOUDTRAIL", SourceProvider.AWS, "GCP_CLOUD_AUDIT", SourceProvider.GCP),
                MappingSpecs.empty(), id -> Optional.ofNullable(transports.get(id)), PipelineMode.REGISTRY, List.of(),
                name -> "ACME_TRAIL_READER_ROLE_ARN".equals(name) ? "arn:aws:iam::111111111111:role/reader" : null,
                registry.describe());
    }

    @Test
    void theExampleValidatesOnceItsFamiliesExist() throws IOException {
        LogSourceConfigValidator.Result r = new LogSourceConfigValidator(withFamilies())
                .validate(TestFamilies.bind(exampleYaml()));

        assertThat(r.report().errors()).isEmpty();
        assertThat(r.bindings()).extracting(EffectiveBinding::id).containsExactly("acme-aws-111111111111",
                "acme-aws-222222222222", "acme-aws-333333333333", "acme-gcp-p1", "acme-gcp-p2");
        EffectiveBinding pinned = r.bindings().get(1);
        assertThat(pinned.streams().get(0).parse().pinnedVersion()).contains("1.08");
        assertThat(pinned.streams().get(0).parse().onMismatch().name()).isEqualTo("QUARANTINE");
        assertThat(r.bindings().get(0).streams().get(0).parse().pinnedVersion()).isEmpty();
        assertThat(r.bindings().get(0).streams().get(0).overlap()).hasMinutes(20);
        assertThat(r.bindings().get(2).enabled()).isFalse();
        assertThat(r.bindings().get(3).streams().get(0).parse().pinnedVersion()).contains("bq-auditdata-legacy");
        assertThat(r.bindings().get(4).streams().get(0).filters().excludeServices())
                .containsExactly("logging.googleapis.com");
    }

    @Test
    void inTodaysBuildTheExampleNamesUnknownFamiliesAndTransports() throws IOException {
        LogSourceConfigValidator.Result r = TestFamilies.validate(exampleYaml());

        assertThat(r.report().rules()).contains(ConfigProblem.Rule.UNKNOWN_SOURCE_KIND,
                ConfigProblem.Rule.UNKNOWN_TRANSPORT);
        assertThat(r.bindings()).allSatisfy(b -> assertThat(b.valid()).isFalse());
    }
}
