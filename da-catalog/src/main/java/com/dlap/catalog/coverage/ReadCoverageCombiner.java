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

package com.dlap.catalog.coverage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.dlap.catalog.retention.BindingStatusStore.BindingStatus;

/**
 * Turns a provider-configuration observation into the coverage the catalog stores, by asking
 * whether DLAP actually ingests the reads the configuration records (spec "Read coverage").
 *
 * <p>The provider configuration says which reads are recorded; the log bindings of the
 * observation's scope say which of those records reach DLAP. Both must hold for a read history
 * to be complete, so:
 *
 * <ol>
 *   <li>An enabled binding of the observation's channel reports {@code READ_COVERAGE_CAPTURED}:
 *       the configuration decides (its level and reasons, unknown stays unknown).</li>
 *   <li>Otherwise, for BigQuery, an enabled {@code GCP_BQ_JOBS_VIEW} binding that captures reads:
 *       {@code PARTIAL}, "bqjobs only: job reads only". The JOBS view lists only jobs run in the
 *       project; Storage Read API reads, {@code tabledata.list} and jobs run from other projects
 *       are not in it.</li>
 *   <li>Otherwise, an enabled binding of the channel exists but does not capture reads: unknown,
 *       naming the binding's state. DLAP cannot yet tell whether reads arrive.</li>
 *   <li>Otherwise: {@code NONE}. No DLAP binding ingests the recorded reads, so DLAP's read
 *       history of the asset is empty whatever the provider records.</li>
 * </ol>
 *
 * <p>Pure: the same observation and bindings give the same result.
 */
public final class ReadCoverageCombiner {

    /** {@code dlap.log.v1.ReadCoverage.READ_COVERAGE_CAPTURED}. */
    static final String CAPTURED = "READ_COVERAGE_CAPTURED";
    /** {@code dlap.log.v1.BindingState.BINDING_STATE_DISABLED}. */
    static final String DISABLED = "BINDING_STATE_DISABLED";
    /** Source kind of the BigQuery JOBS-view family (GCP-02). */
    static final String BQ_JOBS_KIND = "GCP_BQ_JOBS_VIEW";
    /** The reason a JOBS-view binding alone gives (critique correction 2). */
    public static final String BQJOBS_ONLY = "bqjobs only: job reads only";

    private ReadCoverageCombiner() {
    }

    /**
     * The coverage to store.
     *
     * @param level   the level, {@code null} for unknown
     * @param reasons the reasons, never {@code null}
     */
    public record Result(ReadCoverageLevel level, List<String> reasons) {

        /**
         * Creates the result.
         *
         * @param level   the level
         * @param reasons the reasons
         */
        public Result {
            reasons = ReadCoverageWire.bounded(List.copyOf(reasons));
        }
    }

    /**
     * Combines an observation with the binding statuses.
     *
     * @param observation the observation
     * @param bindings    every stored binding status; those of other scopes are ignored
     * @return the coverage to store
     */
    public static Result combine(ReadCoverageObservation observation, List<BindingStatus> bindings) {
        List<BindingStatus> inScope = bindings.stream()
                .filter(b -> !DISABLED.equals(b.state()))
                .filter(b -> same(b.sourceProvider(), observation.provider()) && same(b.scopeId(), observation.scopeId()))
                .toList();
        List<BindingStatus> channel = inScope.stream()
                .filter(b -> observation.channel().equals(b.sourceKind()))
                .toList();
        if (channel.stream().anyMatch(b -> CAPTURED.equals(b.readCoverage()))) {
            return new Result(observation.level(), observation.reasons());
        }
        boolean bigQuery = observation.appliesTo().stream().anyMatch(k -> k.startsWith("BIGQUERY_"));
        if (bigQuery && inScope.stream().anyMatch(b -> BQ_JOBS_KIND.equals(b.sourceKind())
                && CAPTURED.equals(b.readCoverage()))) {
            return new Result(ReadCoverageLevel.PARTIAL, with(BQJOBS_ONLY, gaps(observation)));
        }
        if (!channel.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            for (BindingStatus b : channel) {
                reasons.add("binding " + b.bindingId() + " (" + observation.channel() + ") reports "
                        + (b.readCoverage() == null ? "no read coverage" : b.readCoverage()));
            }
            reasons.addAll(gaps(observation));
            return new Result(null, reasons);
        }
        return new Result(ReadCoverageLevel.NONE, with("no DLAP log binding ingests " + observation.channel()
                + " records for " + observation.provider() + " scope " + observation.scopeId(), gaps(observation)));
    }

    /** The observation's reasons when they describe gaps; a FULL observation's reasons are evidence. */
    private static List<String> gaps(ReadCoverageObservation observation) {
        return observation.level() == ReadCoverageLevel.FULL ? List.of() : observation.reasons();
    }

    private static List<String> with(String first, List<String> rest) {
        List<String> out = new ArrayList<>(rest.size() + 1);
        out.add(first);
        out.addAll(rest);
        return out;
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.strip().toLowerCase(Locale.ROOT).equals(b.strip().toLowerCase(Locale.ROOT));
    }
}
