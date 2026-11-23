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

package com.dlap.autoarchival.reconcile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Looks for archive rules that stay {@code APPLIED} without proof (Q-607). It reads and reports; it changes
 * no row, calls no provider and writes no audit record.
 *
 * <p>An archive row is {@code APPLIED} when DLAP wrote the native lifecycle rule and read it back. It becomes
 * {@code EXECUTED} only when a provider log event shows an object moved. A row that never leaves
 * {@code APPLIED} means the rule has not fired yet (nothing old enough), the provider logs nothing for
 * lifecycle actions, or the log does not reach DLAP. The job counts those rows, logs the oldest ones and
 * exposes the count, so an operator sees the gap instead of a rule that is silently trusted.
 *
 * <p>It also counts rows stuck in {@code EXECUTING} (a worker died between claiming the row and writing its
 * outcome). Such a row is never run again by itself: whether the provider holds the rule is for a person to check.
 *
 * <p>The job is a cluster-wide singleton (ShedLock, database time, plan rule R7); the age is measured on the
 * injected clock (rule R6). Metrics: {@value #UNPROVEN_METRIC} and {@value #STUCK_METRIC}, as left by the replica
 * that ran last; alert on the maximum across replicas.
 */
@Component
@ConditionalOnProperty(prefix = "dlap.autoarchival.reconcile", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ArchiveRuleReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveRuleReconciler.class);

    /** The ShedLock name of the job (the lock table holds 64 characters). */
    public static final String LOCK_NAME = "autoarchival-rule-reconcile";
    /** The most APPLIED rows one pass reads; more than this is logged and the count is a floor. */
    static final int SCAN_LIMIT = 5000;
    /** Gauge: rules applied longer than the maximum age plus their own age with no provider proof. */
    public static final String UNPROVEN_METRIC = "dlap.autoarchival.archive.rules.unproven";
    /** Gauge: rows claimed longer than the claim timeout ago whose worker never wrote an outcome. */
    public static final String STUCK_METRIC = "dlap.autoarchival.archive.rules.stuck";

    /**
     * What one run found.
     *
     * @param unproven rules {@code APPLIED} longer than the maximum age without proof
     * @param stuck    rows {@code EXECUTING} longer than the claim timeout
     */
    public record Result(long unproven, long stuck) {
    }

    private final ArchiveRuleRepository rules;
    private final ArchiveReconcileProperties properties;
    private final Clock clock;
    private final AtomicLong unproven = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();

    /**
     * Creates the job and registers its gauges.
     *
     * @param rules      the rule rows
     * @param properties the settings
     * @param clock      the injected clock
     * @param registry   the meter registry
     */
    public ArchiveRuleReconciler(ArchiveRuleRepository rules, ArchiveReconcileProperties properties, Clock clock,
            MeterRegistry registry) {
        this.rules = rules;
        this.properties = properties;
        this.clock = clock;
        Gauge.builder(UNPROVEN_METRIC, unproven, AtomicLong::get)
                .description("Archive rules APPLIED longer than the maximum age with no provider proof")
                .register(registry);
        Gauge.builder(STUCK_METRIC, stuck, AtomicLong::get)
                .description("Archive rows EXECUTING longer than the claim timeout (a worker died before its outcome)")
                .register(registry);
    }

    /** The scheduled entry point: one replica at a time. */
    @Scheduled(fixedDelayString = "${dlap.autoarchival.reconcile.interval:PT1H}",
            initialDelayString = "${dlap.autoarchival.reconcile.initial-delay:PT5M}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "${dlap.autoarchival.reconcile.lock-at-most-for:PT10M}",
            lockAtLeastFor = "PT1M")
    public void scheduledReconcile() {
        reconcile();
    }

    /**
     * One pass: counts, logs the oldest rows of each list and updates the gauges.
     *
     * @return what the pass found
     */
    public Result reconcile() {
        Instant now = clock.instant();
        Instant appliedCutoff = now.minus(properties.maxApplied());
        Instant claimCutoff = now.minus(properties.claimTimeout());
        PageRequest bound = PageRequest.of(0, properties.logLimit());

        // Q-672: a rule with afterDays = 90 cannot fire before its objects are 90 days old, so it only counts as
        // unproven after max-applied PLUS its own after_days. The query bounds by max-applied; the age is per row.
        List<ArchiveRuleEntity> candidates = rules.findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(
                ArchiveRuleEntity.STATUS_APPLIED, appliedCutoff, PageRequest.of(0, SCAN_LIMIT));
        List<ArchiveRuleEntity> overdue = candidates.stream()
                .filter(r -> r.getAppliedAt() != null && r.getAppliedAt().plus(Duration.ofDays(r.getAfterDays()))
                        .isBefore(appliedCutoff))
                .toList();
        long unprovenCount = overdue.size();
        if (candidates.size() == SCAN_LIMIT) {
            LOG.warn("the unproven-rule scan read its limit of {} rows; the count may be low", SCAN_LIMIT);
        }
        if (unprovenCount > 0) {
            List<ArchiveRuleEntity> oldest = overdue.stream().limit(properties.logLimit()).toList();
            LOG.warn("{} archive rule(s) APPLIED for more than {} plus their own age with no provider proof of a "
                    + "transition; showing the oldest {}", unprovenCount, properties.maxApplied(), oldest.size());
            for (ArchiveRuleEntity r : oldest) {
                LOG.warn("archive rule {} ({} {}/{} to {}, {} days) applied at {} has no proof yet", r.getRuleId(),
                        r.getProvider(), r.getScopeBucket(), r.getScopePrefix(), r.getNativeClass(),
                        r.getAfterDays(), r.getAppliedAt());
            }
        }
        long stuckCount = rules.countByStatusAndUpdatedAtBefore(ArchiveRuleEntity.STATUS_EXECUTING, claimCutoff);
        if (stuckCount > 0) {
            List<ArchiveRuleEntity> oldest = rules.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                    ArchiveRuleEntity.STATUS_EXECUTING, claimCutoff, bound);
            LOG.error("{} archive row(s) EXECUTING for more than {}: the worker stopped before it wrote an outcome. "
                    + "Check the provider and the audit log; DLAP does not run them again; showing the oldest {}",
                    stuckCount, properties.claimTimeout(), oldest.size());
            for (ArchiveRuleEntity r : oldest) {
                LOG.error("archive rule {} (approval {}) was claimed at {} and has no outcome", r.getRuleId(),
                        r.getApprovalId(), r.getUpdatedAt());
            }
        }
        unproven.set(unprovenCount);
        stuck.set(stuckCount);
        return new Result(unprovenCount, stuckCount);
    }
}
