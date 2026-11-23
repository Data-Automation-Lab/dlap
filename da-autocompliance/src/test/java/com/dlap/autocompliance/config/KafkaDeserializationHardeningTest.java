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
package com.dlap.autocompliance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * SEC-03 acceptance for da-autocompliance: no Kafka consumer of the service trusts every package, and a
 * record cannot choose the class the service builds. The test uses the service's real
 * {@code application.properties} and its real Kafka configuration (no broker is needed).
 *
 * <p>The hostile record names {@link Gadget} in its {@code __TypeId__} header. Building that class
 * would be the first step of a deserialization attack, so the test counts constructions. The control
 * test shows that the same record does build the class under the old setting ({@code trusted
 * packages = *}, headers on), so a pass here is not a vacuous pass.
 */
@SuppressWarnings({"removal", "rawtypes", "unchecked"})
class KafkaDeserializationHardeningTest {

    private static final String TYPE_ID_HEADER = "__TypeId__";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(KafkaReliabilityConfiguration.class);

    /** A class an attacker would like the consumer to build. Public, with a no-argument constructor. */
    public static class Gadget {

        static int constructions;

        private int x;

        public Gadget() {
            constructions++;
        }

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }
    }

    @BeforeEach
    void resetGadget() {
        Gadget.constructions = 0;
    }

    @Test
    void noConsumerTrustsEveryPackage() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment()
                    .getProperty("spring.kafka.consumer.properties.spring.json.trusted.packages"))
                    .as("trusted packages in application.properties").isNotBlank().doesNotContain("*");
            assertThat(context.getBeansOfType(ConsumerFactory.class)).as("consumer factories").isNotEmpty();
            context.getBeansOfType(ConsumerFactory.class).forEach((name, factory) -> {
                Object trusted = factory.getConfigurationProperties().get(JsonDeserializer.TRUSTED_PACKAGES);
                assertThat(String.valueOf(trusted)).as("trusted packages of " + name).doesNotContain("*");
            });
        });
    }

    @Test
    void aTypeHeaderNeverChoosesTheClassTheServiceBuilds() {
        byte[] json = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            context.getBeansOfType(ConsumerFactory.class).forEach((name, factory) -> {
                RecordHeaders headers = new RecordHeaders();
                headers.add(TYPE_ID_HEADER, Gadget.class.getName().getBytes(StandardCharsets.UTF_8));
                Object value = factory.getValueDeserializer().deserialize("sec03.probe", headers, json);
                assertThat(value instanceof Gadget).as("value read by " + name + ": " + value).isFalse();
            });
            assertThat(Gadget.constructions).as("classes a header made the service build").isZero();
        });
    }

    @Test
    void theSameRecordBuildsTheClassUnderTheOldWildcardSetting() {
        JsonDeserializer<Object> old = new JsonDeserializer<>(Object.class);
        old.configure(Map.of(JsonDeserializer.TRUSTED_PACKAGES, "*", "spring.json.use.type.headers", true), false);
        RecordHeaders headers = new RecordHeaders();
        headers.add(TYPE_ID_HEADER, Gadget.class.getName().getBytes(StandardCharsets.UTF_8));

        Object value = old.deserialize("sec03.probe", headers, "{\"x\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(value).as("control: the old setting builds whatever the header names").isInstanceOf(Gadget.class);
        assertThat(Gadget.constructions).isEqualTo(1);
    }

    @Test
    void aClientCannotAskForAPageLargerThanTheCap() throws Exception {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataWebAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PageableHandlerMethodArgumentResolver resolver = new PageableHandlerMethodArgumentResolver();
                    context.getBean(PageableHandlerMethodArgumentResolverCustomizer.class).customize(resolver);
                    MockHttpServletRequest request = new MockHttpServletRequest();
                    request.setParameter("size", "100000");
                    MethodParameter parameter = new MethodParameter(
                            KafkaDeserializationHardeningTest.class.getDeclaredMethod("pagedHandler", Pageable.class), 0);

                    Pageable page = resolver.resolveArgument(parameter, null, new ServletWebRequest(request), null);

                    assertThat(page.getPageSize()).as("page size after the cap").isEqualTo(200);
                });
    }

    /** Only here to give the resolver a {@link Pageable} parameter to resolve. */
    static void pagedHandler(Pageable pageable) {
    }
}
