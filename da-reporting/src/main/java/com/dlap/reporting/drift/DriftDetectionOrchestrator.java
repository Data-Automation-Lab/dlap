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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.reporting.model.AccessDecisionRecordEntity;
import com.dlap.reporting.model.AssetReadRecordEntity;
import com.dlap.reporting.model.DriftFindingEntity;
import com.dlap.reporting.repository.AccessDecisionRecordRepository;
import com.dlap.reporting.repository.AssetReadRecordRepository;
import com.dlap.reporting.repository.DriftFindingRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stateful wiring around the pure {@link DriftDetectionService} (mirrors the
 * consumer-to-recorder pattern of {@code PolicyActionEventRecorder}, ADR-0033/0035).
 *
 * <p>Both sides of the join are buffered as they arrive, under canonical keys
 * ({@link DriftEventProjector}): the rolled-up canonical asset URI and the DLAP subject.
 * <ul>
 *   <li>{@link #recordReadAndDetect} buffers a read (idempotent on {@code eventId}) and
 *       reconciles its findings against the decisions buffered so far.</li>
 *   <li>{@link #recordDecision} buffers a decision (idempotent on {@code decisionId}) and
 *       reconciles every buffered read it applies to: reads of the same key in
 *       {@code [decidedAt, decidedAt + window]}.</li>
 * </ul>
 *
 * <p>So the findings are a function of the buffered events, not of their arrival order: an ALLOW
 * consumed after its read retracts the read's {@code UNENFORCED_READ}, and a DENY consumed late
 * turns it into an {@code ENFORCEMENT_BYPASS} (enforcement-contract.md section 6.1). Each
 * transaction first takes the advisory lock of its {@code (asset, subject)} key
 * ({@link DriftJoinRepository#lockJoinKey}), so a read and a decision committed concurrently by
 * two listeners or two replicas still see each other.
 *
 * <p>Received and detected times come from the injected {@link Clock}; business times come from
 * the events.
 */
@Service
public class DriftDetectionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DriftDetectionOrchestrator.class);

    /** Counter incremented once per DriftFinding persisted, tagged by drift type. */
    public static final String DRIFT_FINDINGS_COUNTER = "dlap.drift.findings.total";

    /** Counter incremented once per DriftFinding removed because a late decision explained the read, tagged by drift type. */
    public static final String DRIFT_FINDINGS_RETRACTED_COUNTER = "dlap.drift.findings.retracted";

    private final DriftDetectionService driftDetectionService;
    private final AccessDecisionRecordRepository decisionRepository;
    private final AssetReadRecordRepository readRepository;
    private final DriftFindingRepository findingRepository;
    private final DriftJoinRepository joinRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * Creates the orchestrator.
     *
     * @param driftDetectionService the pure correlation rules
     * @param decisionRepository    buffered decisions
     * @param readRepository        buffered reads
     * @param findingRepository     stored findings
     * @param joinRepository        the join lock and window queries
     * @param meterRegistry         where findings are counted
     * @param clocks                the service clock; the system UTC clock when none is defined
     */
    public DriftDetectionOrchestrator(DriftDetectionService driftDetectionService,
                                      AccessDecisionRecordRepository decisionRepository,
                                      AssetReadRecordRepository readRepository,
                                      DriftFindingRepository findingRepository,
                                      DriftJoinRepository joinRepository,
                                      MeterRegistry meterRegistry,
                                      ObjectProvider<Clock> clocks) {
        this.driftDetectionService = driftDetectionService;
        this.decisionRepository = decisionRepository;
        this.readRepository = readRepository;
        this.findingRepository = findingRepository;
        this.joinRepository = joinRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Buffers a PDP decision and reconciles the buffered reads it applies to. Idempotent on
     * {@code decisionId}: a redelivered decision changes nothing.
     *
     * @param decision the decision, keys already canonical
     * @return the findings newly persisted because of this decision (a late DENY); may be empty
     */
    @Transactional
    public List<DriftFinding> recordDecision(DecisionRecord decision) {
        if (decision == null || decision.decisionId() == null || decision.assetId() == null
                || decision.subjectId() == null || decision.decidedAt() == null) {
            return List.of();
        }
        joinRepository.lockJoinKey(joinLockKey(decision.assetId(), decision.subjectId()));
        if (decisionRepository.existsByDecisionId(decision.decisionId())) {
            return List.of();
        }
        AccessDecisionRecordEntity row = new AccessDecisionRecordEntity();
        row.setDecisionId(decision.decisionId());
        row.setSubjectId(decision.subjectId());
        row.setAssetId(decision.assetId());
        row.setAllowed(decision.allow());
        row.setDecidedAt(decision.decidedAt());
        row.setReceivedAt(clock.instant());
        decisionRepository.save(row);

        Instant until = decision.decidedAt().plus(window());
        List<DriftFinding> persisted = new ArrayList<>();
        for (AssetReadRecordEntity read : joinRepository.findReadsInWindow(
                decision.assetId(), decision.subjectId(), decision.decidedAt(), until)) {
            persisted.addAll(reconcile(new ReadRecord(read.getEventId(), read.getSubjectId(),
                    read.getAssetId(), read.getReadAt())));
        }
        return persisted;
    }

    /**
     * Buffers a read and runs drift detection for it. Idempotent on the read {@code eventId}; a
     * redelivered read neither re-buffers nor re-writes findings.
     *
     * @param read the read, keys already canonical
     * @return the findings newly persisted for this read (may be empty)
     */
    @Transactional
    public List<DriftFinding> recordReadAndDetect(ReadRecord read) {
        if (read == null || read.eventId() == null
                || read.assetId() == null || read.subjectId() == null
                || read.readAt() == null) {
            return List.of();
        }
        joinRepository.lockJoinKey(joinLockKey(read.assetId(), read.subjectId()));
        if (readRepository.existsByEventId(read.eventId())) {
            return List.of();
        }

        AssetReadRecordEntity row = new AssetReadRecordEntity();
        row.setEventId(read.eventId());
        row.setSubjectId(read.subjectId());
        row.setAssetId(read.assetId());
        row.setReadAt(read.readAt());
        row.setReceivedAt(clock.instant());
        readRepository.save(row);

        return reconcile(read);
    }

    /**
     * Makes the stored findings of one read equal to what the buffered decisions say: inserts a
     * missing finding, updates a changed one, deletes one the decisions no longer support.
     *
     * @return the findings inserted
     */
    private List<DriftFinding> reconcile(ReadRecord read) {
        long window = driftDetectionService.windowSeconds();
        Instant since = read.readAt().minus(Duration.ofSeconds(window));
        List<DecisionRecord> decisions = decisionRepository
                .findByAssetIdAndSubjectIdAndDecidedAtGreaterThanEqual(read.assetId(), read.subjectId(), since)
                .stream()
                .map(d -> new DecisionRecord(d.getDecisionId(), d.getSubjectId(),
                        d.getAssetId(), d.isAllowed(), d.getDecidedAt()))
                .toList();
        List<DriftFinding> desired = driftDetectionService.detect(decisions, List.of(read), window);

        List<DriftFindingEntity> existing = joinRepository.findFindingsForRead(read.eventId());
        for (DriftFindingEntity stored : existing) {
            Optional<DriftFinding> same = desired.stream().filter(f -> f.type() == stored.getDriftType()).findFirst();
            if (same.isEmpty()) {
                findingRepository.delete(stored);
                meterRegistry.counter(DRIFT_FINDINGS_RETRACTED_COUNTER, "type", stored.getDriftType().name())
                        .increment();
                log.info("Drift finding retracted: type={} asset={} readEventId={}",
                        stored.getDriftType(), stored.getAssetId(), read.eventId());
            } else if (!Objects.equals(stored.getDecisionId(), same.get().decisionId())
                    || !Objects.equals(stored.getDetail(), same.get().detail())) {
                stored.setDecisionId(same.get().decisionId());
                stored.setDetail(same.get().detail());
                findingRepository.save(stored);
            }
        }

        List<DriftFinding> persisted = new ArrayList<>();
        for (DriftFinding finding : desired) {
            boolean present = existing.stream().anyMatch(e -> e.getDriftType() == finding.type());
            if (present) {
                continue;
            }
            DriftFindingEntity entity = new DriftFindingEntity();
            entity.setDriftType(finding.type());
            entity.setSubjectId(finding.subjectId());
            entity.setAssetId(finding.assetId());
            entity.setDecisionId(finding.decisionId());
            entity.setReadEventId(read.eventId());
            entity.setReadAt(finding.readAt());
            entity.setDetail(finding.detail());
            entity.setDetectedAt(clock.instant());
            findingRepository.save(entity);
            persisted.add(finding);
            meterRegistry.counter(DRIFT_FINDINGS_COUNTER, "type", finding.type().name()).increment();
            log.warn("Drift detected: type={} asset={} readEventId={}",
                    finding.type(), finding.assetId(), read.eventId());
        }
        return persisted;
    }

    private Duration window() {
        return Duration.ofSeconds(driftDetectionService.windowSeconds());
    }

    /**
     * The advisory lock key of one join key: the first 8 bytes of SHA-256 over
     * {@code asset NUL subject}. Collisions only serialize two unrelated keys.
     *
     * @param assetId   the asset key
     * @param subjectId the subject key
     * @return the 64-bit lock key
     */
    static long joinLockKey(String assetId, String subjectId) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest((assetId + '\u0000' + subjectId).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
