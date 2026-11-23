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

import com.dlap.logingest.config.sources.TransportCatalog;
import com.dlap.logingest.parse.bqjobs.BqJobsConfiguration;
import com.dlap.logingest.parse.bqjobs.BqJobsFormat;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.ParserContractConfiguration;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.logingest.transport.TransportRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The family registers itself (parser contract, rule 2): the mapping is loaded from the classpath,
 * the parser and the declarative detector join the registry, and the transport joins the transport
 * registry, with no central switch naming either. Also the safety net for context startup: the
 * BigQuery gateway is a component that must not look for credentials when the service starts.
 */
class BqJobsWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ParserContractConfiguration.class, BqJobsConfiguration.class)
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC))
            .withBean(JobsViewGateway.class, BigQueryJobsViewGateway::new)
            .withBean(BqJobsViewTransport.class)
            .withBean(TransportRegistry.class);

    @Test
    void theParserTheDetectorAndTheTransportRegisterThemselves() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            DefaultParserRegistry registry = context.getBean(DefaultParserRegistry.class);
            EnvelopeKey key = new EnvelopeKey(BqJobsFormat.SOURCE_KIND, BqJobsFormat.ENVELOPE);
            assertThat(registry.envelopes()).contains(key);
            assertThat(registry.rangeFor(key, BqJobsFormat.VERSION)).isPresent();
            assertThat(context.getBean(MappingSpecs.class).forSourceKind(BqJobsFormat.SOURCE_KIND)).isPresent();

            TransportRegistry transports = context.getBean(TransportRegistry.class);
            assertThat(transports.ids()).contains(BqJobsViewTransport.TRANSPORT_ID);
            assertThat(transports.requirements(BqJobsViewTransport.TRANSPORT_ID))
                    .map(TransportCatalog.Requirements::required).hasValue(java.util.Set.of("location"));
        });
    }
}
