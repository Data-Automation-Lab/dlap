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

package com.dlap.autodelete.retire;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.common.audit.AuditOutcome;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code retire.delete.apply} SUCCEEDED record that did not land right after the provider call (Q-633).
 *
 * <p>When the provider call succeeded but the outcome append failed, the disposition is saved as applied with
 * {@code success_audit_pending}. The sweeper writes the missing record on its next pass; a restore or the end of a
 * rule writes it first, before the rule is removed or the record changes state (RET-08, Q-699, decision D38), so
 * the chain never holds a removal of a rule whose apply it does not show as done. Never calls the provider.
 */
final class PendingApplyOutcome {

    private PendingApplyOutcome() {
    }

    /**
     * Writes the pending SUCCEEDED record of a disposition, if it has one, and clears the flag. A failed append
     * propagates and the flag stays set, so the caller's transaction decides: the sweeper retries later, a removal
     * removes nothing.
     *
     * @param audit        the audit log
     * @param dispositions the dispositions
     * @param row          the record, locked by the caller
     * @param d            the record's disposition
     * @param now          the time of the write
     * @return true when a pending record was written, false when nothing was pending
     */
    static boolean write(RetireAuditLog audit, RetireDispositionRepository dispositions, SoftDeleteRecordEntity row,
            RetireDispositionEntity d, Instant now) {
        if (d == null || !d.isSuccessAuditPending()) {
            return false;
        }
        audit.record(RetireAuditLog.APPLY, d.getAppliedAt(), RetireAuditLog.SERVICE, row.getAssetUri(),
                AuditOutcome.SUCCEEDED, row.getId().toString(), details(row, d));
        d.setSuccessAuditPending(false);
        d.setUpdatedAt(now);
        dispositions.saveAndFlush(d);
        return true;
    }

    /** The details of the retried record: what the first append would have carried, marked as retried. */
    private static Map<String, Object> details(SoftDeleteRecordEntity row, RetireDispositionEntity d) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("recordId", row.getId());
        details.put("assetId", row.getAssetId());
        details.put("origin", row.getOrigin());
        details.put("softDeleteApprovalId", row.getApprovalId());
        details.put("windowDays", row.getWindowDays() == null ? null : row.getWindowDays().longValue());
        details.put("dispositionId", d.getId());
        details.put("mode", d.getMode());
        details.put("platform", d.getPlatform());
        details.put("ruleId", d.getRuleId());
        details.put("ruleDocumentSha256", HardDeleteExecutor.sha256(d.getRuleDocument()));
        details.put("dryRun", false);
        details.put("hardDeleteApprovalId", d.getApprovalId());
        if (d.getApprovers() != null) {
            details.put("approvers", List.of(d.getApprovers().split(",")));
        }
        details.put("proofDueAt", d.getProofDueAt());
        details.put("retriedOutcome", true);
        return details;
    }
}
