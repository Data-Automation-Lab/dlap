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

package com.dlap.reporting.drift;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Consumed-stage drift detector (ADR-0040, enforcement-contract.md &#167;6).
 *
 * <p>Correlates PDP access-decisions against actual asset reads observed on the
 * log spine and emits a {@link DriftFinding} wherever the two diverge, joining
 * on {@code (assetId + subjectId)} within a configurable time window. Both keys
 * arrive canonical (enforcement-contract.md section 6.1): {@code assetId} is the
 * rolled-up canonical asset URI from {@code AssetIdNormalizer} and
 * {@code subjectId} is the DLAP subject, mapped from the cloud principal through
 * the principal identity map on the read side.
 *
 * <p>{@link #detect(List, List, long)} is a <b>pure function</b>: identical
 * inputs yield identical, deterministically-ordered output, with no Kafka, no
 * DB, no clock read. The Kafka/persistence wiring lives in the consumers and
 * {@link DriftDetectionOrchestrator}; this class is the unit-testable core.
 *
 * <p>Detection rules, per the spec:
 * <ul>
 *   <li><b>UNENFORCED_READ</b>: a read with no ALLOW decision for the same
 *       {@code (asset, subject)} whose decision time is within
 *       {@code windowSeconds} <i>before</i> the read.</li>
 *   <li><b>ENFORCEMENT_BYPASS</b>: a read preceded (within the window) by a
 *       DENY decision for the same {@code (asset, subject)}. Deny overrides:
 *       a bypass is reported even if an ALLOW is also present, because a
 *       present deny means the read should not have happened.</li>
 * </ul>
 */
@Service
public class DriftDetectionService {

    /** Default correlation window if {@code dlap.drift.window-seconds} is unset. */
    public static final long DEFAULT_WINDOW_SECONDS = 300L;

    private final long defaultWindowSeconds;

    public DriftDetectionService(
            @Value("${dlap.drift.window-seconds:" + DEFAULT_WINDOW_SECONDS + "}") long windowSeconds) {
        this.defaultWindowSeconds = windowSeconds > 0 ? windowSeconds : DEFAULT_WINDOW_SECONDS;
    }

    /** The configured window; used by the orchestrator so it need not repeat the property. */
    public long windowSeconds() {
        return defaultWindowSeconds;
    }

    /**
     * Detect drift using the configured default window.
     *
     * @see #detect(List, List, long)
     */
    public List<DriftFinding> detect(List<DecisionRecord> decisions, List<ReadRecord> reads) {
        return detect(decisions, reads, defaultWindowSeconds);
    }

    /**
     * Correlate {@code reads} against {@code decisions} and return every drift
     * finding, ordered deterministically by {@code (readAt, assetId, subjectId,
     * type)}.
     *
     * <p>For each read, the applicable decisions are those for the same
     * {@code (assetId, subjectId)} whose {@code decidedAt} falls in
     * {@code [readAt - window, readAt]} (a decision authorizes reads that follow
     * it, not ones that preceded it). Then:
     * <ul>
     *   <li>any applicable DENY &#8594; ENFORCEMENT_BYPASS;</li>
     *   <li>else no applicable ALLOW &#8594; UNENFORCED_READ;</li>
     *   <li>else (an applicable ALLOW, no deny) &#8594; enforced, no finding.</li>
     * </ul>
     *
     * @param decisions     recent PDP decisions; {@code null} treated as empty
     * @param reads         recent asset reads; {@code null} treated as empty
     * @param windowSeconds join window in seconds; non-positive falls back to
     *                      the configured default
     * @return findings, one per drifting read; never {@code null}
     */
    public List<DriftFinding> detect(List<DecisionRecord> decisions,
                                     List<ReadRecord> reads,
                                     long windowSeconds) {
        long window = windowSeconds > 0 ? windowSeconds : defaultWindowSeconds;
        Duration windowDuration = Duration.ofSeconds(window);

        List<DecisionRecord> decisionList = decisions == null ? List.of() : decisions;
        List<ReadRecord> readList = reads == null ? List.of() : reads;

        List<DriftFinding> findings = new ArrayList<>();

        for (ReadRecord read : readList) {
            if (read == null || read.assetId() == null || read.subjectId() == null
                    || read.readAt() == null) {
                continue;
            }

            DecisionRecord matchedDeny = null;
            boolean hasAllow = false;

            for (DecisionRecord decision : decisionList) {
                if (!applies(decision, read, windowDuration)) {
                    continue;
                }
                if (decision.allow()) {
                    hasAllow = true;
                } else if (matchedDeny == null
                        || decision.decidedAt().isAfter(matchedDeny.decidedAt())) {
                    // Correlate the most recent applicable deny.
                    matchedDeny = decision;
                }
            }

            if (matchedDeny != null) {
                findings.add(new DriftFinding(
                        DriftType.ENFORCEMENT_BYPASS,
                        read.subjectId(),
                        read.assetId(),
                        matchedDeny.decisionId(),
                        read.readAt(),
                        "DENY decision " + matchedDeny.decisionId()
                                + " was followed by an actual read of asset "
                                + read.assetId() + " by " + read.subjectId()));
            } else if (!hasAllow) {
                findings.add(new DriftFinding(
                        DriftType.UNENFORCED_READ,
                        read.subjectId(),
                        read.assetId(),
                        null,
                        read.readAt(),
                        "Asset " + read.assetId() + " was read by " + read.subjectId()
                                + " with no corresponding ALLOW decision within "
                                + window + "s"));
            }
        }

        findings.sort(Comparator
                .comparing(DriftFinding::readAt)
                .thenComparing(DriftFinding::assetId)
                .thenComparing(DriftFinding::subjectId)
                .thenComparing(f -> f.type().name()));
        return findings;
    }

    /**
     * A decision applies to a read when it is for the same canonical {@code (asset,
     * subject)} key and its decision time is within {@code window} before the read
     * (inclusive of the read instant).
     */
    private static boolean applies(DecisionRecord decision, ReadRecord read, Duration window) {
        if (decision == null || decision.decidedAt() == null) {
            return false;
        }
        if (!Objects.equals(decision.assetId(), read.assetId())
                || !Objects.equals(decision.subjectId(), read.subjectId())) {
            return false;
        }
        Instant windowStart = read.readAt().minus(window);
        Instant decidedAt = decision.decidedAt();
        // windowStart <= decidedAt <= readAt
        return !decidedAt.isBefore(windowStart) && !decidedAt.isAfter(read.readAt());
    }
}
