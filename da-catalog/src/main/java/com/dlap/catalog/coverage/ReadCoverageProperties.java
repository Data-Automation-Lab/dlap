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

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the catalog's read-coverage handling ({@code dlap.catalog.coverage.*}, COV-01). The
 * defaults live here; the two switches read by annotations are
 * {@code dlap.catalog.coverage.listener-enabled} (default {@code true}: apply observations from
 * the spine) and {@code dlap.catalog.coverage.require-asset-coverage} (default {@code true}: the
 * retention evaluator needs per-asset {@code FULL} coverage on top of the binding signal).
 */
@ConfigurationProperties(prefix = "dlap.catalog.coverage")
public class ReadCoverageProperties {

    private Duration validityGrace = Duration.ZERO;

    /**
     * How long after its observation a {@code FULL} coverage still counts as proven for the
     * retention evaluator. A provider configuration is known only at the instant it was read, so
     * the default is zero: an expiry after the last observation is {@code READ_COVERAGE_STALE}.
     *
     * @return the grace, never negative
     */
    public Duration getValidityGrace() {
        return validityGrace;
    }

    /**
     * Sets the grace.
     *
     * @param validityGrace the grace; {@code null} or negative is refused
     */
    public void setValidityGrace(Duration validityGrace) {
        if (validityGrace == null || validityGrace.isNegative()) {
            throw new IllegalArgumentException("dlap.catalog.coverage.validity-grace must be zero or positive");
        }
        this.validityGrace = validityGrace;
    }
}
