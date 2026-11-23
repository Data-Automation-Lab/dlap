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

import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.ParserContractConfiguration;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedConfiguration;
import com.dlap.proto.log.v1.SourceProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * Design B2 "Startup validation" in a Spring context: an error fails startup under the
 * {@code prod} profile and is only a warning under {@code dev} (the binding is marked invalid and
 * does not run); {@code config-validation} overrides that; the registry pipeline adds the built-in
 * channels; and {@code /actuator/info} shows the configuration hash.
 */
class LogSourcesStartupTest {

    @Configuration(proxyBeanMethods = false)
    static class AwsFamily {
        @Bean
        TestFamilies.Parser lookupParser() {
            return new TestFamilies.Parser(new EnvelopeKey(TestFamilies.AWS, "lookup-events"),
                    VersionRange.majorMinor("1.0", "1.11"), SourceProvider.AWS);
        }

        @Bean
        TestFamilies.Detector lookupDetector() {
            return new TestFamilies.Detector(new EnvelopeKey(TestFamilies.AWS, "lookup-events"));
        }

        @Bean
        TransportCatalog transports() {
            return TestFamilies.transports();
        }
    }

    private static final String VALID = """
            dlap:
              logingest:
                sources:
                  - id: aws
                    kind: TEST_AWS
                    bindings:
                      - id: acme-prod
                        scope: { account-id: "111111111111" }
                        stream: { transport: LOOKUP_EVENTS }
                        format: { version: "1.08" }
            """;

    private static final String BAD_PIN = VALID.replace("\"1.08\"", "\"2.0\"");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ParserContractConfiguration.class, DlapNormalizedConfiguration.class, AwsFamily.class,
                    LogSourcesConfiguration.class);

    private static ApplicationContextRunner withYaml(ApplicationContextRunner r, String yaml) {
        return r.withInitializer(ctx -> {
            try {
                List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                        .load("bindings-file", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
                sources.forEach(s -> ctx.getEnvironment().getPropertySources().addFirst(s));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Test
    void anErrorFailsStartupUnderTheProdProfile() {
        withYaml(runner, BAD_PIN).withPropertyValues("spring.profiles.active=prod").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PIN_NOT_READ")
                    .hasMessageContaining("acme-prod");
        });
    }

    @Test
    void anErrorIsAWarningUnderDevAndTheBindingDoesNotRun() {
        withYaml(runner, BAD_PIN).withPropertyValues("spring.profiles.active=dev",
                "dlap.logingest.pipeline=registry").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            EffectiveLogSources sources = ctx.getBean(EffectiveLogSources.class);
            EffectiveBinding b = sources.binding("acme-prod").orElseThrow();
            assertThat(b.valid()).isFalse();
            assertThat(b.state(sources.pipeline()).name()).isEqualTo("INVALID");
            assertThat(sources.runningStream("acme-prod", "lookup-events")).isEmpty();
            assertThat(sources.pollableStreams()).isEmpty();
            assertThat(sources.report().errors()).extracting(ConfigProblem::rule).contains(ConfigProblem.Rule.PIN_NOT_READ);
        });
    }

    @Test
    void strictValidationFailsUnderAnyProfileAndWarnNeverFails() {
        withYaml(runner, BAD_PIN).withPropertyValues("spring.profiles.active=dev",
                "dlap.logingest.config-validation=strict").run(ctx -> assertThat(ctx).hasFailed());
        withYaml(runner, BAD_PIN).withPropertyValues("spring.profiles.active=prod",
                "dlap.logingest.config-validation=warn").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void aValidFileStartsUnderProdAndTheInfoEndpointShowsTheHash() {
        withYaml(runner, VALID).withPropertyValues("spring.profiles.active=prod", "dlap.logingest.pipeline=registry")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EffectiveLogSources sources = ctx.getBean(EffectiveLogSources.class);
                    assertThat(sources.binding("acme-prod")).hasValueSatisfying(b -> assertThat(b.valid()).isTrue());
                    assertThat(sources.bindings()).filteredOn(b -> b.origin().name().equals("BUILTIN"))
                            .hasSize(2 * com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat.providers().size());
                    assertThat(sources.runningStream("dlap-rest-aws", "ingest-dto")).isPresent();

                    Map<String, Object> info = new InfoEndpoint(List.of(ctx.getBean(LogSourcesInfoContributor.class)))
                            .info();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> logSources = (Map<String, Object>) info.get("logSources");
                    assertThat(logSources).containsEntry("configHash", sources.configHash())
                            .containsEntry("pipeline", "registry");
                    assertThat(sources.configHash()).matches("[0-9a-f]{64}");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> bindings = (List<Map<String, Object>>) logSources.get("bindings");
                    assertThat(bindings).anySatisfy(b -> {
                        assertThat(b).containsEntry("id", "acme-prod").containsEntry("state", "ACTIVE");
                        assertThat(b.toString()).doesNotContain("111111111111");
                    });
                });
    }

    @Test
    void theLegacyPipelineAddsNoBuiltInBindings() {
        withYaml(runner, VALID).run(ctx -> {
            EffectiveLogSources sources = ctx.getBean(EffectiveLogSources.class);
            assertThat(sources.bindings()).extracting(EffectiveBinding::id).containsExactly("acme-prod");
            assertThat(sources.binding("acme-prod").orElseThrow().state(sources.pipeline()).name()).isEqualTo("INACTIVE");
            assertThat(sources.runningStream("acme-prod", "lookup-events")).isEmpty();
        });
    }

    @Test
    void theHashIsStableForTheSameConfigurationAndChangesWithIt() {
        String[] hashes = new String[3];
        withYaml(runner, VALID).run(ctx -> hashes[0] = ctx.getBean(EffectiveLogSources.class).configHash());
        withYaml(runner, VALID).run(ctx -> hashes[1] = ctx.getBean(EffectiveLogSources.class).configHash());
        withYaml(runner, VALID.replace("\"1.08\"", "\"1.09\"")).run(ctx ->
                hashes[2] = ctx.getBean(EffectiveLogSources.class).configHash());

        assertThat(hashes[1]).isEqualTo(hashes[0]);
        assertThat(hashes[2]).isNotEqualTo(hashes[0]);
    }

    @Test
    void aFileBindingNamedLikeABuiltInIsRefusedWithoutBreakingTheBuiltIn() {
        withYaml(runner, VALID.replace("id: acme-prod", "id: dlap-rest-aws")).withPropertyValues(
                "spring.profiles.active=dev", "dlap.logingest.pipeline=registry").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            EffectiveLogSources sources = ctx.getBean(EffectiveLogSources.class);
            assertThat(sources.report().errors()).extracting(ConfigProblem::rule).contains(ConfigProblem.Rule.RESERVED_ID);
            assertThat(sources.binding("dlap-rest-aws")).hasValueSatisfying(b ->
                    assertThat(b.origin().name()).isEqualTo("BUILTIN"));
        });
    }

    @Test
    void noFileMeansNoConfiguredBindingsAndAStartingContext() {
        runner.withPropertyValues("spring.profiles.active=prod").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EffectiveLogSources.class).bindings()).isEmpty();
        });
    }
}
