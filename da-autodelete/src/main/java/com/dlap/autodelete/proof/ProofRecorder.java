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

package com.dlap.autodelete.proof;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.retire.CanonicalTarget;
import com.dlap.autodelete.retire.RetireAuditLog;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.proto.log.v1.DlapLogEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks a disposition {@code EXECUTED}, and its soft-delete row {@code HARD_DELETED} (or
 * {@code RULE_IN_FORCE}, below), when the provider's own delete event proves it
 * ({@link DeletionProofMatcher}). Nothing else ever does: an applied rule or a successful engine
 * call only makes a row {@code AWAITING_PROOF}.
 *
 * <p><b>What the proof means.</b> For an object, a blob or a table the target is gone, so the row is
 * {@code HARD_DELETED}. For a lifecycle rule on a whole bucket, prefix or container
 * ({@link NativeRulePlatform#containerRule()}) the first proven child delete shows that the provider's
 * rule fires; the bucket stays and other objects may not have expired yet. The disposition is
 * {@code EXECUTED} with that proof, and the row is {@code RULE_IN_FORCE} (Q-631, ADR-0107), never
 * {@code HARD_DELETED}.
 *
 * <p>The row's {@code hard_deleted_at} is the provider's event time (business time, rule R6), and the
 * audit record {@code retire.delete.executed} carries it as {@code occurredAt}. A repeated event
 * changes nothing (the disposition is no longer awaiting proof).
 */
@Component
public class ProofRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(ProofRecorder.class);

    private static final List<String> OPEN = List.of(RetireDispositionEntity.AWAITING_PROOF,
            RetireDispositionEntity.PROOF_OVERDUE);

    private final RetireDispositionRepository dispositions;
    private final SoftDeleteRecordRepository records;
    private final RetireAuditLog audit;
    private final RetireProperties properties;
    private final Clock clock;

    /**
     * Creates the recorder.
     *
     * @param dispositions the dispositions
     * @param records      the soft-delete rows
     * @param audit        the audit log
     * @param properties   the settings
     * @param clock        the clock
     */
    public ProofRecorder(RetireDispositionRepository dispositions, SoftDeleteRecordRepository records,
            RetireAuditLog audit, RetireProperties properties, Clock clock) {
        this.dispositions = dispositions;
        this.records = records;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Applies one delete event.
     *
     * @param event the decoded event
     * @return the number of dispositions it proved
     */
    @Transactional
    public int onEvent(DlapLogEvent event) {
        String uri = event.hasTargetResource() ? event.getTargetResource().getCanonicalUri() : "";
        if (uri.isEmpty()) {
            return 0;
        }
        int proved = 0;
        for (RetireDispositionEntity candidate : dispositions.findByTargetUriInAndStatusIn(
                CanonicalTarget.selfAndParents(uri), OPEN)) {
            Optional<String> source = DeletionProofMatcher.match(event, candidate, properties.proof().clockSkew());
            if (source.isEmpty()) {
                continue;
            }
            RetireDispositionEntity d = dispositions.findByIdForUpdate(candidate.getId()).orElse(null);
            if (d == null || !OPEN.contains(d.getStatus())) {
                continue;
            }
            Instant eventTime = Instant.ofEpochSecond(event.getEventTime().getSeconds(), event.getEventTime().getNanos());
            Instant now = clock.instant();
            d.setStatus(RetireDispositionEntity.EXECUTED);
            d.setProofEventId(event.getEventId().isEmpty() ? event.getNativeEventId() : event.getEventId());
            d.setProofEventTime(eventTime);
            d.setProofSource(source.get());
            d.setExecutedAt(now);
            d.setUpdatedAt(now);
            dispositions.saveAndFlush(d);

            // A rule on a whole bucket, prefix or container is proven to fire by its first child delete; the
            // container stays and other objects may not have expired yet, so the record is not HARD_DELETED
            // (Q-631). A table, an object or a blob is gone.
            boolean ruleInForce = NativeRulePlatform.isContainerRule(d.getPlatform());
            SoftDeleteRecordEntity row = records.findByIdForUpdate(d.getSoftDeleteRecordId()).orElse(null);
            // RET-06: a container rule waiting for its proof can be restored (the rule is removed). A late proof of
            // what the rule deleted before then proves the disposition, but never moves the record out of
            // RESTORED, RULE_ENDED, RULE_ENDED_UNPROVEN (RET-08) or RESTORE_FAILED.
            String recordState = row == null ? null : row.getStatus();
            if (row != null && SoftDeleteRecordEntity.AWAITING_PROOF.equals(row.getStatus())) {
                row.setStatus(ruleInForce ? SoftDeleteRecordEntity.RULE_IN_FORCE : SoftDeleteRecordEntity.HARD_DELETED);
                row.setHardDeletedAt(ruleInForce ? null : eventTime);
                row.setNextCheckAt(null);
                row.setLastError(null);
                row.setUpdatedAt(now);
                records.saveAndFlush(row);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("recordId", d.getSoftDeleteRecordId());
            details.put("dispositionId", d.getId());
            details.put("assetId", d.getAssetId());
            details.put("mode", d.getMode());
            details.put("platform", d.getPlatform());
            details.put("recordState", row == null || SoftDeleteRecordEntity.AWAITING_PROOF.equals(recordState)
                    ? (ruleInForce ? SoftDeleteRecordEntity.RULE_IN_FORCE : SoftDeleteRecordEntity.HARD_DELETED)
                    : recordState);
            details.put("hardDeleteApprovalId", d.getApprovalId());
            details.put("proofEventId", d.getProofEventId());
            details.put("proofSource", source.get());
            details.put("proofTargetUri", uri);
            details.put("appliedAt", d.getAppliedAt());
            audit.record(RetireAuditLog.EXECUTED, eventTime, RetireAuditLog.SERVICE, d.getTargetUri(),
                    AuditOutcome.SUCCEEDED, d.getSoftDeleteRecordId().toString(), details);
            LOG.info("Proof seen for {} (record {}): {} event {} at {}", d.getTargetUri(), d.getSoftDeleteRecordId(),
                    source.get(), d.getProofEventId(), eventTime);
            proved++;
        }
        return proved;
    }
}
