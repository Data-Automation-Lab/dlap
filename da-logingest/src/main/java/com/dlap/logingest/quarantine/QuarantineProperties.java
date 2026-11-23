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

package com.dlap.logingest.quarantine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the quarantine sinks, replay and retention, under {@code dlap.logingest.quarantine}.
 * The defaults live here, not in {@code application.properties}.
 *
 * @param tableEnabled    write every quarantined record to {@code dlap_logingest.quarantine_record}
 *                        (the system of record; replay reads it)
 * @param topicEnabled    publish every quarantined record to {@code dlap.log.event.quarantine}
 * @param maxExcerptBytes the most bytes of the redacted raw excerpt a sink keeps (never more than
 *                        {@value #EXCERPT_CEILING}, whatever the setting)
 * @param retentionDays   age in days, by {@code quarantined_at}, after which the nightly sweep deletes a
 *                        row; 0 or less turns the sweep off
 * @param retentionEnabled the switch of the nightly sweep
 * @param retentionBatch  rows deleted per statement, so one sweep never holds a long lock
 * @param replayMaxBatch  the most records one replay call re-runs
 */
@ConfigurationProperties(prefix = "dlap.logingest.quarantine")
public record QuarantineProperties(
        @DefaultValue("true") boolean tableEnabled,
        @DefaultValue("true") boolean topicEnabled,
        @DefaultValue("2048") int maxExcerptBytes,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("true") boolean retentionEnabled,
        @DefaultValue("5000") int retentionBatch,
        @DefaultValue("500") int replayMaxBatch) {

    /** Hard ceiling of the excerpt: the column is text, but a record is bounded provider data. */
    public static final int EXCERPT_CEILING = 8192;

    /**
     * Validates the settings.
     *
     * @param tableEnabled     see the record description
     * @param topicEnabled     see the record description
     * @param maxExcerptBytes  see the record description
     * @param retentionDays    see the record description
     * @param retentionEnabled see the record description
     * @param retentionBatch   see the record description
     * @param replayMaxBatch   see the record description
     */
    public QuarantineProperties {
        if (maxExcerptBytes < 0 || retentionBatch < 1 || replayMaxBatch < 1) {
            throw new IllegalArgumentException("dlap.logingest.quarantine: max-excerpt-bytes is not negative, "
                    + "retention-batch and replay-max-batch are positive");
        }
        maxExcerptBytes = Math.min(maxExcerptBytes, EXCERPT_CEILING);
    }

    /**
     * The defaults, for code that builds the settings without Spring.
     *
     * @return the default settings
     */
    public static QuarantineProperties defaults() {
        return new QuarantineProperties(true, true, 2048, 30, true, 5000, 500);
    }
}
