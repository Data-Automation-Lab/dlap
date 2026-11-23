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

package com.dlap.logingest.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.health.ReadCoverageEvaluator.Evaluation;
import com.dlap.proto.log.v1.ReadCoverage;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Every row of the read-coverage decision (log-event spec section 10). The rule they protect: an asset
 * is "not read" only on a binding that captures reads.
 */
class ReadCoverageEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Duration DAY = Duration.ofHours(24);
    private static final Instant RECENT = NOW.minus(Duration.ofHours(2));
    private static final Instant STALE = NOW.minus(Duration.ofHours(30));
    private static final Instant FIRST_READ = Instant.parse("2026-09-01T08:00:00Z");

    private static Evaluation evaluate(boolean canCarryReads, Instant read, Instant write, Instant record) {
        return ReadCoverageEvaluator.evaluate(canCarryReads, Optional.ofNullable(read), Optional.ofNullable(write),
                Optional.ofNullable(record), Optional.of(FIRST_READ), NOW, DAY);
    }

    @Test
    void aTransportThatCannotCarryReadsIsNotCapturedWhateverArrived() {
        Evaluation e = evaluate(false, RECENT, RECENT, RECENT);

        assertThat(e.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_NOT_CAPTURED);
        assertThat(e.signal()).isNaN();
        assertThat(e.since()).isEmpty();
    }

    @Test
    void aReadWithinTheLookbackIsCapturedFromTheFirstKnownRead() {
        Evaluation e = evaluate(true, RECENT, null, RECENT);

        assertThat(e.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_CAPTURED);
        assertThat(e.signal()).isEqualTo(1.0);
        assertThat(e.since()).contains(FIRST_READ);
    }

    @Test
    void writesWithoutReadsMeanReadLoggingMayBeOff() {
        Evaluation e = evaluate(true, STALE, RECENT, RECENT);

        assertThat(e.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_NONE_OBSERVED);
        assertThat(e.signal()).as("the alert fires on exactly this case").isEqualTo(0.0);
        assertThat(e.since()).isEmpty();
    }

    @Test
    void aBindingWithOnlyOtherEventsHasNoReadsButNoProofThatReadsExist() {
        Evaluation e = evaluate(true, null, null, RECENT);

        assertThat(e.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_NONE_OBSERVED);
        assertThat(e.signal()).as("without writes the silence proves nothing").isNaN();
    }

    @Test
    void aBindingThatDeliveredNothingRecentlyIsNotYetKnown() {
        Evaluation e = evaluate(true, null, null, STALE);

        assertThat(e.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_UNSPECIFIED);
        assertThat(e.signal()).isNaN();
    }

    @Test
    void aReadOlderThanTheLookbackDoesNotCount() {
        Evaluation e = evaluate(true, STALE, null, RECENT);

        assertThat(e.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_NONE_OBSERVED);
    }

    @Test
    void theLookbackBoundaryIsInclusive() {
        Evaluation e = evaluate(true, NOW.minus(DAY), null, null);

        assertThat(e.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_CAPTURED);
    }

    @Test
    void capturedWithoutAKnownFirstReadHasNoSince() {
        Evaluation e = ReadCoverageEvaluator.evaluate(true, Optional.of(RECENT), Optional.empty(), Optional.empty(),
                Optional.empty(), NOW, DAY);

        assertThat(e.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_CAPTURED);
        assertThat(e.since()).isEmpty();
    }
}
