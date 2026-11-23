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

package com.dlap.logingest.backfill;

import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.parse.core.ConditionalOnRegistryPipeline;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.logingest.service.registry.BatchReport;
import com.dlap.logingest.service.registry.Disposition;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.LogTransport;
import com.dlap.logingest.transport.ObjectReadException;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.ReadFailureQuarantine;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportRegistry;
import com.dlap.logingest.transport.lock.AdvisoryLocks;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs one backfill job (design B6): reads {@code [from, to)} of a binding's stream through its
 * transport in {@code BACKFILL} mode, so every event carries {@code origin=BACKFILL}.
 *
 * <h3>Bounded</h3>
 * The window is cut into slices ({@code slice-time} or {@code slice-object}); a transport is asked
 * for one slice at a time, and never for anything outside the job's window.
 *
 * <h3>Rate-limited</h3>
 * Every batch waits for a {@link BatchPacer} set to the lower of {@code max-batches-per-second} and
 * the stream's own {@code rate-limit}. Transports with API quotas (Cloud Logging) add their own
 * limiter on top.
 *
 * <h3>Resumable, no gaps, no duplicates</h3>
 * <ul>
 *   <li>The job's cursor is the {@code progress} JSON of its row, one position per transport
 *       partition. It moves only after a batch reached its outcomes, exactly as a stream cursor does,
 *       so a batch in flight when the process dies is read again. A slice is closed only when the
 *       transport confirms it reached the slice end ({@link BoundedReader}); a transport that stops
 *       short fails the attempt instead of leaving a gap.</li>
 *   <li>Re-reading a batch cannot duplicate: events have deterministic ids and the store skips an
 *       id it already holds, publishing nothing again in {@code BACKFILL} mode
 *       ({@link IngestMode#shouldPublish}). File transports additionally skip objects the job
 *       already finished ({@link ScopedObjects}).</li>
 *   <li>A job whose process died stays {@code RUNNING}. The advisory lock it held is released with
 *       the session, so the next scheduler tick on any replica takes the lock and resumes from the
 *       stored progress.</li>
 *   <li>One job per binding is enforced by the unique index of V3; one runner per job by the
 *       advisory lock taken here.</li>
 * </ul>
 *
 * <h3>Failures</h3>
 * A runtime failure keeps the job {@code RUNNING}, records the error class and a retry time with
 * exponential backoff, and counts an attempt; a committed batch resets the count. After
 * {@code max-attempts} consecutive failures the job is {@code FAILED}. A job whose binding, stream or
 * transport vanished from the configuration, or lost {@code backfill.allowed}, fails at once. An
 * {@link Error} is not caught: it models a killed process and leaves the job resumable.
 *
 * <h3>Unreadable objects</h3>
 * An object a file transport cannot read ({@link ObjectReadException}) follows the same rule as in the
 * stream runner (ADR-0110, Q-702): each failed read is counted in {@code processed_object.attempts}
 * under the job's pseudo stream, the attempt fails and the job retries after its backoff, and at
 * {@code dlap.logingest.transports.max-read-attempts} (default 5) the object is quarantined as
 * {@code READ_FAILED}, checkpointed and skipped so the job goes on. A job whose
 * {@code max-attempts} is not above that cap can fail before the object reaches it. Resetting a
 * quarantined unreadable object stays a documented SQL step (docs/guides/log-sources.md, section 5.3).
 */
@Component
@ConditionalOnRegistryPipeline
public class BackfillRunner {

    /** Scope of the advisory lock that guards one job (the lock key is the job id). */
    static final String LOCK_SCOPE = "backfill-job";

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);

    /** How one {@link #run} call ended. */
    public enum RunResult {
        /** Another replica holds the job's lock. */
        SKIPPED,
        /** The job does not exist or has ended. */
        NOT_RUNNABLE,
        /** The job waits out its retry backoff. */
        WAITING,
        /** Every partition was read to the end; the job is {@code SUCCEEDED}. */
        COMPLETED,
        /** An administrator cancelled the job. */
        CANCELLED,
        /** The service is stopping; the job stays {@code RUNNING} and resumes later. */
        STOPPED,
        /** An attempt failed; the job stays {@code RUNNING} and is retried after the backoff. */
        RETRY,
        /** The job is {@code FAILED}. */
        FAILED
    }

    /** A transport ended a slice before its end without an error. */
    private static final class IncompleteSliceException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        IncompleteSliceException(String partition) {
            super("the transport stopped before the end of the slice (partition '" + partition + "')");
        }
    }

    private final BackfillJobs jobs;
    private final EffectiveLogSources sources;
    private final TransportRegistry transports;
    private final RegistryIngestService ingest;
    private final ProcessedObjectStore objects;
    private final AdvisoryLocks locks;
    private final BackfillProperties props;
    private final Clock clock;
    private final DoubleFunction<BatchPacer> pacers;
    private final ReadFailureQuarantine readFailures;
    private final BackfillMetrics metrics;

    /**
     * Creates the runner for the running service.
     *
     * @param jobs       the job table
     * @param sources    the effective configuration
     * @param transports the transports
     * @param ingest     the registry ingest path
     * @param objects    the processed-object checkpoints
     * @param locks      the advisory locks
     * @param props      the settings
     * @param clock      the application clock when unique, else the UTC system clock
     * @param sinks      the quarantine sinks, which receive an object that could not be read
     * @param maxReadAttempts failed reads of one object before it is quarantined and skipped
     *                   ({@code dlap.logingest.transports.max-read-attempts}, the cap of the stream runner)
     * @param metrics    the backfill meters (Q-558)
     */
    @Autowired
    public BackfillRunner(BackfillJobs jobs, EffectiveLogSources sources, TransportRegistry transports,
                          RegistryIngestService ingest, ProcessedObjectStore objects, AdvisoryLocks locks,
                          BackfillProperties props, ObjectProvider<Clock> clock, ObjectProvider<QuarantineSink> sinks,
                          @Value("${dlap.logingest.transports.max-read-attempts:"
                                  + ReadFailureQuarantine.DEFAULT_MAX_READ_ATTEMPTS + "}") int maxReadAttempts,
                          BackfillMetrics metrics) {
        this(jobs, sources, transports, ingest, objects, locks, props, clock.getIfUnique(Clock::systemUTC),
                BatchPacer::system, sinks, maxReadAttempts, metrics);
    }

    /**
     * Creates the runner with a fixed clock and pacer, for tests.
     *
     * @param jobs       the job table
     * @param sources    the effective configuration
     * @param transports the transports
     * @param ingest     the registry ingest path
     * @param objects    the processed-object checkpoints
     * @param locks      the advisory locks
     * @param props      the settings
     * @param clock      the clock
     * @param pacers     builds the pacer of a job from its rate
     */
    public BackfillRunner(BackfillJobs jobs, EffectiveLogSources sources, TransportRegistry transports,
                          RegistryIngestService ingest, ProcessedObjectStore objects, AdvisoryLocks locks,
                          BackfillProperties props, Clock clock, DoubleFunction<BatchPacer> pacers) {
        this(jobs, sources, transports, ingest, objects, locks, props, clock, pacers, null,
                ReadFailureQuarantine.DEFAULT_MAX_READ_ATTEMPTS);
    }

    /**
     * Creates the runner with a fixed clock and pacer, quarantine sinks and a read-attempt cap, for tests.
     *
     * @param jobs       the job table
     * @param sources    the effective configuration
     * @param transports the transports
     * @param ingest     the registry ingest path
     * @param objects    the processed-object checkpoints
     * @param locks      the advisory locks
     * @param props      the settings
     * @param clock      the clock
     * @param pacers     builds the pacer of a job from its rate
     * @param sinks      the quarantine sinks, or {@code null} for the logging sink only
     * @param maxReadAttempts failed reads of one object before it is quarantined and skipped; at least 1
     */
    public BackfillRunner(BackfillJobs jobs, EffectiveLogSources sources, TransportRegistry transports,
                          RegistryIngestService ingest, ProcessedObjectStore objects, AdvisoryLocks locks,
                          BackfillProperties props, Clock clock, DoubleFunction<BatchPacer> pacers,
                          ObjectProvider<QuarantineSink> sinks, int maxReadAttempts) {
        this(jobs, sources, transports, ingest, objects, locks, props, clock, pacers, sinks, maxReadAttempts,
                BackfillMetrics.detached());
    }

    /**
     * Creates the runner with a fixed clock and pacer, quarantine sinks, a read-attempt cap and the
     * backfill meters, for tests that read the meters.
     *
     * @param jobs       the job table
     * @param sources    the effective configuration
     * @param transports the transports
     * @param ingest     the registry ingest path
     * @param objects    the processed-object checkpoints
     * @param locks      the advisory locks
     * @param props      the settings
     * @param clock      the clock
     * @param pacers     builds the pacer of a job from its rate
     * @param sinks      the quarantine sinks, or {@code null} for the logging sink only
     * @param maxReadAttempts failed reads of one object before it is quarantined and skipped; at least 1
     * @param metrics    the backfill meters
     */
    public BackfillRunner(BackfillJobs jobs, EffectiveLogSources sources, TransportRegistry transports,
                          RegistryIngestService ingest, ProcessedObjectStore objects, AdvisoryLocks locks,
                          BackfillProperties props, Clock clock, DoubleFunction<BatchPacer> pacers,
                          ObjectProvider<QuarantineSink> sinks, int maxReadAttempts, BackfillMetrics metrics) {
        this.metrics = metrics;
        this.jobs = jobs;
        this.sources = sources;
        this.transports = transports;
        this.ingest = ingest;
        this.objects = objects;
        this.locks = locks;
        this.props = props;
        this.clock = clock;
        this.pacers = pacers;
        this.readFailures = new ReadFailureQuarantine(sources, clock, sinks, maxReadAttempts);
    }

    /**
     * Runs or resumes a job until it ends, is cancelled, fails an attempt or the service stops.
     *
     * @param id            the job
     * @param stopRequested true when the service is shutting down; checked between batches
     * @return how the call ended
     */
    public RunResult run(UUID id, BooleanSupplier stopRequested) {
        Optional<BackfillJob> peek = jobs.find(id);
        if (peek.isEmpty() || !peek.get().status().active()) {
            return RunResult.NOT_RUNNABLE;
        }
        if (peek.get().progress().waiting(clock.instant())) {
            return RunResult.WAITING;
        }
        Optional<AdvisoryLocks.Lease> lease = locks.tryLockStream(LOCK_SCOPE, id.toString());
        if (lease.isEmpty()) {
            return RunResult.SKIPPED;
        }
        try (AdvisoryLocks.Lease held = lease.get()) {
            // The state may have changed between the peek and the lock.
            Optional<BackfillJob> job = jobs.find(id);
            if (job.isEmpty() || !job.get().status().active() || !jobs.markRunning(id, clock.instant())) {
                return RunResult.NOT_RUNNABLE;
            }
            return new Execution(job.get(), stopRequested).execute();
        }
    }

    /** The state of one run. */
    private final class Execution {
        private final BackfillJob job;
        private final BooleanSupplier stopRequested;
        private BackfillProgress progress;
        private boolean cancelled;
        private boolean stopping;
        private final Instant runStartedAt = clock.instant();

        Execution(BackfillJob job, BooleanSupplier stopRequested) {
            this.job = job;
            this.stopRequested = stopRequested;
            this.progress = job.progress();
        }

        RunResult execute() {
            Optional<EffectiveBinding> binding = sources.binding(job.bindingId());
            Optional<EffectiveStream> stream = binding.flatMap(b -> b.streams().stream()
                    .filter(s -> s.streamId().equals(job.streamId())).findFirst());
            if (binding.isEmpty() || stream.isEmpty() || !binding.get().runs(sources.pipeline())) {
                return fail("the binding or stream no longer runs");
            }
            if (stream.get().push() || !stream.get().backfillAllowed()) {
                return fail("the stream no longer allows backfill");
            }
            Optional<LogTransport> transport = transports.find(stream.get().transport());
            if (transport.isEmpty()) {
                return fail("transport " + stream.get().transport() + " is not registered");
            }
            StreamContext ctx = new StreamContext(stream.get(), binding.get().sourceKind(), IngestMode.BACKFILL, clock,
                    ScopedObjects.forJob(objects, job.id()));
            BoundedReader reader = new BoundedReader(pacers.apply(BatchPacer.effectiveRate(props.maxBatchesPerSecond(),
                    stream.get().requestsPerSecond())));
            Duration slice = BoundedReader.sliceSize(transport.get().descriptor(), props);
            log.info("Backfill job {} runs: binding={} stream={} window=[{}, {}) slice={} batches={}", job.id(),
                    job.bindingId(), job.streamId(), job.from(), job.to(), slice, progress.batches());
            try {
                List<String> partitions = transport.get().partitions(ctx);
                for (String key : partitions) {
                    if (progress.partition(key).isEmpty()) {
                        progress = progress.withPartition(new BackfillProgress.Partition(key, job.from(), null,
                                Map.of(), false));
                    }
                }
                for (String key : partitions) {
                    RunResult stop = readPartition(transport.get(), ctx, reader, key, slice);
                    if (stop != null) {
                        return stop;
                    }
                }
                Instant now = clock.instant();
                BackfillProgress last = progress.withHeartbeat(now);
                if (!jobs.finish(job.id(), BackfillStatus.SUCCEEDED, now, "", last)) {
                    return RunResult.CANCELLED;
                }
                ended(BackfillStatus.SUCCEEDED, now);
                log.info("Backfill job {} succeeded: batches={} records={} quarantined={} duplicates={}", job.id(),
                        last.batches(), last.records(), last.quarantined(), last.duplicates());
                return RunResult.COMPLETED;
            } catch (BatchPacer.InterruptedWaitException e) {
                log.info("Backfill job {} paused: the service is stopping; it resumes from batch {}", job.id(),
                        progress.batches());
                return RunResult.STOPPED;
            } catch (RuntimeException e) {
                return failedAttempt(e);
            }
        }

        /** Reads one partition to the end of the window; null means "go on to the next partition". */
        private RunResult readPartition(LogTransport transport, StreamContext ctx, BoundedReader reader, String key,
                                        Duration slice) {
            BackfillProgress.Partition part = progress.partition(key).orElseThrow();
            while (!part.done()) {
                if (stopRequested.getAsBoolean()) {
                    return RunResult.STOPPED;
                }
                // Doubles as the cancel check: the guarded update touches nothing once the job ended.
                if (!jobs.saveProgress(job.id(), progress.withHeartbeat(clock.instant()))) {
                    return RunResult.CANCELLED;
                }
                Instant low = part.low() == null ? job.from() : part.low();
                Instant sliceEnd = BoundedReader.sliceEnd(low, job.to(), slice);
                FetchWindow window = new FetchWindow(low, sliceEnd);
                CursorPosition start = new CursorPosition(low, part.high(), part.state());
                BoundedReader.Result result = reader.readSlice(transport, ctx, key, start, window,
                        new BoundedReader.Handler() {
                            @Override
                            public BoundedReader.Verdict onBatch(RawBatch batch) {
                                return Execution.this.onBatch(ctx, key, sliceEnd, batch);
                            }

                            @Override
                            public BoundedReader.Verdict onUnreadable(ObjectReadException failure) {
                                return Execution.this.onUnreadable(ctx, key, failure);
                            }
                        });
                if (cancelled) {
                    return RunResult.CANCELLED;
                }
                if (stopping) {
                    return RunResult.STOPPED;
                }
                if (result.outcome() == BoundedReader.Outcome.INCOMPLETE) {
                    throw new IncompleteSliceException(key);
                }
                BackfillProgress.Partition current = progress.partition(key).orElseThrow();
                part = new BackfillProgress.Partition(key, sliceEnd, current.high(), current.state(),
                        !sliceEnd.isBefore(job.to()));
                progress = progress.withPartition(part);
                if (!jobs.saveProgress(job.id(), progress.withHeartbeat(clock.instant()))) {
                    return RunResult.CANCELLED;
                }
            }
            return null;
        }

        private BoundedReader.Verdict onBatch(StreamContext ctx, String key, Instant sliceEnd, RawBatch batch) {
            if (stopRequested.getAsBoolean()) {
                stopping = true;
                return BoundedReader.Verdict.STOP;
            }
            Optional<RawBatch.ObjectRef> object = batch.object();
            long quarantined = 0;
            long duplicates = 0;
            long records = 0;
            if (object.isPresent() && ctx.alreadyProcessed(object.get().key(), object.get().etag())) {
                log.debug("Backfill job {} skips a finished object", job.id());
            } else {
                try {
                    BatchReport report = ingest.ingestBatch(batch.records(), false);
                    records = batch.records().size();
                    quarantined = report.quarantinedCount();
                    duplicates = report.count(Disposition.DUPLICATE);
                    long q = quarantined;
                    metrics.batch(job.bindingId(), records, quarantined);
                    object.ifPresent(o -> ctx.objects().markDone(ctx.stream().bindingId(), ctx.stream().streamId(),
                            o.key(), o.etag(), batch.records().size(), q));
                } catch (RuntimeException e) {
                    object.ifPresent(o -> markFailed(ctx, o));
                    object.ifPresent(o -> metrics.objectFailed(job.bindingId(), BackfillMetrics.REASON_INGEST));
                    throw e;
                }
            }
            BackfillProgress.Partition before = progress.partition(key).orElseThrow();
            Instant low = later(before.low(), batch.advanceTo().lowWatermark());
            if (low != null && low.isAfter(sliceEnd)) {
                low = sliceEnd;
            }
            progress = progress
                    .withPartition(new BackfillProgress.Partition(key, low, later(before.high(),
                            batch.advanceTo().highWatermark()), batch.advanceTo().state(), false))
                    .afterBatch(records, quarantined, duplicates, clock.instant());
            if (!jobs.saveProgress(job.id(), progress)) {
                cancelled = true;
                return BoundedReader.Verdict.STOP;
            }
            return BoundedReader.Verdict.CONTINUE;
        }

        /**
         * An object could not be read (Q-702). Below the read-attempt cap the exception goes on, so the
         * attempt fails and the job retries after its backoff, exactly as for any other failure. At the cap
         * the object is quarantined as READ_FAILED, checkpointed under the job and skipped, and the job reads
         * on; the stream's own checkpoints are not touched.
         */
        private BoundedReader.Verdict onUnreadable(StreamContext ctx, String key, ObjectReadException failure) {
            if (stopRequested.getAsBoolean()) {
                stopping = true;
                return BoundedReader.Verdict.STOP;
            }
            metrics.objectFailed(job.bindingId(), BackfillMetrics.REASON_READ);
            if (!readFailures.skipUnreadable(ctx, key, failure, "backfill job " + job.id())) {
                throw failure;
            }
            progress = progress.afterSkippedObject(clock.instant());
            if (!jobs.saveProgress(job.id(), progress)) {
                cancelled = true;
                return BoundedReader.Verdict.STOP;
            }
            return BoundedReader.Verdict.CONTINUE;
        }

        private void markFailed(StreamContext ctx, RawBatch.ObjectRef o) {
            try {
                ctx.objects().markFailed(ctx.stream().bindingId(), ctx.stream().streamId(), o.key(), o.etag());
            } catch (RuntimeException e) {
                log.warn("Could not record the failed object of backfill job {}: {}", job.id(), e.getClass().getSimpleName());
            }
        }

        private RunResult failedAttempt(RuntimeException e) {
            Instant now = clock.instant();
            String error = e.getClass().getSimpleName();
            int attempts = progress.attempts() + 1;
            // Only the class is stored and returned; the message may quote provider data or SQL.
            log.error("Backfill job {} attempt {} failed ({}): {}", job.id(), attempts, error, e.getMessage(), e);
            if (attempts >= props.maxAttempts()) {
                progress = progress.afterFailure(error, null, now);
                jobs.finish(job.id(), BackfillStatus.FAILED, now,
                        "stopped after " + attempts + " failed attempts, the last: " + error, progress);
                ended(BackfillStatus.FAILED, now);
                return RunResult.FAILED;
            }
            long factor = 1L << Math.min(attempts - 1, 20);
            Duration wait = props.retryBackoff().multipliedBy(factor);
            Duration cap = Duration.ofMinutes(BackfillProperties.MAX_BACKOFF_MINUTES);
            progress = progress.afterFailure(error, now.plus(wait.compareTo(cap) > 0 ? cap : wait), now);
            jobs.saveProgress(job.id(), progress);
            return RunResult.RETRY;
        }

        private RunResult fail(String reason) {
            Instant now = clock.instant();
            log.warn("Backfill job {} failed: {}", job.id(), reason);
            jobs.finish(job.id(), BackfillStatus.FAILED, now, reason, progress.withHeartbeat(now));
            ended(BackfillStatus.FAILED, now);
            return RunResult.FAILED;
        }

        private void ended(BackfillStatus status, Instant at) {
            metrics.jobEnded(job.bindingId(), status, job.startedAt() != null ? job.startedAt() : runStartedAt, at);
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null ? a : (a.isAfter(b) ? a : b);
    }
}
