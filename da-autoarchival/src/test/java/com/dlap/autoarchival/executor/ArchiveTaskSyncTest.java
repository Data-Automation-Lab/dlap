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

package com.dlap.autoarchival.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.autoarchival.model.ArchivalStatus;
import com.dlap.autoarchival.model.ArchivalTaskEntity;
import com.dlap.autoarchival.model.ArchivalTaskEntity.AssetArchivalStatusData;
import com.dlap.autoarchival.repository.ArchivalTaskRepository;

/** A task is a view over its rule rows: only an EXECUTED row, set from provider evidence, counts as archived. */
class ArchiveTaskSyncTest {

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), ZoneOffset.UTC);

    private final ArchivalTaskRepository tasks = mock(ArchivalTaskRepository.class);
    private final ArchiveRuleRepository rules = mock(ArchiveRuleRepository.class);
    private final ArchiveTaskSync sync = new ArchiveTaskSync(tasks, rules, CLOCK);
    private ArchivalTaskEntity task;

    @BeforeEach
    void init() {
        task = new ArchivalTaskEntity();
        task.setTaskId("t1");
        task.setStatus(ArchivalStatus.SUBMITTED);
        when(tasks.findForUpdate("t1")).thenReturn(Optional.of(task));
    }

    private void assets(String... ids) {
        List<AssetArchivalStatusData> list = new ArrayList<>();
        for (String id : ids) {
            list.add(new AssetArchivalStatusData(id, null, "PENDING", "gs://b/" + id + "/", "GCP", null, null, null));
        }
        task.setAssetStatuses(list);
    }

    private ArchiveRuleEntity row(String asset, String status) {
        ArchiveRuleEntity r = new ArchiveRuleEntity("rule-" + asset, "appr-" + asset, null, "t1", asset,
                "gs://b/" + asset + "/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "b", asset + "/", "COLDLINE", 0, T0);
        switch (status) {
            case ArchiveRuleEntity.STATUS_APPLIED, ArchiveRuleEntity.STATUS_RENDERED ->
                r.markApplied(status, "{}", false, T0);
            case ArchiveRuleEntity.STATUS_EXECUTED -> {
                r.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, T0);
                r.markExecuted("ev-1", "LifecycleTransition", Instant.parse("2026-11-02T00:00:09Z"),
                        "PROVIDER_LOG", T0);
            }
            case ArchiveRuleEntity.STATUS_FAILED, ArchiveRuleEntity.STATUS_REFUSED -> r.markFailed(status, "why", T0);
            default -> { }
        }
        return r;
    }

    @Test
    void aRuleThatIsInPlaceButNotSeenToFireIsPendingNotArchived() {
        assets("a");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_APPLIED)));

        sync.sync("t1");

        assertThat(task.getAssetStatuses().get(0).getStatus()).isEqualTo("RULE_APPLIED");
        assertThat(task.getAssetsArchivedSuccessfully()).isZero();
        assertThat(task.getAssetsPendingArchival()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.RULE_APPLIED);
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    void anExecutedRowIsTheOnlyThingThatCountsAsArchivedAndCompletesTheTask() {
        assets("a");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_EXECUTED)));

        sync.sync("t1");

        AssetArchivalStatusData s = task.getAssetStatuses().get(0);
        assertThat(s.getStatus()).isEqualTo("EXECUTED");
        assertThat(s.getRuleId()).isEqualTo("rule-a");
        assertThat(s.getApprovalId()).isEqualTo("appr-a");
        assertThat(s.getArchivedAt()).isEqualTo(LocalDateTime.parse("2026-11-02T00:00:09"));
        assertThat(task.getAssetsArchivedSuccessfully()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.EXECUTED);
        assertThat(task.getCompletedAt()).isEqualTo(LocalDateTime.parse("2026-11-02T01:00:00"));
    }

    @Test
    void aMixOfExecutedAndWaitingIsRuleAppliedAndKeepsCountingOnlyTheExecuted() {
        assets("a", "b");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_EXECUTED),
                row("b", ArchiveRuleEntity.STATUS_APPLIED)));

        sync.sync("t1");

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.RULE_APPLIED);
        assertThat(task.getAssetsArchivedSuccessfully()).isEqualTo(1);
        assertThat(task.getAssetsPendingArchival()).isEqualTo(1);
    }

    @Test
    void aRenderedRuleIsNotArchivedEither() {
        assets("a");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_RENDERED)));

        sync.sync("t1");

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.RULE_RENDERED);
        assertThat(task.getAssetsArchivedSuccessfully()).isZero();
    }

    @Test
    void failuresAndRefusalsAreCountedAndTheTaskSaysWhichKindOfEnd() {
        assets("a", "b");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_REFUSED),
                row("b", ArchiveRuleEntity.STATUS_REFUSED)));
        sync.sync("t1");
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.REFUSED);
        assertThat(task.getAssetsFailedToArchive()).isEqualTo(2);
        assertThat(task.getAssetStatuses().get(0).getErrorMessage()).isEqualTo("why");

        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_FAILED),
                row("b", ArchiveRuleEntity.STATUS_REFUSED)));
        sync.sync("t1");
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.FAILED);
    }

    @Test
    void someProgressAndSomeFailureIsPartial() {
        assets("a", "b");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_APPLIED),
                row("b", ArchiveRuleEntity.STATUS_FAILED)));

        sync.sync("t1");

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.PARTIALLY_COMPLETED);
    }

    @Test
    void aTaskWhosePlannedRowsAreStillWaitingForTheApprovalStaysPendingApproval() {
        task.setStatus(ArchivalStatus.PENDING_APPROVAL);
        assets("a");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_PLANNED)));

        sync.sync("t1");

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.PENDING_APPROVAL);
        assertThat(task.getAssetStatuses().get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void anAssetWithoutARowKeepsItsOwnFailureFromSubmission() {
        assets("a", "b");
        task.getAssetStatuses().get(1).setStatus("FAILED");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_EXECUTED)));

        sync.sync("t1");

        assertThat(task.getAssetStatuses().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.PARTIALLY_COMPLETED);
    }

    @Test
    void aRejectedOrRestoringTaskKeepsItsStatus() {
        for (ArchivalStatus s : List.of(ArchivalStatus.REJECTED, ArchivalStatus.CANCELLED,
                ArchivalStatus.RESTORATION_IN_PROGRESS, ArchivalStatus.RESTORED)) {
            task.setStatus(s);
            assets("a");
            when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_EXECUTED)));
            sync.sync("t1");
            assertThat(task.getStatus()).isEqualTo(s);
        }
    }

    @Test
    void anUnknownTaskIsIgnored() {
        when(tasks.findForUpdate("nope")).thenReturn(Optional.empty());

        sync.sync("nope");

        verify(tasks, never()).save(any());
    }

    @Test
    void theResultIsTheSameWhicheverWriterSyncsLast() {
        assets("a");
        ArchiveRuleEntity r = row("a", ArchiveRuleEntity.STATUS_APPLIED);
        when(rules.findByTaskId("t1")).thenReturn(List.of(r));
        sync.sync("t1");
        r.markExecuted("ev-1", "LifecycleTransition", Instant.parse("2026-11-02T00:00:09Z"), "PROVIDER_LOG", T0);
        sync.sync("t1");
        sync.sync("t1");

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.EXECUTED);
        assertThat(task.getAssetsArchivedSuccessfully()).isEqualTo(1);
    }

    // ---- Q-711 and Q-708: the two end states that are neither a success nor a failure ------------------

    private void asset(String id, String status) {
        AssetArchivalStatusData data = new AssetArchivalStatusData(id, null, status, "gs://b/" + id + "/", "GCP", null,
                null, null);
        if (task.getAssetStatuses() == null) {
            task.setAssetStatuses(new ArrayList<>());
        }
        task.getAssetStatuses().add(data);
    }

    @Test
    void q711_anAlreadyHeldAssetIsNeitherPendingNorFailedAndTheTaskIsJudgedOnTheOthers() {
        asset("h", "ALREADY_HELD");
        asset("a", "PENDING");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_APPLIED)));

        sync.sync("t1");

        assertThat(task.getAssetStatuses().get(0).getStatus()).as("no row, so the status stays").isEqualTo("ALREADY_HELD");
        assertThat(task.getAssetsFailedToArchive()).isZero();
        assertThat(task.getAssetsPendingArchival()).as("only the applied rule waits for proof").isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.RULE_APPLIED);
    }

    @Test
    void q711_anAlreadyHeldAssetBesideAnExecutedOneLeavesTheTaskExecuted() {
        asset("h", "ALREADY_HELD");
        asset("a", "PENDING");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_EXECUTED)));

        sync.sync("t1");

        assertThat(task.getStatus()).as("the held asset does not stop the others being all executed")
                .isEqualTo(ArchivalStatus.EXECUTED);
        assertThat(task.getAssetsArchivedSuccessfully()).isEqualTo(1);
    }

    @Test
    void q711_aHeldAssetBesideARefusedOneIsRefusedNotFailed() {
        asset("h", "ALREADY_HELD");
        asset("a", "PENDING");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_REFUSED)));

        sync.sync("t1");

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.REFUSED);
        assertThat(task.getAssetsFailedToArchive()).isEqualTo(1);
    }

    @Test
    void q711_aTaskThatIsAllAlreadyHeldKeepsItsStatusAndCompletes() {
        task.setStatus(ArchivalStatus.ALREADY_HELD);
        asset("h", "ALREADY_HELD");
        when(rules.findByTaskId("t1")).thenReturn(List.of());

        sync.sync("t1");

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.ALREADY_HELD);
        assertThat(task.getAssetsFailedToArchive()).isZero();
        assertThat(task.getAssetsPendingArchival()).isZero();
        assertThat(task.getCompletedAt()).isNotNull();
    }

    @Test
    void q708_aRuleRemovedTaskIsNotRecomputedBackToAnotherStatusWhenARowIsSynced() {
        task.setStatus(ArchivalStatus.RULE_REMOVED);
        asset("a", "RULE_REMOVED");
        when(rules.findByTaskId("t1")).thenReturn(List.of(row("a", ArchiveRuleEntity.STATUS_REMOVED)));

        sync.sync("t1");

        assertThat(task.getStatus()).isEqualTo(ArchivalStatus.RULE_REMOVED);
    }
}
