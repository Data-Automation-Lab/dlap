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

package com.dlap.logingest.parse.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Self-registration and the pipeline flag: parser and detector beans join the registry with no
 * central list, the shipped classpath holds no family yet, {@code dlap.logingest.pipeline}
 * defaults to legacy, and the pipeline conditions follow the flag.
 */
class ParserContractConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ParserContractConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    static class FamilyBeans {
        @Bean
        Stubs.Parser stubParser() {
            return new Stubs.Parser();
        }

        @Bean
        Stubs.Detector stubDetector() {
            return new Stubs.Detector();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondMajor {
        @Bean
        Stubs.Parser stubParserTwo() {
            return new Stubs.Parser().id("stub-two").range(VersionRange.majorMinor("2.0", "2.2"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Gated {
        @Bean
        @ConditionalOnLegacyPipeline
        String legacyMarker() {
            return "legacy";
        }

        @Bean
        @ConditionalOnRegistryPipeline
        Integer registryMarker() {
            return 1;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void theRegistryCollectsParserAndDetectorBeans() {
        runner.withUserConfiguration(FamilyBeans.class, SecondMajor.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            DefaultParserRegistry registry = ctx.getBean(DefaultParserRegistry.class);
            assertThat(registry.envelopes()).contains(Stubs.KEY);
            assertThat(registry.describe().get(Stubs.KEY)).hasSize(2);
            ParseOutcome o = registry.parse(Stubs.record("{\"v\":\"2.1\",\"id\":\"x\"}"), Stubs.binding());
            assertThat(o.parserVersion()).contains("stub-two@1.0.0");
        });
    }

    @Test
    void anAmbiguousSetOfBeansFailsStartup() {
        runner.withUserConfiguration(FamilyBeans.class)
                .withBean("duplicate", Stubs.Parser.class, () -> new Stubs.Parser().id("dup"))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void everyShippedFamilyMappingLoadsAndGetsADetector() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            MappingSpecs specs = ctx.getBean(MappingSpecs.class);
            java.util.Set<EnvelopeKey> expected = new java.util.HashSet<>();
            specs.all().forEach(s -> s.envelopes().forEach(e -> expected.add(s.keyOf(e))));
            assertThat(ctx.getBean(DefaultParserRegistry.class).envelopes()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(ctx).hasSingleBean(CanonicalEventMapper.class);
        });
    }

    @Test
    void thePipelineDefaultsToLegacy() {
        runner.withUserConfiguration(Gated.class).run(ctx -> {
            assertThat(ctx.getBean(PipelineMode.class)).isEqualTo(PipelineMode.LEGACY);
            assertThat(ctx).hasBean("legacyMarker").doesNotHaveBean("registryMarker");
        });
    }

    @Test
    void registryModeSwitchesTheConditions() {
        runner.withUserConfiguration(Gated.class).withPropertyValues(PipelineMode.PROPERTY + "=registry").run(ctx -> {
            assertThat(ctx.getBean(PipelineMode.class)).isEqualTo(PipelineMode.REGISTRY);
            assertThat(ctx).hasBean("registryMarker").doesNotHaveBean("legacyMarker");
        });
    }

    @Test
    void legacyIsAcceptedInAnyCase() {
        runner.withUserConfiguration(Gated.class).withPropertyValues(PipelineMode.PROPERTY + "=LEGACY").run(ctx -> {
            assertThat(ctx.getBean(PipelineMode.class)).isEqualTo(PipelineMode.LEGACY);
            assertThat(ctx).hasBean("legacyMarker");
        });
    }

    @Test
    void anUnknownPipelineFailsStartup() {
        runner.withPropertyValues(PipelineMode.PROPERTY + "=both").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void theMapperUsesAnApplicationClockWhenThereIsOne() {
        runner.withUserConfiguration(FamilyBeans.class, FixedClock.class).run(ctx -> {
            CanonicalEventMapper mapper = ctx.getBean(CanonicalEventMapper.class);
            ParseOutcome o = ctx.getBean(DefaultParserRegistry.class)
                    .parse(Stubs.record("{\"v\":\"1.0\",\"id\":\"x\"}"), Stubs.binding());
            assertThat(mapper.events(o).get(0).getIngestTime().getSeconds())
                    .isEqualTo(Instant.parse("2031-01-01T00:00:00Z").getEpochSecond());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoClocks {
        @Bean
        Clock firstClock() {
            return Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        Clock secondClock() {
            return Clock.fixed(Instant.parse("2032-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void twoClockBeansDoNotFailStartupAndTheMapperFallsBackToTheSystemClock() {
        runner.withUserConfiguration(FamilyBeans.class, TwoClocks.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ParseOutcome o = ctx.getBean(DefaultParserRegistry.class)
                    .parse(Stubs.record("{\"v\":\"1.0\",\"id\":\"x\"}"), Stubs.binding());
            long ingest = ctx.getBean(CanonicalEventMapper.class).events(o).get(0).getIngestTime().getSeconds();
            assertThat(ingest).isNotIn(Instant.parse("2031-01-01T00:00:00Z").getEpochSecond(),
                    Instant.parse("2032-01-01T00:00:00Z").getEpochSecond());
        });
    }

    @Test
    void parseRejectsValuesTheConditionsWouldNotMatch() {
        assertThat(PipelineMode.parse(null)).isEqualTo(PipelineMode.LEGACY);
        assertThat(PipelineMode.parse("Registry")).isEqualTo(PipelineMode.REGISTRY);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PipelineMode.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PipelineMode.parse(" legacy"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
