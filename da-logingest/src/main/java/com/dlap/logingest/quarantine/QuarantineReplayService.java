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

package com.dlap.logingest.quarantine;

import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.lock.AdvisoryLocks;
import com.dlap.logingest.transport.lock.PgAdvisoryLocks;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.stereotype.Service;

/**
 * Re-runs quarantined records through the current parser registry, after a parser fix or upgrade
 * (design B3 "replay"). It is the only writer of {@code replayed_at}.
 *
 * <h3>What is replayed</h3>
 * Only a row whose stored excerpt is exactly the original record ({@link QuarantineRow#replayable()}:
 * not truncated, nothing masked, SHA-256 and size match). The table keeps a masked, bounded excerpt,
 * and replaying a masked copy would store {@code <email>} as a principal. A truncated or masked row is
 * counted, not replayed from the table; with {@link SourceReplayHook} present they are re-read from
 * their source by the bounded backfill reader instead, and no raw provider data is stored for it
 * (decision D26, Q-362).
 *
 * <h3>Idempotency</h3>
 * <ul>
 *   <li>Only rows with {@code replayed_at IS NULL} are selected. A record that parses (or is skipped)
 *       on replay gets {@code replayed_at}, so a second call selects nothing; a record that is
 *       quarantined again stays pending, with {@code replay_count} up by one.</li>
 *   <li>The events of a replayed record keep their {@code event_id}, so the replay store and every
 *       downstream consumer deduplicate on it: replaying twice can never store an event twice.
 *       Records run in {@code REPLAY} mode, so a stored id (for example the pass-through event of the
 *       quarantine) is published again, which is what lets the corrected event reach the catalog.</li>
 *   <li>One replay runs at a time in the whole cluster (a PostgreSQL advisory lock); a second call
 *       is refused with {@link ReplayBusyException} instead of racing the first.</li>
 * </ul>
 */
@Service
public class QuarantineReplayService {

    /** Advisory-lock namespace of the replay (distinct from the stream locks of {@link PgAdvisoryLocks}). */
    static final int LOCK_NAMESPACE = 0x444C_0003;

    /** Advisory-lock key of the one cluster-wide replay. */
    static final int LOCK_KEY = 1;

    /** Rows a replay takes when the request names no limit. */
    public static final int DEFAULT_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(QuarantineReplayService.class);

    /**
     * What to replay. Every field narrows the selection.
     *
     * @param bindingId       only this binding, or {@code null}
     * @param reason          only this reason code, or {@code null}
     * @param detectedVersion only records detected as this version, or {@code null}
     * @param quarantineIds   only these records, or {@code null}
     * @param limit           the most records to re-run; null means {@value #DEFAULT_LIMIT}, and it never
     *                        exceeds {@code replay-max-batch}
     * @param dryRun          true to count what would be re-run without ingesting anything
     * @param fromSource      {@code false} to replay only rows whose stored excerpt is the exact record; absent or
     *                        {@code true} also re-reads truncated and masked rows from their source by the bounded
     *                        backfill reader (decision D26, Q-362), for streams that allow backfill
     */
    public record ReplayRequest(String bindingId, String reason, String detectedVersion, List<String> quarantineIds,
                                Integer limit, boolean dryRun, Boolean fromSource) {

        /**
         * A request that leaves the source re-read at its default.
         *
         * @param bindingId       see the record description
         * @param reason          see the record description
         * @param detectedVersion see the record description
         * @param quarantineIds   see the record description
         * @param limit           see the record description
         * @param dryRun          see the record description
         */
        public ReplayRequest(String bindingId, String reason, String detectedVersion, List<String> quarantineIds,
                             Integer limit, boolean dryRun) {
            this(bindingId, reason, detectedVersion, quarantineIds, limit, dryRun, null);
        }
    }

    /**
     * What a replay did.
     *
     * @param dryRun               whether anything was ingested
     * @param selected             pending rows matching the request, up to the limit
     * @param replayable           of those, rows whose stored excerpt is the exact record
     * @param resolved             rows that now parse or skip (marked replayed)
     * @param stillQuarantined     rows quarantined again (still pending)
     * @param notReplayableTruncated rows with a truncated excerpt
     * @param notReplayableAltered rows whose excerpt was masked or is not the original bytes
     * @param bindingNotRunning    rows of a binding that does not run, or has no stream of the envelope
     * @param failed               rows whose ingest raised an error (pending; see the log)
     * @param resolvedIds          the ids of the resolved rows
     * @param fromSource           what the re-read from the source did with the rows the table could not replay
     */
    public record ReplayResult(boolean dryRun, int selected, int replayable, int resolved, int stillQuarantined,
                               int notReplayableTruncated, int notReplayableAltered, int bindingNotRunning,
                               int failed, List<String> resolvedIds, SourceCounts fromSource) {

        /**
         * A result with no source re-read.
         *
         * @param dryRun                 see the record description
         * @param selected               see the record description
         * @param replayable             see the record description
         * @param resolved               see the record description
         * @param stillQuarantined       see the record description
         * @param notReplayableTruncated see the record description
         * @param notReplayableAltered   see the record description
         * @param bindingNotRunning      see the record description
         * @param failed                 see the record description
         * @param resolvedIds            see the record description
         */
        public ReplayResult(boolean dryRun, int selected, int replayable, int resolved, int stillQuarantined,
                            int notReplayableTruncated, int notReplayableAltered, int bindingNotRunning, int failed,
                            List<String> resolvedIds) {
            this(dryRun, selected, replayable, resolved, stillQuarantined, notReplayableTruncated,
                    notReplayableAltered, bindingNotRunning, failed, resolvedIds, SourceCounts.NONE);
        }
    }

    /**
     * What the re-read from the source did. Rows counted here are not counted in
     * {@code notReplayableTruncated} or {@code notReplayableAltered}; rows that cannot be re-read at all
     * (a push channel, a binding that does not run) stay there.
     *
     * @param planned          dry run: rows that would be re-read
     * @param resolved         re-read, and the current parsers produced an event or a skip
     * @param stillQuarantined re-read, and quarantined again
     * @param notFound         the source no longer holds the record
     * @param changed          the source holds a record there with other bytes
     * @param notAllowed       the stream does not allow backfill, so nothing was read
     * @param failed           reading or ingesting failed
     */
    public record SourceCounts(int planned, int resolved, int stillQuarantined, int notFound, int changed,
                               int notAllowed, int failed) {

        /** No source re-read took place. */
        public static final SourceCounts NONE = new SourceCounts(0, 0, 0, 0, 0, 0, 0);
    }

    /** The registry pipeline is not active, so there is nothing to replay into. */
    public static final class ReplayUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReplayUnavailableException() {
            super("quarantine replay needs dlap.logingest.pipeline=registry");
        }
    }

    /** Another replay is running. */
    public static final class ReplayBusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReplayBusyException() {
            super("another quarantine replay is running");
        }
    }

    private final QuarantineRecordRepository repository;
    private final ObjectProvider<RegistryIngestService> ingest;
    private final EffectiveLogSources sources;
    private final PgAdvisoryLocks locks;
    private final QuarantineProperties props;
    private final ObjectProvider<SourceReplayHook> sourceHook;
    private final Clock clock;

    /**
     * Creates the service with no source re-read: truncated and masked rows are counted, not replayed.
     *
     * @param repository the quarantine table
     * @param ingest     the registry ingest path; present only with {@code dlap.logingest.pipeline=registry}
     * @param sources    the effective configuration
     * @param locks      the advisory locks
     * @param props      the settings
     * @param clock      the application clock when unique, else the UTC system clock
     */
    public QuarantineReplayService(QuarantineRecordRepository repository, ObjectProvider<RegistryIngestService> ingest,
                                   EffectiveLogSources sources, PgAdvisoryLocks locks, QuarantineProperties props,
                                   ObjectProvider<Clock> clock) {
        this(repository, ingest, sources, locks, props,
                new StaticListableBeanFactory().getBeanProvider(SourceReplayHook.class), clock);
    }

    /**
     * Creates the service.
     *
     * @param repository the quarantine table
     * @param ingest     the registry ingest path; present only with {@code dlap.logingest.pipeline=registry}
     * @param sources    the effective configuration
     * @param locks      the advisory locks
     * @param props      the settings
     * @param sourceHook re-reads truncated and masked rows from their source; present only with the registry
     *                   pipeline (decision D26)
     * @param clock      the application clock when unique, else the UTC system clock
     */
    @Autowired
    public QuarantineReplayService(QuarantineRecordRepository repository, ObjectProvider<RegistryIngestService> ingest,
                                   EffectiveLogSources sources, PgAdvisoryLocks locks, QuarantineProperties props,
                                   ObjectProvider<SourceReplayHook> sourceHook, ObjectProvider<Clock> clock) {
        this.repository = repository;
        this.ingest = ingest;
        this.sources = sources;
        this.locks = locks;
        this.props = props;
        this.sourceHook = sourceHook;
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    /**
     * Replays quarantined records.
     *
     * @param request what to replay
     * @param actor   who asked, for the audit line (a name, never a token)
     * @return what happened
     * @throws ReplayUnavailableException when the registry pipeline is not active
     * @throws ReplayBusyException        when another replay is running
     * @throws IllegalArgumentException   when the request names an unknown reason, a limit below 1 or more ids than
     *                                  {@code replay-max-batch}
     */
    public ReplayResult replay(ReplayRequest request, String actor) {
        RegistryIngestService service = ingest.getIfAvailable();
        if (service == null) {
            throw new ReplayUnavailableException();
        }
        if (request.limit() != null && request.limit() < 1) {
            throw new IllegalArgumentException("limit is at least 1");
        }
        if (request.quarantineIds() != null && request.quarantineIds().size() > props.replayMaxBatch()) {
            throw new IllegalArgumentException("quarantineIds names at most " + props.replayMaxBatch() + " records");
        }
        String reason = request.reason() == null ? null
                : QuarantineBounds.reasonCode(QuarantineBounds.parseReason(request.reason()));
        int limit = Math.min(request.limit() == null ? DEFAULT_LIMIT : request.limit(), props.replayMaxBatch());
        QuarantineRecordRepository.Filter filter = new QuarantineRecordRepository.Filter(request.bindingId(), reason,
                request.detectedVersion(), null, null, null, request.quarantineIds());

        Optional<AdvisoryLocks.Lease> lease = locks.tryLock(LOCK_NAMESPACE, LOCK_KEY);
        if (lease.isEmpty()) {
            throw new ReplayBusyException();
        }
        try (AdvisoryLocks.Lease ignored = lease.get()) {
            return run(service, repository.pending(filter, limit), request.dryRun(),
                    !Boolean.FALSE.equals(request.fromSource()), actor);
        }
    }

    private ReplayResult run(RegistryIngestService service, List<QuarantineRow> rows, boolean dryRun,
                             boolean fromSource, String actor) {
        int replayable = 0;
        int resolved = 0;
        int again = 0;
        int truncated = 0;
        int altered = 0;
        int notRunning = 0;
        int failed = 0;
        List<String> resolvedIds = new ArrayList<>();
        List<QuarantineRow> unreadable = new ArrayList<>();
        for (QuarantineRow row : rows) {
            QuarantineRow.NotReplayable why = row.notReplayableReason();
            if (why != null) {
                unreadable.add(row);
                continue;
            }
            if (sources.runningStream(row.bindingId(), row.envelope()).isEmpty()) {
                notRunning++;
                continue;
            }
            replayable++;
            if (dryRun) {
                continue;
            }
            try {
                RecordReport report = service.ingestBatch(List.of(toRecord(row)), false).records().get(0);
                boolean quarantined = report.quarantined();
                repository.markReplayAttempt(row.quarantineId(), !quarantined, clock.instant());
                if (quarantined) {
                    again++;
                } else {
                    resolved++;
                    resolvedIds.add(row.quarantineId());
                }
            } catch (RuntimeException e) {
                // The message may quote SQL or provider data; only the class goes to the log.
                failed++;
                log.warn("Replay of quarantine record {} (binding {}) failed: {}", row.quarantineId(),
                        row.bindingId(), e.getClass().getSimpleName());
            }
        }

        // Rows the table cannot replay are re-read from their source (decision D26, Q-362).
        Map<String, SourceReplayHook.Outcome> sourced = Map.of();
        SourceReplayHook hook = fromSource && !unreadable.isEmpty() ? sourceHook.getIfAvailable() : null;
        if (hook != null) {
            try {
                sourced = hook.replay(unreadable, dryRun);
            } catch (RuntimeException e) {
                // The table rows are already booked; a failing source re-read fails only its own rows.
                // The message may quote provider data or SQL; only the class goes to the log.
                log.warn("Source re-read of {} quarantine record(s) failed: {}", unreadable.size(),
                        e.getClass().getSimpleName());
                Map<String, SourceReplayHook.Outcome> failedAll = new HashMap<>();
                unreadable.forEach(r -> failedAll.put(r.quarantineId(), SourceReplayHook.Outcome.FAILED));
                sourced = failedAll;
            }
        }
        int planned = 0;
        int sResolved = 0;
        int sAgain = 0;
        int sNotFound = 0;
        int sChanged = 0;
        int sNotAllowed = 0;
        int sFailed = 0;
        for (QuarantineRow row : unreadable) {
            SourceReplayHook.Outcome outcome = sourced.get(row.quarantineId());
            if (outcome == null || outcome == SourceReplayHook.Outcome.NOT_ELIGIBLE) {
                if (row.notReplayableReason() == QuarantineRow.NotReplayable.TRUNCATED) {
                    truncated++;
                } else {
                    altered++;
                }
                continue;
            }
            switch (outcome) {
                case WOULD_RE_READ -> planned++;
                case NOT_ALLOWED -> {
                    sNotAllowed++;
                    if (row.notReplayableReason() == QuarantineRow.NotReplayable.TRUNCATED) {
                        truncated++;
                    } else {
                        altered++;
                    }
                }
                case RESOLVED -> {
                    repository.markReplayAttempt(row.quarantineId(), true, clock.instant());
                    sResolved++;
                    resolved++;
                    resolvedIds.add(row.quarantineId());
                }
                case STILL_QUARANTINED -> {
                    repository.markReplayAttempt(row.quarantineId(), false, clock.instant());
                    sAgain++;
                    again++;
                }
                case NOT_FOUND -> {
                    repository.markReplayAttempt(row.quarantineId(), false, clock.instant());
                    sNotFound++;
                }
                case CHANGED -> {
                    repository.markReplayAttempt(row.quarantineId(), false, clock.instant());
                    sChanged++;
                }
                case FAILED -> {
                    sFailed++;
                    failed++;
                }
                default -> throw new IllegalStateException("unhandled source replay outcome " + outcome);
            }
        }
        SourceCounts counts = new SourceCounts(planned, sResolved, sAgain, sNotFound, sChanged, sNotAllowed, sFailed);
        log.info("Quarantine replay by {}: dryRun={} selected={} replayable={} resolved={} stillQuarantined={} "
                        + "truncated={} altered={} bindingNotRunning={} failed={} fromSource={}", actor, dryRun,
                rows.size(), replayable, resolved, again, truncated, altered, notRunning, failed, counts);
        return new ReplayResult(dryRun, rows.size(), replayable, resolved, again, truncated, altered, notRunning,
                failed, List.copyOf(resolvedIds), counts);
    }

    /**
     * Rebuilds the raw record of a replayable row.
     *
     * @param row a row whose excerpt is the exact record
     * @return the record, in {@code REPLAY} mode
     */
    RawRecord toRecord(QuarantineRow row) {
        Map<String, Object> ref = row.rawRef();
        long index = ref.get("recordIndex") instanceof Number n ? n.longValue() : -1L;
        return RawRecord.builder()
                .bindingId(row.bindingId())
                .sourceKind(row.sourceKind())
                .envelope(row.envelope())
                .ref(new RawRef(String.valueOf(ref.get("transport")), String.valueOf(ref.get("uri")), index,
                        ref.get("cursor") == null ? "" : String.valueOf(ref.get("cursor"))))
                .mode(IngestMode.REPLAY)
                .fetchedAt(clock.instant())
                .text(row.rawExcerpt())
                .build();
    }
}
