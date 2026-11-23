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

package com.dlap.reporting.report.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rule that read logging that is not enabled is never shown as "not read" (test strategy
 * scenario E7, ADR-0046, the wave 3C rule for RPT-01).
 */
class ReadSignalStateTest {

    private static final Instant SOME_READ = Instant.parse("2026-10-01T10:15:30Z");

    @ParameterizedTest
    @CsvSource(value = {
            "FULL,NOT_READ",
            "full,NOT_READ",
            " FULL ,NOT_READ",
            "PARTIAL,PARTIAL_COVERAGE",
            "partial,PARTIAL_COVERAGE",
            "NONE,NO_COVERAGE",
            "none,NO_COVERAGE",
            "UNKNOWN,UNKNOWN",
            "FULL_EXTENDED,UNKNOWN",
            "'',UNKNOWN",
    })
    void aMissingReadMeansWhatTheCoverageAllows(String coverage, ReadSignalState expected) {
        assertThat(ReadSignalState.of(null, coverage)).isEqualTo(expected);
    }

    @Test
    void aMissingReadWithNoCoverageInformationIsUnknownNotNotRead() {
        assertThat(ReadSignalState.of(null, null)).isEqualTo(ReadSignalState.UNKNOWN);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"FULL", "PARTIAL", "NONE", "UNKNOWN", "anything"})
    void aRecordedReadIsReadWhateverTheCoverage(String coverage) {
        assertThat(ReadSignalState.of(SOME_READ, coverage)).isEqualTo(ReadSignalState.READ);
    }

    @Test
    void notReadIsReachableOnlyThroughFullCoverage() {
        List<String> coverages = Arrays.asList(null, "", " ", "NONE", "PARTIAL", "UNKNOWN", "FULLY", "NOT_READ", "0",
                "false", "full-ish");
        for (String coverage : coverages) {
            assertThat(ReadSignalState.of(null, coverage))
                    .as("coverage %s", coverage)
                    .isNotEqualTo(ReadSignalState.NOT_READ);
        }
        assertThat(ReadSignalState.of(null, "FULL")).isEqualTo(ReadSignalState.NOT_READ);
    }

    @Test
    void theSentenceNamesTheUtcDayOfTheCoverageStartForNotReadOnly() {
        assertThat(ReadSignalState.text(ReadSignalState.NOT_READ, Instant.parse("2026-09-20T23:59:59Z")))
                .isEqualTo("not read since 2026-09-20");
        assertThat(ReadSignalState.text(ReadSignalState.NOT_READ, Instant.parse("2026-01-01T00:00:00Z")))
                .isEqualTo("not read since 2026-01-01");
    }

    @Test
    void theSentenceNeedsBothTheNotReadStateAndAKnownStart() {
        Instant since = Instant.parse("2026-09-20T06:41:00Z");
        assertThat(ReadSignalState.text(ReadSignalState.NOT_READ, null)).isNull();
        for (ReadSignalState state : ReadSignalState.values()) {
            if (state != ReadSignalState.NOT_READ) {
                assertThat(ReadSignalState.text(state, since)).as(state.name()).isNull();
            }
        }
    }
}
