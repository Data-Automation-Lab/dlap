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

import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.common.audit.AuditOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ends DLAP's bucket, prefix and container rules after a maximum age (RET-06, Q-677; RET-08, Q-695). Such a rule
 * keeps deleting matching objects, new ones included, until it is removed. When
 * {@code dlap.autodelete.rule-in-force.max-age} (default 30 days) has passed since the rule was applied, the
 * executor removes the rule itself:
 *
 * <ul>
 *   <li>a {@code RULE_IN_FORCE} record (the rule is proven to fire) ends {@code RULE_ENDED}, audited
 *       {@code retire.delete.rule_ended};</li>
 *   <li>an applied container rule that no provider event ever proved (the record still {@code AWAITING_PROOF},
 *       the disposition {@code AWAITING_PROOF} or {@code PROOF_OVERDUE}) ends {@code RULE_ENDED_UNPROVEN}
 *       (decision D38), audited {@code retire.delete.rule_ended_unproven}, logged as a warning and counted by
 *       {@value #ENDED_UNPROVEN_METRIC}.</li>
 * </ul>
 *
 * <p>Either way:
 * <ul>
 *   <li>Removing a rule deletes nothing, so it needs no approval. It is audited like every effect: REQUESTED
 *       before the removal, SUCCEEDED or FAILED after. A pending apply SUCCEEDED record is written first
 *       (Q-699, in {@link RuleRemover}).</li>
 *   <li>A platform that refuses or does not answer leaves the record as it was; it is tried again after
 *       {@code dlap.autodelete.rule-in-force.retry-after} ({@code rule_end_retry_at}; the sweeper's
 *       {@code next_check_at} is never touched).</li>
 *   <li>A removal that finds no matching rule (Q-682) ends the record {@code RESTORE_FAILED}: the rule may still
 *       be there in another form, and a person has to look.</li>
 * </ul>
 *
 * <p>The age is measured on the injected clock (rule R6) from the disposition's {@code applied_at}. The job is a
 * cluster-wide singleton (ShedLock {@value #LOCK_NAME}, database time, rule R7) and claims each row with
 * {@code FOR UPDATE SKIP LOCKED} in its own transaction, so a restore or a sweep that holds the row is never
 * raced.
 *
 * <p>Metrics: {@value #IN_FORCE_METRIC} counts the records in force and {@value #RESTORE_FAILED_METRIC} the
 * records whose rule could not be found, both read from the database at each scrape;
 * {@value #ENDED_UNPROVEN_METRIC} counts the unproven rules this replica removed.
 */
@Component
public class RuleInForceExpiry {

    private static final Logger LOG = LoggerFactory.getLogger(RuleInForceExpiry.class);

    /** The ShedLock name of the job (the lock table holds 64 characters). */
    public static final String LOCK_NAME = "autodelete-rule-in-force-end";
    /** Gauge: records in {@code RULE_IN_FORCE}. */
    public static final String IN_FORCE_METRIC = "dlap.autodelete.rules.in_force";
    /** Gauge: records in {@code RESTORE_FAILED}. */
    public static final String RESTORE_FAILED_METRIC = "dlap.autodelete.rules.restore_failed";
    /** Counter: applied container rules removed at their maximum age without ever being proven (Q-695). */
    public static final String ENDED_UNPROVEN_METRIC = "dlap.autodelete.rules.ended_unproven";

    private final SoftDeleteRecordRepository records;
    private final RuleRemover remover;
    private final RetireAuditLog audit;
    private final RetireProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Counter endedUnproven;

    /**
     * Creates the job and registers its meters.
     *
     * @param records    the soft-delete rows
     * @param remover    removes a record's rule
     * @param audit      the audit log
     * @param properties the settings
     * @param clock      the injected clock
     * @param txManager  the transaction manager
     * @param registry   the meter registry
     */
    public RuleInForceExpiry(SoftDeleteRecordRepository records, RuleRemover remover, RetireAuditLog audit,
            RetireProperties properties, Clock clock, PlatformTransactionManager txManager, MeterRegistry registry) {
        this.records = records;
        this.remover = remover;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        Gauge.builder(IN_FORCE_METRIC, records, r -> r.countByStatus(SoftDeleteRecordEntity.RULE_IN_FORCE))
                .description("Delete records whose bucket, prefix or container rule is in force")
                .strongReference(true)
                .register(registry);
        Gauge.builder(RESTORE_FAILED_METRIC, records, r -> r.countByStatus(SoftDeleteRecordEntity.RESTORE_FAILED))
                .description("Delete records whose rule DLAP could not find to remove (a person has to check)")
                .strongReference(true)
                .register(registry);
        this.endedUnproven = Counter.builder(ENDED_UNPROVEN_METRIC)
                .description("Applied bucket, prefix or container rules removed at their maximum age, never proven")
                .register(registry);
    }

    /** The scheduled entry point: one replica at a time. */
    @Scheduled(fixedDelayString = "${dlap.autodelete.rule-in-force.interval:PT1H}",
            initialDelayString = "${dlap.autodelete.rule-in-force.initial-delay:PT5M}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtMostFor = "${dlap.autodelete.rule-in-force.lock-at-most-for:PT10M}",
            lockAtLeastFor = "PT5S")
    public void scheduledEnd() {
        endExpired();
    }

    /**
     * One run: ends at most {@code batch-size} rules whose maximum age passed.
     *
     * @return the number of records handled (ended, failed or retried later)
     */
    public int endExpired() {
        int handled = 0;
        for (int i = 0; i < properties.ruleInForce().batchSize(); i++) {
            AtomicReference<UUID> claimed = new AtomicReference<>();
            Optional<UUID> done;
            try {
                done = tx.execute(status -> {
                    Instant now = clock.instant();
                    Optional<SoftDeleteRecordEntity> row = records.claimNextRuleToEnd(
                            now.minus(properties.ruleInForce().maxAge()), now);
                    if (row.isEmpty()) {
                        return Optional.<UUID>empty();
                    }
                    claimed.set(row.get().getId());
                    end(row.get(), now);
                    return Optional.of(row.get().getId());
                });
            } catch (RuntimeException e) {
                LOG.error("Rule end: record {} failed and was rolled back; it is retried after {}: {}", claimed.get(),
                        properties.ruleInForce().retryAfter(), e.toString());
                if (claimed.get() == null) {
                    break;
                }
                defer(claimed.get());
                continue;
            }
            if (done == null || done.isEmpty()) {
                break;
            }
            handled++;
        }
        if (handled > 0) {
            LOG.info("Rule end: handled {} rule(s) past {}", handled, properties.ruleInForce().maxAge());
        }
        return handled;
    }

    /**
     * Ends one claimed record's rule.
     *
     * @param row the record, locked by the caller's transaction
     * @param now the run time
     */
    void end(SoftDeleteRecordEntity row, Instant now) {
        String target = row.getAssetUri() != null ? row.getAssetUri() : row.getAssetId().toString();
        boolean unproven = SoftDeleteRecordEntity.AWAITING_PROOF.equals(row.getStatus());
        if (unproven && remover.appliedContainerRule(row.getId()).isEmpty()) {
            // The claim selects applied container rules only; anything else stays with the sweeper, untouched.
            LOG.warn("Rule end: record {} is AWAITING_PROOF without an applied container rule; left alone",
                    row.getId());
            row.setRuleEndRetryAt(now.plus(properties.ruleInForce().retryAfter()));
            records.saveAndFlush(row);
            return;
        }
        String eventType = unproven ? RetireAuditLog.RULE_ENDED_UNPROVEN : RetireAuditLog.RULE_ENDED;
        String endState = unproven ? SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN : SoftDeleteRecordEntity.RULE_ENDED;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("recordId", row.getId());
        details.put("assetId", row.getAssetId());
        details.put("previousStatus", row.getStatus());
        details.put("maxAge", properties.ruleInForce().maxAge().toString());
        details.put("proven", !unproven);
        RuleRemover.Outcome outcome = remover.remove(row, eventType, RetireAuditLog.SERVICE, target, details);
        switch (outcome.result()) {
            case REMOVED -> {
                row.setStatus(endState);
                row.setNextCheckAt(null);
                row.setRuleEndRetryAt(null);
                row.setLastError(null);
                row.setUpdatedAt(now);
                records.saveAndFlush(row);
                Map<String, Object> ended = new LinkedHashMap<>(details);
                ended.put("dispositionId", outcome.disposition().getId());
                ended.put("dispositionStatus", outcome.disposition().getStatus());
                ended.put("platform", outcome.disposition().getPlatform());
                ended.put("ruleId", outcome.disposition().getRuleId());
                ended.put("ruleAppliedAt", outcome.disposition().getAppliedAt());
                ended.put("ruleRemoved", true);
                ended.put("endState", endState);
                audit.record(eventType, clock.instant(), RetireAuditLog.SERVICE, target, AuditOutcome.SUCCEEDED,
                        row.getId().toString(), ended);
                if (unproven) {
                    endedUnproven.increment();
                    LOG.warn("Record {}: the {} rule on {} was applied at {} and no provider event ever proved it; "
                            + "it reached its maximum age {} and was removed. The record is RULE_ENDED_UNPROVEN: "
                            + "check the bucket and its log source (Q-695)", row.getId(),
                            outcome.disposition().getPlatform(), target, outcome.disposition().getAppliedAt(),
                            properties.ruleInForce().maxAge());
                } else {
                    LOG.info("Record {}: the {} rule on {} reached its maximum age {} and was removed; the record is "
                            + "RULE_ENDED", row.getId(), outcome.disposition().getPlatform(), target,
                            properties.ruleInForce().maxAge());
                }
            }
            case NOT_FOUND, NO_STORED_RULE -> {
                if (outcome.result() == RuleRemover.Result.NO_STORED_RULE) {
                    audit.refused(now, RetireAuditLog.SERVICE, target, row.getId().toString(), "rule_end",
                            RefusalReason.RULE_NOT_FOUND, outcome.detail(), details);
                }
                row.setStatus(SoftDeleteRecordEntity.RESTORE_FAILED);
                row.setNextCheckAt(null);
                row.setRuleEndRetryAt(null);
                row.setLastError(RefusalReason.RULE_NOT_FOUND.code() + ": " + outcome.detail());
                row.setUpdatedAt(now);
                records.saveAndFlush(row);
            }
            case FAILED -> {
                row.setRuleEndRetryAt(now.plus(properties.ruleInForce().retryAfter()));
                row.setLastError(RefusalReason.RULE_REMOVAL_FAILED.code() + ": " + outcome.detail());
                row.setUpdatedAt(now);
                records.saveAndFlush(row);
            }
        }
    }

    private void defer(UUID id) {
        try {
            tx.executeWithoutResult(status -> records.deferRuleEnd(id,
                    clock.instant().plus(properties.ruleInForce().retryAfter())));
        } catch (RuntimeException e) {
            LOG.error("Rule end: could not defer record {}: {}", id, e.toString());
        }
    }
}
