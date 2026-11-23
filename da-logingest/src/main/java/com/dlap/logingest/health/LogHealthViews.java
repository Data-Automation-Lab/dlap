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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The JSON shapes of the parser health API ({@code GET /api/v1/logingest/health},
 * {@code /health/{bindingId}} and {@code /drift}). Plain records; no field carries a secret, a
 * record value or an unmasked identifier. Excerpts are the masked, bounded text of the quarantine
 * table.
 */
public final class LogHealthViews {

    private LogHealthViews() {
    }

    /**
     * The startup self-test.
     *
     * @param ok     whether every golden case passed
     * @param cases  cases run
     * @param failed the failing case ids with what differed
     */
    public record SelfTestView(boolean ok, int cases, Map<String, String> failed) {
    }

    /**
     * One stream of a binding.
     *
     * @param streamId      the stream
     * @param transport     the transport id
     * @param envelope      the envelope
     * @param pinnedVersion the pinned version or {@code auto}
     * @param lowWatermark  the oldest unacknowledged event time
     * @param highWatermark the newest event time seen
     * @param lastPoll      when the cursor last moved
     */
    public record StreamView(String streamId, String transport, String envelope, String pinnedVersion,
                             Instant lowWatermark, Instant highWatermark, Instant lastPoll) {
    }

    /**
     * A version a binding delivered, set against what it declares.
     *
     * @param envelope    the envelope
     * @param version     the detected version
     * @param fingerprint the shape fingerprint
     * @param declared    the version the stream of this envelope pins, or {@code auto}
     * @param matchesPin  false when the stream pins another version
     * @param firstSeen   when it was first seen
     * @param lastSeen    when it was last seen
     * @param count       records seen
     */
    public record VersionView(String envelope, String version, String fingerprint, String declared,
                              boolean matchesPin, Instant firstSeen, Instant lastSeen, long count) {
    }

    /**
     * A redacted sample of a quarantined record.
     *
     * @param quarantineId    the record's id
     * @param quarantinedAt   when it was quarantined
     * @param detectedVersion the version detected on it
     * @param reasonDetail    why, naming fields and never values
     * @param rawExcerpt      the masked, bounded leading part of the record
     * @param rawTruncated    whether the excerpt is shorter than the record
     */
    public record SampleView(String quarantineId, Instant quarantinedAt, String detectedVersion, String reasonDetail,
                             String rawExcerpt, boolean rawTruncated) {
    }

    /**
     * A quarantine reason with its count and samples.
     *
     * @param reason  the reason code
     * @param count   records in the window
     * @param samples the newest samples
     */
    public record ReasonView(String reason, long count, List<SampleView> samples) {
    }

    /**
     * An unknown JSON path of a source.
     *
     * @param format  the envelope
     * @param version the version label
     * @param path    the path, never a value
     * @param count   records that carried it since the instance started
     */
    public record UnknownPathView(String format, String version, String path, long count) {
    }

    /**
     * The fill ratio of a watched field.
     *
     * @param version  the version label
     * @param field    the field
     * @param ratio    the ratio over the window, null when nothing was seen
     * @param samples  events in the window
     * @param baseline the highest ratio seen since the instance started, null before enough samples
     */
    public record FillView(String version, String field, Double ratio, long samples, Double baseline) {
    }

    /**
     * The health of one binding: what the parser health page shows in a row.
     *
     * @param bindingId           the binding
     * @param sourceId            its source
     * @param sourceKind          the source kind
     * @param scopeType           what the binding covers, for example {@code GCP_PROJECT}
     * @param scopeId             the account, project, subscription or workspace id
     * @param state               ACTIVE, DISABLED, DEGRADED or FAILED
     * @param stateDetail         why, empty for ACTIVE
     * @param configHash          SHA-256 of the effective configuration
     * @param streams             its streams with their cursors
     * @param versions            the versions it delivered, against what it declares
     * @param topQuarantineReasons the most frequent reasons of the window, with samples
     * @param unknownFieldPaths   the unknown paths seen by this instance
     * @param fillRatios          the watched fields' fill ratios
     * @param heuristicRatio      share of events classified by the verb heuristic, null when none seen
     * @param lastRecordAt        when this instance last received a record of the binding
     * @param lagSeconds          now minus the oldest unacknowledged event time, null before the first poll
     * @param readCoverage        CAPTURED, NONE_OBSERVED, NOT_CAPTURED or UNSPECIFIED
     * @param readCoverageSince   the earliest known read event time when CAPTURED
     * @param readSignalPresent   true/false, null when unknown
     */
    public record BindingHealth(String bindingId, String sourceId, String sourceKind, String scopeType,
                                String scopeId, String state, String stateDetail, String configHash,
                                List<StreamView> streams, List<VersionView> versions,
                                List<ReasonView> topQuarantineReasons, List<UnknownPathView> unknownFieldPaths,
                                List<FillView> fillRatios, Double heuristicRatio, Instant lastRecordAt,
                                Long lagSeconds, String readCoverage, Instant readCoverageSince,
                                Boolean readSignalPresent) {
    }

    /**
     * The parser health page.
     *
     * @param generatedAt when the view was built
     * @param pipeline    {@code legacy} or {@code registry}
     * @param selfTest    the startup self-test
     * @param bindings    one entry per binding
     */
    public record HealthPage(Instant generatedAt, String pipeline, SelfTestView selfTest,
                             List<BindingHealth> bindings) {
    }

    /**
     * A field whose fill ratio fell below its baseline.
     *
     * @param source   the source kind
     * @param version  the version label
     * @param field    the field
     * @param ratio    the current ratio
     * @param baseline the baseline
     * @param drop     baseline minus ratio
     * @param samples  events in the window
     */
    public record FillDropView(String source, String version, String field, double ratio, double baseline,
                               double drop, long samples) {
    }

    /**
     * A source's unknown path in the drift report.
     *
     * @param source  the source kind
     * @param format  the envelope
     * @param version the version label
     * @param path    the path, never a value
     * @param count   records that carried it
     */
    public record DriftPathView(String source, String format, String version, String path, long count) {
    }

    /**
     * A version first seen inside the report window.
     *
     * @param bindingId   the binding
     * @param source      the source kind
     * @param format      the envelope
     * @param version     the version
     * @param fingerprint the shape fingerprint
     * @param firstSeen   when it was first seen
     * @param count       records seen
     */
    public record NewVersionView(String bindingId, String source, String format, String version, String fingerprint,
                                 Instant firstSeen, long count) {
    }

    /**
     * The drift report: what changed in the logs lately. It feeds new fixtures and parser work.
     *
     * @param generatedAt when it was built
     * @param since       the start of the window for new versions
     * @param note        what is per instance and what is persistent
     * @param newVersions versions first seen since {@code since}, from the database
     * @param unknownPaths unknown JSON paths, most frequent first, since the instance started
     * @param fillDrops   fields whose fill ratio fell more than the threshold below their baseline
     */
    public record DriftReport(Instant generatedAt, Instant since, String note, List<NewVersionView> newVersions,
                              List<DriftPathView> unknownPaths, List<FillDropView> fillDrops) {
    }
}
