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

import static com.dlap.autodelete.retire.Approvals.ALICE;
import static com.dlap.autodelete.retire.Approvals.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.model.DeletionStatus;
import com.dlap.autodelete.model.DeletionTaskEntity;
import com.dlap.autodelete.model.DeletionTaskEntity.AssetDeletionStatusData;
import com.dlap.autodelete.repository.DeletionTaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RET-02: task assets enter their window only once their own approval is executable.
 */
class TaskApprovalProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final String SUB = Approvals.CAROL;

    private final DeletionTaskRepository tasks = mock(DeletionTaskRepository.class);
    private final ApprovalClient approvals = mock(ApprovalClient.class);
    private final SoftDeleteAdmission admission = mock(SoftDeleteAdmission.class);
    private final RetireAuditLog audit = mock(RetireAuditLog.class);
    private final TaskApprovalProcessor processor = new TaskApprovalProcessor(tasks, approvals, admission, audit,
            RetireProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static DeletionTaskEntity task(AssetDeletionStatusData... rows) {
        DeletionTaskEntity t = new DeletionTaskEntity();
        t.setTaskId("t1");
        t.setStatus(DeletionStatus.PENDING_APPROVAL);
        t.setRequestedBy(SUB);
        t.setAssetStatuses(List.of(rows));
        return t;
    }

    private static AssetDeletionStatusData awaiting(UUID asset, UUID approval) {
        return new AssetDeletionStatusData(asset.toString(), null, TaskApprovalProcessor.AWAITING_APPROVAL, "SOFT", null,
                null, approval.toString(), null);
    }

    /** Q-632: a task row that cannot be processed because its ids are not valid is refused and audited. */
    @Test
    void aTaskRowWithoutValidIdsIsRefusedAndWritesARefusalRecord() {
        AssetDeletionStatusData bad = new AssetDeletionStatusData("not-a-uuid", null,
                TaskApprovalProcessor.AWAITING_APPROVAL, "SOFT", null, null, null, null);
        DeletionTaskEntity t = task(bad);

        processor.process(t);

        assertThat(t.getAssetStatuses().get(0).getStatus()).isEqualTo(TaskApprovalProcessor.REFUSED);
        verify(audit).refused(eq(NOW), eq(RetireAuditLog.SERVICE), eq("not-a-uuid"), eq("t1"), eq("task"),
                eq(RefusalReason.INVALID), anyString(), any());
    }

    @Test
    void aPendingApprovalKeepsTheTaskWaiting() {
        UUID asset = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        when(approvals.read(approval)).thenReturn(Optional.of(view(approval, asset, "PENDING", "SOFT_DELETE", 1, SUB, null)));
        DeletionTaskEntity t = task(awaiting(asset, approval));

        processor.process(t);

        assertThat(t.getStatus()).isEqualTo(DeletionStatus.PENDING_APPROVAL);
        assertThat(t.getNextCheckAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        verify(admission, never()).admitTaskAsset(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void anExecutableApprovalAdmitsTheAssetWithTheSubmitterExcluded() {
        UUID asset = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        UUID record = UUID.randomUUID();
        when(approvals.read(approval)).thenReturn(Optional.of(view(approval, asset, "APPROVED", "SOFT_DELETE", 1, SUB,
                null, ALICE)));
        when(admission.admitTaskAsset(eq("t1"), eq(asset), eq(approval), eq(SUB), any(), eq(false), any()))
                .thenReturn(new SoftDeleteAdmission.Outcome(record, null, true));
        DeletionTaskEntity t = task(awaiting(asset, approval));

        processor.process(t);

        assertThat(t.getStatus()).isEqualTo(DeletionStatus.COMPLETED);
        assertThat(t.getAssetStatuses()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(TaskApprovalProcessor.SOFT_DELETED);
            assertThat(r.getSoftDeleteRecordId()).isEqualTo(record.toString());
        });
        verify(tasks).save(t);
    }

    @Test
    void rejectedApprovalsEndTheTaskRejectedAndMixedOutcomesPartiallyCompleted() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID approvalA = UUID.randomUUID();
        UUID approvalB = UUID.randomUUID();
        when(approvals.read(approvalA)).thenReturn(Optional.of(view(approvalA, a, "REJECTED", "SOFT_DELETE", 1, SUB, null)));
        when(approvals.read(approvalB)).thenReturn(Optional.of(view(approvalB, b, "REJECTED", "SOFT_DELETE", 1, SUB, null)));
        when(admission.admitTaskAsset(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SoftDeleteAdmission.Outcome(null, RefusalReason.APPROVAL_REJECTED, false));
        DeletionTaskEntity rejected = task(awaiting(a, approvalA), awaiting(b, approvalB));
        processor.process(rejected);
        assertThat(rejected.getStatus()).isEqualTo(DeletionStatus.REJECTED);

        when(admission.admitTaskAsset(any(), eq(a), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SoftDeleteAdmission.Outcome(UUID.randomUUID(), null, true));
        DeletionTaskEntity mixed = task(awaiting(a, approvalA), awaiting(b, approvalB));
        processor.process(mixed);
        assertThat(mixed.getStatus()).isEqualTo(DeletionStatus.PARTIALLY_COMPLETED);
        assertThat(mixed.getAssetsProcessedSuccessfully()).isEqualTo(1);
        assertThat(mixed.getAssetsFailedToProcess()).isEqualTo(1);
    }

    @Test
    void anUnreachablePolicyEngineKeepsTheTaskWaiting() {
        UUID asset = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        when(approvals.read(approval)).thenThrow(new RetireRemoteException("down", true, null));
        DeletionTaskEntity t = task(awaiting(asset, approval));

        processor.process(t);

        assertThat(t.getStatus()).isEqualTo(DeletionStatus.PENDING_APPROVAL);
        assertThat(t.getAssetStatuses().get(0).getStatus()).isEqualTo(TaskApprovalProcessor.AWAITING_APPROVAL);
    }
}
