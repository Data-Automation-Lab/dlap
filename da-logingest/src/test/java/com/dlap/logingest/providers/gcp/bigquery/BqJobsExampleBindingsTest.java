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

package com.dlap.logingest.providers.gcp.bigquery;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.LogSourceConfigValidator;
import com.dlap.logingest.config.sources.LogSourcesProperties;
import com.dlap.logingest.config.sources.TransportCatalog;
import com.dlap.logingest.parse.bqjobs.BqJobsFormat;
import com.dlap.logingest.parse.bqjobs.BqJobsParser;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.proto.log.v1.SourceProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * {@code deployment/examples/log-sources/bqjobs.example.yaml} says what it does: with the JOBS family
 * and its transport registered it validates cleanly and resolves the settings its comments promise.
 */
class BqJobsExampleBindingsTest {

    private static final MappingSpec MAPPING = AbstractParserContractTest.classpathMapping(BqJobsFormat.FAMILY);

    private static String example() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("deployment/examples/log-sources/bqjobs.example.yaml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("deployment/examples/log-sources/bqjobs.example.yaml above the working directory").isNotNull();
        return Files.readString(dir.resolve("deployment/examples/log-sources/bqjobs.example.yaml"));
    }

    private static LogSourcesProperties bind(String yaml) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("bindings", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind(LogSourcesProperties.PREFIX, LogSourcesProperties.class).orElseGet(LogSourcesProperties::new);
    }

    private static LogSourceConfigValidator.Result validate(String yaml, boolean transportRegistered) throws IOException {
        DefaultParserRegistry registry = AbstractParserContractTest.registryOf(MAPPING, List.of(new BqJobsParser(MAPPING)));
        var descriptor = new BqJobsViewTransport(new FakeJobsViewGateway(), id -> Optional.empty()).descriptor();
        TransportCatalog transports = transportRegistered
                ? id -> id.equals(descriptor.transportId())
                        ? Optional.of(new TransportCatalog.Requirements(descriptor.requiredLocation(), descriptor.optionalLocation()))
                        : Optional.empty()
                : TransportCatalog.none();
        return new LogSourceConfigValidator(new LogSourceConfigValidator.Context(registry,
                Map.of(BqJobsFormat.SOURCE_KIND, SourceProvider.GCP), new MappingSpecs(List.of(MAPPING)), transports,
                PipelineMode.REGISTRY, List.of(), name -> null, registry.describe())).validate(bind(yaml));
    }

    @Test
    void theExampleValidatesCleanlyAndResolvesTheFamilyDefaults() throws IOException {
        LogSourceConfigValidator.Result r = validate(example(), true);

        assertThat(r.report().errors()).isEmpty();
        assertThat(r.report().warnings()).isEmpty();
        assertThat(r.bindings()).extracting(EffectiveBinding::id).containsExactly("acme-gcp-jobs-p1");
        EffectiveBinding binding = r.bindings().get(0);
        assertThat(binding.valid()).isTrue();
        assertThat(binding.sourceKind()).isEqualTo("GCP_BQ_JOBS_VIEW");
        assertThat(binding.scope()).containsEntry("project-id", "acme-prod-p1");
        var stream = binding.streams().get(0);
        assertThat(stream.transport()).isEqualTo("BQ_JOBS_VIEW");
        assertThat(stream.envelope()).isEqualTo("jobs-view-row");
        assertThat(stream.location()).containsEntry("location", "US").containsEntry("billing-project", "acme-billing");
        assertThat(stream.overlap()).as("from the family mapping").isEqualTo(Duration.ofMinutes(30));
        assertThat(stream.pollInterval()).as("from the family mapping").isEqualTo(Duration.ofMinutes(5));
        assertThat(stream.backfillAllowed()).isTrue();
        assertThat(stream.parse().maxRecordBytes()).isEqualTo(262144L);
        assertThat(stream.parse().pinnedVersion()).isEmpty();
    }

    @Test
    void aStreamWithoutALocationIsRefused() throws IOException {
        String withoutLocation = example().replace("location: US\n", "").replace("billing-project: acme-billing\n", "x: y\n");

        LogSourceConfigValidator.Result r = validate(withoutLocation, true);

        assertThat(r.report().rules()).contains(com.dlap.logingest.config.sources.ConfigProblem.Rule.MISSING_LOCATION);
    }

    @Test
    void theTransportIsTheOneThingTheExampleNeedsBeyondTheParser() throws IOException {
        LogSourceConfigValidator.Result r = validate(example(), false);

        assertThat(r.report().rules()).contains(com.dlap.logingest.config.sources.ConfigProblem.Rule.UNKNOWN_TRANSPORT);
    }
}
