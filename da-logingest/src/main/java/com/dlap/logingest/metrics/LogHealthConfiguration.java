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

package com.dlap.logingest.metrics;

import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.health.LogHealthProperties;
import com.dlap.logingest.parse.core.ConditionalOnRegistryPipeline;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.quarantine.QuarantineProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the parser-health layer (LOG-03): the settings of metrics, health and quarantine, the
 * in-memory telemetry, the meters, the producer listener that counts failed sends and, with the
 * registry pipeline, the metered parser registry.
 *
 * <p>Everything here is passive in the {@code legacy} pipeline: the telemetry and the meters exist
 * but nothing feeds them, and the metered registry is not created, so the legacy ingest path is
 * byte-for-byte what it was.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LogIngestMetricsProperties.class, LogHealthProperties.class,
        QuarantineProperties.class})
public class LogHealthConfiguration {

    /**
     * The in-memory telemetry.
     *
     * @param clock the application clock when unique, else the UTC system clock
     * @param props the metric settings
     * @return the telemetry
     */
    @Bean
    public ParseTelemetry parseTelemetry(ObjectProvider<Clock> clock, LogIngestMetricsProperties props) {
        return new ParseTelemetry(clock.getIfUnique(Clock::systemUTC), props);
    }

    /**
     * The meters.
     *
     * @param registry  the meter registry
     * @param telemetry the telemetry
     * @param clock     the application clock when unique, else the UTC system clock
     * @param props     the metric settings
     * @param sources   the effective configuration, for the number of bindings
     * @return the meters
     */
    @Bean
    public LogIngestMetrics logIngestMetrics(MeterRegistry registry, ParseTelemetry telemetry,
                                             ObjectProvider<Clock> clock, LogIngestMetricsProperties props,
                                             EffectiveLogSources sources) {
        return new LogIngestMetrics(registry, telemetry, clock.getIfUnique(Clock::systemUTC), props,
                sources.bindings().size());
    }

    /**
     * Counts every failed Kafka send. Spring Boot's Kafka auto-configuration wires a
     * {@code ProducerListener} bean into the {@code KafkaTemplate}.
     *
     * @param metrics the meters
     * @return the listener
     */
    @Bean
    public PublishFailureListener publishFailureListener(LogIngestMetrics metrics) {
        return new PublishFailureListener(metrics);
    }

    /**
     * The parser registry the ingest path uses: the real one wrapped to record every outcome. The
     * {@code DefaultParserRegistry} bean stays what configuration validation and the startup
     * self-test use.
     *
     * @param delegate the real registry
     * @param metrics  the meters
     * @return the primary {@link ParserRegistry}
     */
    @Bean
    @Primary
    @ConditionalOnRegistryPipeline
    public ParserRegistry meteredParserRegistry(DefaultParserRegistry delegate, LogIngestMetrics metrics) {
        return new MeteredParserRegistry(delegate, metrics);
    }
}
