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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.proto.log.v1.SourceProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** The decorator returns what the registry returns and records it, nothing more. */
class MeteredParserRegistryTest {

    private final DefaultParserRegistry real = LogHealthFixtures.registry();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(LogHealthFixtures.T0);
    private final LogIngestMetricsProperties props = LogIngestMetricsProperties.defaults();
    private final LogIngestMetrics metrics = new LogIngestMetrics(meters, new ParseTelemetry(clock, props), clock,
            props, 2);
    private final MeteredParserRegistry metered = new MeteredParserRegistry(real, metrics);

    @Test
    void theOutcomeIsTheDelegatesOutcomeAndIsRecorded() {
        RawRecord record = LogHealthFixtures.record(LogHealthFixtures.BINDING, LogHealthFixtures.write("w1"));
        BindingConfig binding = BindingConfig.auto(LogHealthFixtures.BINDING, LogHealthFixtures.KIND);

        ParseOutcome viaDecorator = metered.parse(record, binding);
        ParseOutcome direct = real.parse(record, binding);

        assertThat(viaDecorator.outcomeCode()).isEqualTo(direct.outcomeCode()).isEqualTo("PARSED_WITH_WARNINGS");
        assertThat(viaDecorator.detected()).isEqualTo(direct.detected());
        assertThat(meters.find("dlap.logingest.records").tag("outcome", "PARSED_WITH_WARNINGS").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aQuarantinedRecordIsCountedUnderItsReason() {
        metered.parse(LogHealthFixtures.record(LogHealthFixtures.BINDING, LogHealthFixtures.broken("b1")),
                BindingConfig.auto(LogHealthFixtures.BINDING, LogHealthFixtures.KIND));

        assertThat(meters.find("dlap.logingest.quarantine").tag("reason", "MISSING_REQUIRED_FIELD").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void theEnvelopeAndRangeQueriesPassThrough() {
        assertThat(metered.envelopes()).isEqualTo(real.envelopes());
        assertThat(metered.rangeFor(DlapNormalizedFormat.envelopeKey(SourceProvider.AWS), "v1"))
                .isEqualTo(real.rangeFor(DlapNormalizedFormat.envelopeKey(SourceProvider.AWS), "v1")).isPresent();
    }

    @Test
    void aCallerDefectStillPropagates() {
        RawRecord record = LogHealthFixtures.record("other-binding", LogHealthFixtures.write("w1"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> metered.parse(record,
                        BindingConfig.auto(LogHealthFixtures.BINDING, LogHealthFixtures.KIND)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(meters.find("dlap.logingest.records").counter()).isNull();
    }
}
