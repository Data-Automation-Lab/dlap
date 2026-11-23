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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the parser-health metrics (design B3, test strategy L8), under
 * {@code dlap.logingest.metrics}. Defaults live here, not in {@code application.properties}, so a
 * deployment sets only what it changes.
 *
 * @param bindingLabelMax      the {@code binding} label is added to a meter only while the service has at
 *                             most this many bindings (design B3); with more, the label reads
 *                             {@value #ALL_BINDINGS} so the series count stays bounded
 * @param maxVersionsPerFormat distinct {@code version} label values per (source, format); later versions
 *                             are labelled {@value #OTHER_VERSION}, because a version string comes from
 *                             the record and must not grow the series count without bound
 * @param fillWindow           the rolling window of the field fill ratio and the heuristic ratio
 * @param fillMinSamples       fewer events than this in the window and a ratio is not judged (drift)
 * @param snapshotInterval     how often the health snapshot (cursor lag, observed versions, read signal)
 *                             is rebuilt from the database
 * @param readSignalWindow     the lookback of the read signal: a binding that has seen writes but no
 *                             {@code DATA_READ} event for this long reports read signal 0
 * @param driftDropThreshold   a field fill ratio that falls by more than this fraction below its baseline is
 *                             listed by the drift report (0.2 is 20 points, the threshold of the alert)
 * @param maxDriftKeys         the most (source, format, version) groups the in-memory drift report keeps
 * @param maxPathsPerKey       the most unknown paths kept per group
 */
@ConfigurationProperties(prefix = "dlap.logingest.metrics")
public record LogIngestMetricsProperties(
        @DefaultValue("50") int bindingLabelMax,
        @DefaultValue("32") int maxVersionsPerFormat,
        @DefaultValue("PT1H") Duration fillWindow,
        @DefaultValue("50") long fillMinSamples,
        @DefaultValue("PT1M") Duration snapshotInterval,
        @DefaultValue("PT24H") Duration readSignalWindow,
        @DefaultValue("0.2") double driftDropThreshold,
        @DefaultValue("200") int maxDriftKeys,
        @DefaultValue("100") int maxPathsPerKey) {

    /** The {@code binding} label value used when there are more bindings than {@code bindingLabelMax}. */
    public static final String ALL_BINDINGS = "_all";

    /** The {@code version} label value of versions beyond {@code maxVersionsPerFormat}. */
    public static final String OTHER_VERSION = "other";

    /** The {@code version} label value of a record whose version could not be detected. */
    public static final String UNKNOWN_VERSION = "unknown";

    /**
     * Validates the settings.
     *
     * @param bindingLabelMax      see the record description
     * @param maxVersionsPerFormat see the record description
     * @param fillWindow           see the record description
     * @param fillMinSamples       see the record description
     * @param snapshotInterval     see the record description
     * @param readSignalWindow     see the record description
     * @param driftDropThreshold   see the record description
     * @param maxDriftKeys         see the record description
     * @param maxPathsPerKey       see the record description
     */
    public LogIngestMetricsProperties {
        if (bindingLabelMax < 0 || maxVersionsPerFormat < 1 || fillMinSamples < 1 || maxDriftKeys < 1
                || maxPathsPerKey < 1) {
            throw new IllegalArgumentException("dlap.logingest.metrics: counts must be positive "
                    + "(binding-label-max may be 0)");
        }
        if (fillWindow == null || fillWindow.compareTo(Duration.ofMinutes(1)) < 0
                || snapshotInterval == null || snapshotInterval.isNegative() || snapshotInterval.isZero()
                || readSignalWindow == null || readSignalWindow.isNegative() || readSignalWindow.isZero()) {
            throw new IllegalArgumentException("dlap.logingest.metrics: fill-window is at least one minute and "
                    + "the other durations are positive");
        }
        if (driftDropThreshold <= 0 || driftDropThreshold > 1) {
            throw new IllegalArgumentException("dlap.logingest.metrics.drift-drop-threshold is in (0, 1]");
        }
    }

    /**
     * The defaults, for code that builds the settings without Spring.
     *
     * @return the default settings
     */
    public static LogIngestMetricsProperties defaults() {
        return new LogIngestMetricsProperties(50, 32, Duration.ofHours(1), 50, Duration.ofMinutes(1),
                Duration.ofHours(24), 0.2, 200, 100);
    }
}
