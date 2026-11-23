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

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the binding health view, the binding-status publisher and the startup self-test,
 * under {@code dlap.logingest.health}. The defaults live here, not in {@code application.properties}.
 *
 * @param statusEnabled           publish a {@code LogBindingStatus} per binding to
 *                                {@code dlap.logingest.binding.status}
 * @param statusInterval          how often the singleton publisher runs
 * @param degradedLag             a binding whose oldest unacknowledged event time is older than this is
 *                                reported {@code DEGRADED}
 * @param degradedQuarantineRatio a binding that quarantined more than this share of the records of its
 *                                status window is reported {@code DEGRADED}
 * @param degradedMinRecords      fewer records than this in the window and the ratio is not judged
 * @param noReadTransports        transport ids that cannot carry data reads (CloudTrail
 *                                {@code LookupEvents}); a binding whose every stream uses one reports
 *                                {@code READ_COVERAGE_NOT_CAPTURED}
 * @param quarantineWindow        how far back the health view looks for top quarantine reasons
 * @param topReasons              reasons listed per binding
 * @param samplesPerReason        redacted samples listed per reason
 * @param selftestFailFast        stop the service at start when the self-test fails; off by default, the
 *                                {@code logParsers} health component is DOWN either way
 */
@ConfigurationProperties(prefix = "dlap.logingest.health")
public record LogHealthProperties(
        @DefaultValue("true") boolean statusEnabled,
        @DefaultValue("PT1M") Duration statusInterval,
        @DefaultValue("PT30M") Duration degradedLag,
        @DefaultValue("0.05") double degradedQuarantineRatio,
        @DefaultValue("20") long degradedMinRecords,
        @DefaultValue("LOOKUP_EVENTS") Set<String> noReadTransports,
        @DefaultValue("P7D") Duration quarantineWindow,
        @DefaultValue("5") int topReasons,
        @DefaultValue("3") int samplesPerReason,
        @DefaultValue("false") boolean selftestFailFast) {

    /**
     * Validates the settings.
     *
     * @param statusEnabled           see the record description
     * @param statusInterval          see the record description
     * @param degradedLag             see the record description
     * @param degradedQuarantineRatio see the record description
     * @param degradedMinRecords      see the record description
     * @param noReadTransports        see the record description
     * @param quarantineWindow        see the record description
     * @param topReasons              see the record description
     * @param samplesPerReason        see the record description
     * @param selftestFailFast        see the record description
     */
    public LogHealthProperties {
        if (statusInterval == null || statusInterval.isNegative() || statusInterval.isZero()
                || degradedLag == null || degradedLag.isNegative() || degradedLag.isZero()
                || quarantineWindow == null || quarantineWindow.isNegative() || quarantineWindow.isZero()) {
            throw new IllegalArgumentException("dlap.logingest.health: durations must be positive");
        }
        if (degradedQuarantineRatio <= 0 || degradedQuarantineRatio > 1 || degradedMinRecords < 1
                || topReasons < 1 || samplesPerReason < 0) {
            throw new IllegalArgumentException("dlap.logingest.health: degraded-quarantine-ratio is in (0, 1], "
                    + "counts are positive");
        }
        noReadTransports = noReadTransports == null ? Set.of() : Set.copyOf(noReadTransports);
    }

    /**
     * The defaults, for code that builds the settings without Spring.
     *
     * @return the default settings
     */
    public static LogHealthProperties defaults() {
        return new LogHealthProperties(true, Duration.ofMinutes(1), Duration.ofMinutes(30), 0.05, 20,
                Set.of("LOOKUP_EVENTS"), Duration.ofDays(7), 5, 3, false);
    }
}
