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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What the read signal of one asset allows the report to say. The state is derived from two
 * facts the catalog delivers, the last recorded read and the read-logging coverage, and from
 * nothing else.
 *
 * <p>The rule that matters: a missing read is "not read" only when read logging covers the asset
 * completely. With partial coverage, no coverage, or no coverage information, a missing read
 * means the logs were not watching, so the report says so and never says "not read" (test
 * strategy scenario E7, ADR-0046: unknown is never stale or fresh).
 *
 * <p>The coverage text is not interpreted beyond {@code FULL}, {@code PARTIAL} and {@code NONE}
 * (case-insensitive). Any other text, a blank, or {@code null} is {@link #UNKNOWN}.
 */
public enum ReadSignalState {

    /** A successful read is recorded. With partial coverage it is the latest the logs saw, not necessarily the latest. */
    READ,

    /** No read is recorded and read logging covers the asset completely: the asset was not read. */
    NOT_READ,

    /** No read is recorded and read logging covers the asset only in part: reads may have gone unseen. */
    PARTIAL_COVERAGE,

    /** No read is recorded and read logging does not cover the asset: nothing could have been seen. */
    NO_COVERAGE,

    /** No read is recorded and the catalog holds no read coverage for the asset: unknown. */
    UNKNOWN;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    /**
     * Derives the state.
     *
     * @param lastReadAt the latest recorded successful read; null when none is recorded
     * @param coverage   the read coverage text as the catalog delivered it; null when it holds none
     * @return the state, never null
     */
    public static ReadSignalState of(Instant lastReadAt, String coverage) {
        if (lastReadAt != null) {
            return READ;
        }
        if (coverage == null) {
            return UNKNOWN;
        }
        return switch (coverage.strip().toUpperCase(Locale.ROOT)) {
            case "FULL" -> NOT_READ;
            case "PARTIAL" -> PARTIAL_COVERAGE;
            case "NONE" -> NO_COVERAGE;
            default -> UNKNOWN;
        };
    }

    /**
     * The sentence the report prints under the state (Q-434, Q-444): "not read since YYYY-MM-DD" for
     * an asset with no recorded read and complete read coverage, the date being the UTC day on which
     * the current unbroken run of FULL coverage began. Nothing else is said: a state that is not
     * {@link #NOT_READ} has no sentence, and a {@code NOT_READ} whose coverage start the catalog does
     * not hold has none either, because without the start the report cannot say since when.
     *
     * @param state         the read signal state
     * @param coverageSince the start of the current FULL run as the catalog states it; null when none
     * @return the sentence, or null when the report has nothing to add
     */
    public static String text(ReadSignalState state, Instant coverageSince) {
        if (state != NOT_READ || coverageSince == null) {
            return null;
        }
        return "not read since " + DAY.format(coverageSince);
    }
}
