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

package com.dlap.adminservice.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.ChainAnchor;
import com.dlap.common.audit.ChainVerification;

/**
 * Verifies one stream: the whole chain, every stored checkpoint's signature against the trusted
 * keys, and the chain against every checkpoint that verified (docs/specs/audit-log.md, section
 * "Verification"). A checkpoint that does not verify makes the stream BROKEN: only the signer's
 * key can make a valid one, so a bad one means the checkpoint table was changed.
 */
public class AuditVerificationService {

    /** Overall result. */
    public enum Status {
        /** The chain and every checkpoint verified. */
        VALID,
        /** Something did not verify; see the report. */
        BROKEN
    }

    /**
     * A checkpoint that did not verify.
     *
     * @param seq    the position the row claims
     * @param reason why it does not verify
     */
    public record CheckpointProblem(long seq, String reason) {
    }

    /**
     * The verification report.
     *
     * @param streamId                  the stream
     * @param status                    VALID or BROKEN
     * @param verifiedAt                when the check ran
     * @param entriesChecked            entries that verified before the first problem
     * @param headSeq                   the last entry that verified (the head when VALID)
     * @param headHash                  its hash, {@code null} for an empty stream
     * @param firstBadSeq               where the chain broke, or {@code null}
     * @param chainProblem              the chain problem, or {@code null}
     * @param chainDetail               a sentence about it, or {@code null}
     * @param checkpointsTotal          stored checkpoints of the stream
     * @param checkpointsVerified       those whose signature verified and that the chain matched
     * @param checkpointProblems        those that did not verify
     * @param latestVerifiedCheckpoint  the newest verified checkpoint position, or {@code null}
     * @param latestCheckpointExported  whether that checkpoint has a write-once copy
     * @param unanchoredEntries         entries after the newest verified checkpoint
     */
    public record Report(String streamId, Status status, Instant verifiedAt, long entriesChecked, long headSeq,
            String headHash, Long firstBadSeq, ChainVerification.Problem chainProblem, String chainDetail,
            int checkpointsTotal, int checkpointsVerified, List<CheckpointProblem> checkpointProblems,
            Long latestVerifiedCheckpoint, boolean latestCheckpointExported, long unanchoredEntries) {
    }

    private final AuditLogReader reader;
    private final CheckpointStore store;
    private final CheckpointKeys keys;
    private final AuditChainVerifier verifier;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param reader   the audit log reader
     * @param store    the checkpoint table
     * @param keys     the trusted keys
     * @param verifier the chain verifier
     * @param clock    the clock for {@code verifiedAt}
     */
    public AuditVerificationService(AuditLogReader reader, CheckpointStore store, CheckpointKeys keys,
            AuditChainVerifier verifier, Clock clock) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.store = Objects.requireNonNull(store, "store");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies a stream.
     *
     * @param streamId the stream
     * @return the report, or empty when no such stream is registered
     * @throws IllegalArgumentException for a malformed stream id
     */
    public Optional<Report> verify(String streamId) {
        AuditStreams.requireValid(streamId);
        if (reader.streams().stream().noneMatch(s -> s.streamId().equals(streamId))) {
            return Optional.empty();
        }
        List<StoredCheckpoint> stored = store.all(streamId);
        List<ChainAnchor> anchors = new ArrayList<>();
        List<CheckpointProblem> problems = new ArrayList<>();
        StoredCheckpoint newestGood = null;
        for (StoredCheckpoint c : stored) {
            String problem = keys.problemWith(c);
            if (problem == null) {
                anchors.add(new ChainAnchor(c.seq(), c.entryHash()));
                if (newestGood == null || c.seq() > newestGood.seq()) {
                    newestGood = c;
                }
            } else {
                problems.add(new CheckpointProblem(c.seq(), problem));
            }
        }
        ChainVerification v = verifier.verify(streamId, reader, anchors);
        Status status = v.isValid() && problems.isEmpty() ? Status.VALID : Status.BROKEN;
        long unanchored = newestGood == null ? v.lastGoodSeq() : Math.max(0, v.lastGoodSeq() - newestGood.seq());
        return Optional.of(new Report(streamId, status, clock.instant(), v.entriesChecked(), v.lastGoodSeq(),
                v.lastGoodHash(), v.firstBadSeq(), v.problem(), v.detail(), stored.size(),
                v.isValid() ? anchors.size() : v.anchorsMatched(), List.copyOf(problems),
                newestGood == null ? null : newestGood.seq(), newestGood != null && newestGood.isExported(),
                unanchored));
    }
}
