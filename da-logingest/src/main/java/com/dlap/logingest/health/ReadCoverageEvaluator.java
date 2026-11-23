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

import com.dlap.proto.log.v1.ReadCoverage;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides whether a binding captures data reads, from what it delivered (log-event spec section 10,
 * test strategy L8). A pure function, so every branch is a unit test.
 *
 * <p>The question the answer protects: an asset may be called "not read since D" only when the
 * binding captures reads. A binding that never delivered a read event is not evidence that nobody
 * read anything: the provider's read logging may be off (CloudTrail data events, GCP data-access
 * audit logs), or the transport cannot carry reads at all.
 *
 * <table>
 *   <caption>Decision, first matching row wins</caption>
 *   <tr><th>Condition</th><th>Coverage</th><th>Read signal</th></tr>
 *   <tr><td>no stream of the binding can carry data reads</td><td>NOT_CAPTURED</td><td>unknown (NaN)</td></tr>
 *   <tr><td>a data-read event arrived within the lookback</td><td>CAPTURED</td><td>1</td></tr>
 *   <tr><td>data writes arrived, no read, within the lookback</td><td>NONE_OBSERVED</td><td>0</td></tr>
 *   <tr><td>other events arrived, no read, no write</td><td>NONE_OBSERVED</td><td>unknown (NaN)</td></tr>
 *   <tr><td>nothing known</td><td>UNSPECIFIED</td><td>unknown (NaN)</td></tr>
 * </table>
 *
 * <p>{@code read_coverage_since} is the earliest event time of a read event known for the binding.
 * It is a lower bound that can only be too late, never too early, so a report built on it errs toward
 * "unknown" and never toward "not read". The signal is 0 only when the writes prove the binding
 * delivers data events at all, which is what separates "read logging is off" from "a quiet binding".
 */
public final class ReadCoverageEvaluator {

    private ReadCoverageEvaluator() {
    }

    /**
     * The verdict.
     *
     * @param coverage the coverage for the status message
     * @param since    {@code read_coverage_since}, present only for {@code CAPTURED} when a first read is known
     * @param signal   the value of {@code dlap_logingest_read_signal_present}: 1, 0 or NaN
     */
    public record Evaluation(ReadCoverage coverage, Optional<Instant> since, double signal) {
    }

    /**
     * Evaluates one binding.
     *
     * @param canCarryReads  whether at least one stream's transport can carry data reads
     * @param lastRead       when a data-read event last arrived (processing time)
     * @param lastWrite      when a data-write event last arrived
     * @param lastRecord     when any record last arrived
     * @param firstReadEvent the earliest event time of a data-read event
     * @param now            the current time
     * @param lookback       how far back "arrived" counts
     * @return the verdict
     */
    public static Evaluation evaluate(boolean canCarryReads, Optional<Instant> lastRead, Optional<Instant> lastWrite,
                                      Optional<Instant> lastRecord, Optional<Instant> firstReadEvent, Instant now,
                                      Duration lookback) {
        if (!canCarryReads) {
            return new Evaluation(ReadCoverage.READ_COVERAGE_NOT_CAPTURED, Optional.empty(), Double.NaN);
        }
        Instant cutoff = now.minus(lookback);
        if (lastRead.filter(t -> !t.isBefore(cutoff)).isPresent()) {
            return new Evaluation(ReadCoverage.READ_COVERAGE_CAPTURED, firstReadEvent, 1.0);
        }
        if (lastWrite.filter(t -> !t.isBefore(cutoff)).isPresent()) {
            return new Evaluation(ReadCoverage.READ_COVERAGE_NONE_OBSERVED, Optional.empty(), 0.0);
        }
        boolean activity = lastRecord.filter(t -> !t.isBefore(cutoff)).isPresent();
        return new Evaluation(activity ? ReadCoverage.READ_COVERAGE_NONE_OBSERVED
                : ReadCoverage.READ_COVERAGE_UNSPECIFIED, Optional.empty(), Double.NaN);
    }
}
