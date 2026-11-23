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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.autoarchival.model.ArchivalStatus;
import com.dlap.autoarchival.model.ArchivalTaskEntity;
import com.dlap.autoarchival.model.ArchivalTaskEntity.AssetArchivalStatusData;
import com.dlap.autoarchival.repository.ArchivalTaskRepository;

import java.time.Clock;

/**
 * Derives a task's asset statuses, counters and status from its {@code dlap_archive_rules} rows.
 *
 * <p>The rows are the record of what happened to each asset; the task is a view over them. The
 * executor, the approval listener and the proof listener each change a row and then call
 * {@link #sync}, which loads the task with a row lock and recomputes it. Because the result is
 * derived, two writers racing on one task converge on the same answer.
 *
 * <p>An asset counts as archived ({@code assetsArchivedSuccessfully}) only when its row is
 * {@code EXECUTED}, which only a provider log event can set. A rule that is in place and has not
 * yet been seen to fire is pending, not done.
 */
@Service
public class ArchiveTaskSync {

    private final ArchivalTaskRepository tasks;
    private final ArchiveRuleRepository rules;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param tasks the task repository
     * @param rules the rule rows
     * @param clock the injected clock
     */
    public ArchiveTaskSync(ArchivalTaskRepository tasks, ArchiveRuleRepository rules, Clock clock) {
        this.tasks = tasks;
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * Recomputes the task from its rows.
     *
     * @param taskId the task id; an unknown id is ignored
     */
    @Transactional
    public void sync(String taskId) {
        ArchivalTaskEntity task = tasks.findForUpdate(taskId).orElse(null);
        if (task == null || task.getAssetStatuses() == null) {
            return;
        }
        Map<String, ArchiveRuleEntity> byAsset = rules.findByTaskId(taskId).stream()
                .collect(Collectors.toMap(ArchiveRuleEntity::getAssetId, Function.identity(), (a, b) -> a));
        int executed = 0;
        int applied = 0;
        int rendered = 0;
        int failed = 0;
        int pending = 0;
        int held = 0;
        for (AssetArchivalStatusData s : task.getAssetStatuses()) {
            ArchiveRuleEntity row = byAsset.get(s.getAssetId());
            if (row != null) {
                s.setRuleId(row.getRuleId());
                s.setApprovalId(row.getApprovalId());
                s.setStatus(assetStatus(row));
                s.setErrorMessage(row.getLastError());
                if (ArchiveRuleEntity.STATUS_EXECUTED.equals(row.getStatus()) && row.getExecutedAt() != null) {
                    s.setArchivedAt(LocalDateTime.ofInstant(row.getExecutedAt(), ZoneOffset.UTC));
                }
            }
            switch (s.getStatus() == null ? "" : s.getStatus()) {
                case "EXECUTED" -> executed++;
                case "RULE_APPLIED" -> applied++;
                case "RULE_RENDERED" -> rendered++;
                case "FAILED", "REFUSED", "REJECTED", "EXPIRED", "REMOVED" -> failed++;
                // Q-711: an identical live row already holds this asset's scope; nothing is pending or failed.
                case "ALREADY_HELD" -> held++;
                default -> pending++;
            }
        }
        int total = task.getAssetStatuses().size();
        task.setAssetsArchivedSuccessfully(executed);
        task.setAssetsFailedToArchive(failed);
        task.setAssetsPendingArchival(Math.max(0, total - held - executed - failed));
        // Assets that are already held have nothing to do, so the task is judged on the others.
        ArchivalStatus next = total == held && total > 0 ? null
                : statusOf(task, total - held, executed, applied, rendered, failed, pending);
        if (next != null) {
            task.setStatus(next);
        }
        boolean terminal = pending == 0 && applied == 0 && rendered == 0;
        if (terminal && task.getCompletedAt() == null) {
            task.setCompletedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        }
        if (!terminal) {
            task.setCompletedAt(null);
        }
        tasks.save(task);
    }

    /**
     * Maps a row status to the asset status the task shows.
     *
     * @param row the rule row
     * @return the asset status
     */
    static String assetStatus(ArchiveRuleEntity row) {
        return switch (row.getStatus()) {
            case ArchiveRuleEntity.STATUS_EXECUTED -> "EXECUTED";
            case ArchiveRuleEntity.STATUS_APPLIED -> "RULE_APPLIED";
            case ArchiveRuleEntity.STATUS_RENDERED -> "RULE_RENDERED";
            case ArchiveRuleEntity.STATUS_FAILED -> "FAILED";
            case ArchiveRuleEntity.STATUS_REFUSED -> "REFUSED";
            case ArchiveRuleEntity.STATUS_REMOVED -> "REMOVED";
            default -> "PENDING";
        };
    }

    private static ArchivalStatus statusOf(ArchivalTaskEntity task, int total, int executed, int applied,
            int rendered, int failed, int pending) {
        ArchivalStatus current = task.getStatus();
        if (current == ArchivalStatus.REJECTED || current == ArchivalStatus.CANCELLED
                || current == ArchivalStatus.RESTORATION_REQUESTED || current == ArchivalStatus.RESTORATION_IN_PROGRESS
                || current == ArchivalStatus.RESTORED || current == ArchivalStatus.RESTORATION_FAILED
                || current == ArchivalStatus.RULE_REMOVED || current == ArchivalStatus.ALREADY_HELD) {
            return null;
        }
        if (pending > 0) {
            return executed + applied + rendered > 0 ? ArchivalStatus.IN_PROGRESS : current;
        }
        if (executed == total) {
            return ArchivalStatus.EXECUTED;
        }
        if (executed + applied + rendered > 0) {
            if (failed > 0) {
                return ArchivalStatus.PARTIALLY_COMPLETED;
            }
            return applied + executed > 0 ? ArchivalStatus.RULE_APPLIED : ArchivalStatus.RULE_RENDERED;
        }
        List<AssetArchivalStatusData> all = task.getAssetStatuses().stream()
                .filter(s -> !"ALREADY_HELD".equals(s.getStatus())).toList();
        boolean allRefused = all.stream().allMatch(s -> "REFUSED".equals(s.getStatus()));
        boolean allExpired = all.stream().allMatch(s -> "EXPIRED".equals(s.getStatus()));
        if (allRefused) {
            return ArchivalStatus.REFUSED;
        }
        return allExpired ? ArchivalStatus.EXPIRED : ArchivalStatus.FAILED;
    }
}
