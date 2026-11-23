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

package com.dlap.policyengine.dsl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogPageDTO;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Fires {@code type: schedule} triggers (POL-01). Before this, a cron was parsed and stored but
 * nothing evaluated it.
 *
 * <p><b>When a policy fires.</b> Every tick (default 30 s) the sweeper reads the ENABLED
 * {@code kind: Policy} documents with at least one schedule trigger. A policy is due when one of
 * its crons has a slot after the policy's stored cursor and at or before now (crons are read in
 * {@code dlap.policyengine.schedule.zone}, UTC by default). A policy seen for the first time
 * starts its cursor at now, so it fires from its next slot and never retroactively. A policy
 * whose slots were missed (service down, policy disabled) fires once for the latest missed slot,
 * not once per slot.
 *
 * <p><b>What a fire evaluates.</b> One sweep serves every policy due in the tick: it pages the
 * catalog activity report ({@code GET /api/v1/catalog/assets/activity}, sorted by
 * {@code assetUri}, statuses {@code dlap.policyengine.schedule.statuses}, default ACTIVE and
 * ARCHIVED) as of the sweep instant, by keyset when the catalog supports it (see "Paging" below), and
 * evaluates each row with {@link PolicyDslEvaluator}
 * against that instant. The row's event-time fields ({@code lastAccessedAt},
 * {@code cloudCreatedAt}, {@code lastModifiedAt}) and read coverage are what the age clauses
 * read, never the row's insert or scan time. Only a {@code TRUE} match produces an action
 * ({@link PolicyActionRequests#forSchedule}); an unknown match is counted. The action goes through
 * {@link ApprovalGate} (APPR-01): a destructive one waits as an approval request (counted as
 * {@code held} in the fire's log line), anything else is published.
 *
 * <p><b>Paging (POL-05, Q-445).</b> The first request asks for page 0 by offset. When the answer carries
 * {@code nextAfter} (the catalog's keyset contract, CAT-04), every later request is
 * {@code ?after=<nextAfter>&size=N} and the sweep ends on the page whose {@code nextAfter} is null, so a
 * row inserted or removed during a long sweep cannot make the sweep skip or repeat an asset. A cursor that
 * repeats itself, or that follows an empty page, aborts the sweep (the slot stays due). An answer
 * without the field, from a catalog that pages by offset only, is read by offset as before. The mode is
 * counted in {@code policyengine.schedule.paging{mode=keyset}}.
 *
 * <p><b>A walk never ends on a missing field (POL-06, Q-917, decision D46).</b> Once a sweep has
 * switched to keyset paging, only an answer that carries {@code nextAfter} explicitly as {@code null}
 * completes the walk. An answer to an {@code after} request that has no {@code nextAfter} field at all
 * is what an older catalog replica sends during a mixed-version rollout (it ignores {@code after} and
 * answers page 0 by offset), so the sweep ends as {@link SweepResult#INCOMPLETE} at once: its rows are
 * not evaluated, no slot is recorded, the next tick retries the slot, a WARN names each slot and the
 * page reached, and {@code policyengine.schedule.slots{result=keyset-incomplete}} counts it.
 *
 * <p><b>Delivery.</b> A slot is recorded only after its sweep reads every page. If the catalog
 * fails mid-sweep nothing is recorded and the next tick runs the slot again; the fire ids are
 * deterministic per (policy, slot, asset), so a repeat is recognisable. A sweep that reaches
 * {@code max-assets-per-sweep} stops with a warning and records the slot. A sweep whose approval
 * request cannot be recorded (the database is down) is aborted the same way, so the slot runs
 * again; the request key is the deterministic fire id, so a repeat adds no second request.
 * A slot whose sends did not all reach the broker is not recorded either (POL-02, Q-439): an
 * action the producer refused ({@code FAILED}), whose asynchronous send failed, or that the broker
 * did not acknowledge within {@code dlap.policyengine.schedule.send-timeout} keeps the slot open,
 * so the next sweep inside the slot window runs it again. Such sends are counted in
 * {@link PolicyScheduleStore.FireCounts#failed()} and {@code policyengine.schedule.sends{result=failed}}.
 *
 * <p><b>Singleton.</b> The tick holds the ShedLock lock {@value #LOCK_NAME} (JDBC, database
 * time, plan rule R7). {@code lockAtMostFor} defaults to one hour, more than twice a sweep of
 * the default 100,000-asset bound at 200 rows per page.
 */
@Component
@ConditionalOnProperty(name = "dlap.policyengine.schedule.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ScheduledPolicySweeper {

    /** ShedLock name of the sweep. */
    public static final String LOCK_NAME = "policy-schedule-sweeper";

    /** Activity-report sort: stable, ties broken by the catalog on {@code assetUri} too. */
    static final String SORT = "assetUri,asc";

    /** Bound on cron steps when catching up one policy; past it the cursor jumps to now. */
    static final int MAX_SLOT_STEPS = 10_000;

    private final PolicyRepository policyRepository;
    private final PolicyDslParser parser;
    private final PolicyDslEvaluator evaluator;
    private final PolicyScheduleStore store;
    private final ResilientCatalogAssetClient catalog;
    private final ApprovalGate gate;
    private final PolicyScheduleProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    /**
     * Creates the sweeper.
     *
     * @param policyRepository policy store
     * @param parser           DSL parser
     * @param evaluator        DSL evaluator
     * @param store            schedule cursor store
     * @param catalog          catalog client
     * @param gate             approval gate in front of the action producer
     * @param properties       schedule settings
     * @param clock            business clock
     * @param meterRegistry    metrics
     */
    public ScheduledPolicySweeper(PolicyRepository policyRepository, PolicyDslParser parser,
            PolicyDslEvaluator evaluator, PolicyScheduleStore store, ResilientCatalogAssetClient catalog,
            ApprovalGate gate, PolicyScheduleProperties properties, Clock clock,
            MeterRegistry meterRegistry) {
        this.policyRepository = policyRepository;
        this.parser = parser;
        this.evaluator = evaluator;
        this.store = store;
        this.catalog = catalog;
        this.gate = gate;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * The scheduled tick: runs every due policy, under the cluster-wide lock. Never throws.
     */
    @Scheduled(fixedDelayString = "${dlap.policyengine.schedule.tick:PT30S}",
            initialDelayString = "${dlap.policyengine.schedule.initial-delay:PT30S}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtMostFor = "${dlap.policyengine.schedule.lock-at-most-for:PT1H}",
            lockAtLeastFor = "${dlap.policyengine.schedule.lock-at-least-for:PT5S}")
    public void tick() {
        try {
            runDue();
        } catch (RuntimeException e) {
            log.error("Scheduled policy sweep failed: {}", e.getMessage(), e);
            meterRegistry.counter("policyengine.schedule.sweeps", "result", "error").increment();
        }
    }

    /** Result of one tick. */
    public enum SweepResult {
        /** No policy was due. */
        IDLE,
        /** Every page was read and every due slot recorded. */
        COMPLETED,
        /** The asset bound stopped the sweep; the slots were recorded. */
        TRUNCATED,
        /** The catalog did not answer, or an approval request could not be recorded; nothing was
         *  recorded and the slots stay due. */
        ABORTED,
        /** A keyset walk met an answer without the {@code nextAfter} field (an older catalog replica
         *  answering by offset, POL-06, Q-917); nothing was recorded and the slots stay due. */
        INCOMPLETE
    }

    /**
     * What one tick did.
     *
     * @param result    the result
     * @param asOf      the sweep instant
     * @param fires     per-policy counts of the due policies, in policy order
     */
    public record SweepReport(SweepResult result, Instant asOf, List<Fire> fires) {

        /**
         * Copies the list.
         */
        public SweepReport {
            fires = List.copyOf(fires);
        }
    }

    /**
     * One due policy's fire.
     *
     * @param policyId the policy row id
     * @param slot     the slot fired
     * @param counts   its counts
     * @param recorded whether the slot was recorded; false when the sweep aborted or a send of the
     *                 fire failed, so the slot stays due
     */
    public record Fire(UUID policyId, Instant slot, PolicyScheduleStore.FireCounts counts, boolean recorded) {
    }

    /**
     * Runs every due policy once against the catalog, as of the clock's instant.
     *
     * @return what the tick did
     */
    public SweepReport runDue() {
        Instant now = clock.instant();
        List<Due> due = findDue(now);
        if (due.isEmpty()) {
            return new SweepReport(SweepResult.IDLE, now, List.of());
        }
        log.info("Scheduled policy sweep as of {}: {} policies due", now, due.size());
        SweepResult result = sweep(due, now);
        meterRegistry.counter("policyengine.schedule.sweeps", "result", result.name().toLowerCase(java.util.Locale.ROOT)).increment();
        if (result == SweepResult.ABORTED || result == SweepResult.INCOMPLETE) {
            if (result == SweepResult.ABORTED) {
                log.warn("Scheduled policy sweep as of {} aborted: the catalog did not answer or an approval request "
                        + "could not be recorded; {} slots stay due", now, due.size());
            }
            List<Fire> fires = new ArrayList<>();
            for (Due d : due) {
                fires.add(new Fire(d.policy().getId(), d.slot(), d.counts(), false));
            }
            return new SweepReport(result, now, fires);
        }
        awaitDeliveries(due);
        Instant finished = clock.instant();
        List<Fire> fires = new ArrayList<>();
        for (Due d : due) {
            if (d.failed > 0) {
                meterRegistry.counter("policyengine.schedule.slots", "result", "send-failed").increment();
                log.warn("Policy {} slot {} not recorded: {} of {} matched actions did not reach the broker; the next "
                        + "sweep runs the slot again (evaluated={} unknown={} published={} shadowed={} held={})",
                        d.dsl().getMetadata().getId(), d.slot(), d.failed, d.matched, d.assets, d.unknown,
                        d.published, d.shadowed, d.held);
                fires.add(new Fire(d.policy().getId(), d.slot(), d.counts(), false));
                continue;
            }
            store.recordFire(d.policy().getId(), d.slot(), now, finished, d.counts());
            meterRegistry.counter("policyengine.schedule.slots", "result", "recorded").increment();
            log.info("Policy {} fired for slot {}: evaluated={} matched={} unknown={} published={} shadowed={} "
                    + "held={}", d.dsl().getMetadata().getId(), d.slot(), d.assets, d.matched, d.unknown, d.published,
                    d.shadowed, d.held);
            fires.add(new Fire(d.policy().getId(), d.slot(), d.counts(), true));
        }
        return new SweepReport(result, now, fires);
    }

    /**
     * Waits, up to {@code send-timeout} for the whole sweep, for the broker to acknowledge every
     * action the sweep published, and adds every send that failed or was not acknowledged in time to
     * its policy's failed count. The wait is wall time on the monotonic clock, not business time.
     */
    private void awaitDeliveries(List<Due> due) {
        long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
        for (Due d : due) {
            for (CompletableFuture<Boolean> delivery : d.deliveries) {
                if (!delivered(delivery, deadline)) {
                    d.failed++;
                    meterRegistry.counter("policyengine.schedule.sends", "result", "failed").increment();
                }
            }
            d.deliveries.clear();
        }
    }

    private static boolean delivered(CompletableFuture<Boolean> delivery, long deadline) {
        try {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            return Boolean.TRUE.equals(delivery.get(remaining, TimeUnit.NANOSECONDS));
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /* ----- which policies are due ----- */

    private List<Due> findDue(Instant now) {
        ZoneId zone = properties.zoneId();
        List<Due> due = new ArrayList<>();
        for (Policy p : policyRepository.findByStatus(PolicyStatus.ENABLED)) {
            String yaml = p.getPolicyDocument();
            if (yaml == null || yaml.isBlank()) {
                continue;
            }
            PolicyDsl dsl;
            try {
                dsl = parser.parse(yaml);
            } catch (PolicyDslParser.PolicyDslParseException e) {
                log.debug("Policy {} has an invalid document; not scheduled: {}", p.getId(), e.getMessage());
                continue;
            }
            if (!PolicyDsl.KIND_POLICY.equals(dsl.getKind()) || !dsl.getMetadata().isEnabled()) {
                continue;
            }
            List<CronExpression> crons = crons(dsl);
            if (crons.isEmpty()) {
                continue;
            }
            Optional<PolicyScheduleStore.State> state = store.find(p.getId());
            if (state.isEmpty()) {
                store.insertBaseline(p.getId(), now.truncatedTo(ChronoUnit.SECONDS), now);
                log.info("Policy {} scheduled; it fires from its next cron slot after {}", dsl.getMetadata().getId(), now);
                continue;
            }
            Instant slot = latestSlot(crons, state.get().lastSlot(), now, zone);
            if (slot != null) {
                due.add(new Due(p, dsl, slot));
            }
        }
        return due;
    }

    private static List<CronExpression> crons(PolicyDsl dsl) {
        List<CronExpression> out = new ArrayList<>();
        if (dsl.getSpec() == null || dsl.getSpec().getTriggers() == null) {
            return out;
        }
        for (PolicyDsl.Trigger t : dsl.getSpec().getTriggers()) {
            if (t != null && PolicyDslParser.TRIGGER_SCHEDULE.equalsIgnoreCase(t.getType()) && t.getCron() != null) {
                try {
                    out.add(CronExpression.parse(t.getCron().strip()));
                } catch (IllegalArgumentException e) {
                    log.debug("Skipping an unparseable cron of policy {}", dsl.getMetadata().getId());
                }
            }
        }
        return out;
    }

    /**
     * The latest slot of any of the crons that is after {@code after} and at or before
     * {@code now}, or {@code null} when none is. A catch-up longer than {@link #MAX_SLOT_STEPS}
     * slots answers {@code now} truncated to seconds, which no cron slot can follow immediately.
     *
     * @param crons the expressions
     * @param after the cursor (exclusive)
     * @param now   the sweep instant (inclusive)
     * @param zone  the zone the crons are read in
     * @return the slot to fire, or {@code null}
     */
    static Instant latestSlot(List<CronExpression> crons, Instant after, Instant now, ZoneId zone) {
        Instant best = null;
        for (CronExpression cron : crons) {
            ZonedDateTime t = cron.next(ZonedDateTime.ofInstant(after, zone));
            Instant last = null;
            int steps = 0;
            while (t != null && !t.toInstant().isAfter(now)) {
                last = t.toInstant();
                if (++steps >= MAX_SLOT_STEPS) {
                    last = now.truncatedTo(ChronoUnit.SECONDS);
                    break;
                }
                t = cron.next(t);
            }
            if (last != null && (best == null || last.isAfter(best))) {
                best = last;
            }
        }
        return best;
    }

    /* ----- the sweep ----- */

    private SweepResult sweep(List<Due> due, Instant asOf) {
        int page = 0;
        long seen = 0;
        // Keyset paging (CAT-04, Q-445): the first answer that carries nextAfter switches the sweep from offset
        // pages to "rows after this assetUri", which a row written during the sweep cannot shift. A catalog
        // that never sends nextAfter is read by offset, as before.
        boolean keyset = false;
        String cursor = null;
        int pagesRead = 0;
        // One WARN per asset per sweep for a FULL coverage without an observation time (Q-475).
        Set<String> coverageWarned = new HashSet<>();
        while (true) {
            Optional<CatalogPageDTO<CatalogActivityRowDTO>> response = keyset
                    ? catalog.getAssetActivityAfter(cursor, properties.pageSize(), properties.statuses(), asOf)
                    : catalog.getAssetActivity(page, properties.pageSize(), SORT, properties.statuses(), asOf);
            if (response.isEmpty()) {
                return SweepResult.ABORTED;
            }
            CatalogPageDTO<CatalogActivityRowDTO> rows = response.get();
            if (!keyset && rows.keysetAnswered()) {
                keyset = true;
                meterRegistry.counter("policyengine.schedule.paging", "mode", "keyset").increment();
            }
            if (!keyset && !rows.content().isEmpty() && !rows.pagingKnown()) {
                log.error("Catalog activity page {} carries no paging data; the sweep cannot know whether rows "
                        + "follow and is aborted", page);
                return SweepResult.ABORTED;
            }
            if (keyset && !rows.keysetAnswered()) {
                // A keyset walk completes only on an explicit nextAfter: null. A missing field is an older replica
                // that ignored "after" and answered page 0 by offset (Q-917, D46); its rows repeat page 0.
                return incomplete(due, asOf, cursor, pagesRead);
            }
            String next = keyset ? rows.nextCursor() : null;
            if (keyset && next != null && (rows.content().isEmpty() || next.equals(cursor))) {
                // Not an order check: the catalog orders by its database collation, which Java cannot reproduce.
                log.error("Catalog activity page after {} answers nextAfter {} with {} rows; a cursor must move "
                        + "on over a page of rows, so the sweep is aborted", cursor, next, rows.content().size());
                return SweepResult.ABORTED;
            }
            for (CatalogActivityRowDTO row : rows.content()) {
                if (seen >= properties.maxAssetsPerSweep()) {
                    log.warn("Scheduled policy sweep as of {} stopped at {} assets (dlap.policyengine.schedule."
                            + "max-assets-per-sweep)", asOf, seen);
                    return SweepResult.TRUNCATED;
                }
                seen++;
                if (row.assetId() == null) {
                    continue;
                }
                Map<String, Object> snapshot = AssetSnapshots.fromActivityRow(row);
                for (Due d : due) {
                    try {
                        evaluate(d, row, snapshot, asOf, coverageWarned);
                    } catch (DataAccessException e) {
                        log.error("Scheduled policy sweep as of {}: the approval request of policy {} for asset {} "
                                + "could not be recorded; the sweep is aborted: {}", asOf,
                                d.dsl().getMetadata().getId(), row.assetId(), e.getMessage());
                        return SweepResult.ABORTED;
                    }
                }
            }
            pagesRead++;
            if (keyset) {
                if (next == null) {
                    return SweepResult.COMPLETED;
                }
                cursor = next;
            } else {
                if (!rows.hasNext()) {
                    return SweepResult.COMPLETED;
                }
                page++;
            }
        }
    }

    /**
     * Ends a keyset walk that met an answer without {@code nextAfter}: logs each slot left unrecorded with the
     * page reached, counts it, and answers {@link SweepResult#INCOMPLETE}.
     */
    private SweepResult incomplete(List<Due> due, Instant asOf, String cursor, int pagesRead) {
        for (Due d : due) {
            meterRegistry.counter("policyengine.schedule.slots", "result", "keyset-incomplete").increment();
            log.warn("Policy {} slot {} not recorded: the catalog answered page {} (after {}) of the keyset walk as of "
                    + "{} without nextAfter, as an older replica does; the walk is incomplete and the next sweep "
                    + "runs the slot again", d.dsl().getMetadata().getId(), d.slot(), pagesRead, cursor, asOf);
        }
        return SweepResult.INCOMPLETE;
    }

    private void evaluate(Due d, CatalogActivityRowDTO row, Map<String, Object> snapshot, Instant asOf,
            Set<String> coverageWarned) {
        d.assets++;
        MatchResult result = evaluator.evaluate(d.dsl(), snapshot, evaluator.options(asOf, d.dsl(), coverageWarned));
        if (result.isUnknown()) {
            d.unknown++;
            meterRegistry.counter("policyengine.schedule.matches", "outcome", "unknown").increment();
            log.debug("Policy {} unknown for asset {} (clauses {})", d.dsl().getMetadata().getId(), row.assetId(),
                    result.unknownClauses());
            return;
        }
        if (!result.isTrue()) {
            return;
        }
        d.matched++;
        meterRegistry.counter("policyengine.schedule.matches", "outcome", "true").increment();
        ApprovalGate.Submission submission = gate.submitTracked(
                PolicyActionRequests.forSchedule(d.dsl(), d.policy().getId(), snapshot, d.slot(), asOf));
        switch (submission.route()) {
            case PUBLISHED -> {
                d.published++;
                d.deliveries.add(submission.delivered());
            }
            case SHADOWED -> d.shadowed++;
            case HELD, ALREADY_HELD -> d.held++;
            case FAILED -> {
                d.failed++;
                meterRegistry.counter("policyengine.schedule.sends", "result", "failed").increment();
                log.warn("Policy {} action for asset {} failed before the send; slot {} stays due",
                        d.dsl().getMetadata().getId(), row.assetId(), d.slot());
            }
        }
    }

    /** A due policy and its running counts. */
    private static final class Due {
        private final Policy policy;
        private final PolicyDsl dsl;
        private final Instant slot;
        private long assets;
        private long matched;
        private long unknown;
        private long published;
        private long shadowed;
        private long held;
        private long failed;
        private final List<CompletableFuture<Boolean>> deliveries = new ArrayList<>();

        Due(Policy policy, PolicyDsl dsl, Instant slot) {
            this.policy = policy;
            this.dsl = dsl;
            this.slot = slot;
        }

        Policy policy() {
            return policy;
        }

        PolicyDsl dsl() {
            return dsl;
        }

        Instant slot() {
            return slot;
        }

        PolicyScheduleStore.FireCounts counts() {
            return new PolicyScheduleStore.FireCounts(assets, matched, unknown, published, shadowed, failed);
        }
    }
}
