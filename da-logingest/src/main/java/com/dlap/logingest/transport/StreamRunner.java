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

package com.dlap.logingest.transport;

import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.parse.core.ConditionalOnRegistryPipeline;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.logingest.service.registry.BatchReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.cursor.StreamCursorStore;
import com.dlap.logingest.transport.lock.AdvisoryLocks;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs one poll of one stream (design B5, B6):
 * <ol>
 *   <li>take the stream's advisory lock, or skip the poll because another replica runs it;</li>
 *   <li>per partition, load the cursor and read {@code [low - overlap, now - settle)}, or
 *       {@code [now - initial lookback, now - settle)} on the first poll;</li>
 *   <li>per batch: skip an object already read, ingest every record through the registry, mark
 *       the object done, commit the cursor, then tell the transport;</li>
 *   <li>on a failure: mark the object failed, stop the partition without moving its cursor, so the
 *       next poll reads the batch again (event-id dedup absorbs what was already stored);</li>
 *   <li>on a read failure ({@link ObjectReadException}) the same, until the object has failed
 *       {@code dlap.logingest.transports.max-read-attempts} times (default {@value #DEFAULT_MAX_READ_ATTEMPTS},
 *       counted in {@code processed_object.attempts}): then the runner quarantines the object with the
 *       detail {@code READ_FAILED}, checkpoints it as done and goes on with the rest of the partition,
 *       so one unreadable object (AccessDenied, a KMS key the reader may not use, a corrupt shard) no
 *       longer blocks the stream forever (Q-665).</li>
 * </ol>
 */
@Component
@ConditionalOnRegistryPipeline
public class StreamRunner {

    /** Failed reads of one object before it is quarantined and skipped. */
    public static final int DEFAULT_MAX_READ_ATTEMPTS = ReadFailureQuarantine.DEFAULT_MAX_READ_ATTEMPTS;

    /** The start of the quarantine detail of an object that could not be read. */
    public static final String READ_FAILED = ReadFailureQuarantine.READ_FAILED;

    private static final Logger log = LoggerFactory.getLogger(StreamRunner.class);

    private final EffectiveLogSources sources;
    private final TransportRegistry transports;
    private final RegistryIngestService ingest;
    private final StreamCursorStore cursors;
    private final ProcessedObjectStore objects;
    private final AdvisoryLocks locks;
    private final Clock clock;
    private final ReadFailureQuarantine readFailures;

    /**
     * The outcome of one poll.
     *
     * @param ran         false when the lock was held elsewhere or the transport is missing
     * @param batches     batches committed
     * @param records     records ingested
     * @param quarantined records quarantined
     * @param failed      partitions that stopped on a failure
     */
    public record PollResult(boolean ran, int batches, long records, long quarantined, int failed) {

        /** A poll that did not run. */
        public static final PollResult SKIPPED = new PollResult(false, 0, 0, 0, 0);
    }

    /**
     * Creates the runner.
     *
     * @param sources         the effective configuration
     * @param transports      the transports
     * @param ingest          the registry ingest path
     * @param cursors         the cursor store
     * @param objects         the processed-object store
     * @param locks           the advisory locks
     * @param clock           the application clock when unique, else the UTC system clock
     * @param sinks           the quarantine sinks, which receive an object that could not be read
     * @param maxReadAttempts failed reads of one object before it is quarantined and skipped
     *                        ({@code dlap.logingest.transports.max-read-attempts})
     */
    @Autowired
    public StreamRunner(EffectiveLogSources sources, TransportRegistry transports, RegistryIngestService ingest,
                        StreamCursorStore cursors, ProcessedObjectStore objects, AdvisoryLocks locks,
                        ObjectProvider<Clock> clock, ObjectProvider<QuarantineSink> sinks,
                        @Value("${dlap.logingest.transports.max-read-attempts:" + DEFAULT_MAX_READ_ATTEMPTS
                                + "}") int maxReadAttempts) {
        this(sources, transports, ingest, cursors, objects, locks, clock.getIfUnique(Clock::systemUTC), sinks,
                maxReadAttempts);
    }

    /**
     * Creates the runner with a fixed clock and the default read-attempt cap; a read failure is
     * delivered to the logging sink only.
     *
     * @param sources    the effective configuration
     * @param transports the transports
     * @param ingest     the registry ingest path
     * @param cursors    the cursor store
     * @param objects    the processed-object store
     * @param locks      the advisory locks
     * @param clock      the clock
     */
    public StreamRunner(EffectiveLogSources sources, TransportRegistry transports, RegistryIngestService ingest,
                        StreamCursorStore cursors, ProcessedObjectStore objects, AdvisoryLocks locks, Clock clock) {
        this(sources, transports, ingest, cursors, objects, locks, clock, null, DEFAULT_MAX_READ_ATTEMPTS);
    }

    /**
     * Creates the runner with a fixed clock, quarantine sinks and a read-attempt cap.
     *
     * @param sources         the effective configuration
     * @param transports      the transports
     * @param ingest          the registry ingest path
     * @param cursors         the cursor store
     * @param objects         the processed-object store
     * @param locks           the advisory locks
     * @param clock           the clock
     * @param sinks           the quarantine sinks, or {@code null} for the logging sink only
     * @param maxReadAttempts failed reads of one object before it is quarantined and skipped; at least 1
     */
    public StreamRunner(EffectiveLogSources sources, TransportRegistry transports, RegistryIngestService ingest,
                        StreamCursorStore cursors, ProcessedObjectStore objects, AdvisoryLocks locks, Clock clock,
                        ObjectProvider<QuarantineSink> sinks, int maxReadAttempts) {
        this.sources = sources;
        this.transports = transports;
        this.ingest = ingest;
        this.cursors = cursors;
        this.objects = objects;
        this.locks = locks;
        this.clock = clock;
        this.readFailures = new ReadFailureQuarantine(sources, clock, sinks, maxReadAttempts);
    }

    /**
     * Polls one stream once.
     *
     * @param stream the stream
     * @return what the poll did
     */
    public PollResult poll(EffectiveStream stream) {
        Optional<LogTransport> transport = transports.find(stream.transport());
        if (transport.isEmpty() || stream.push()) {
            return PollResult.SKIPPED;
        }
        String kind = sources.binding(stream.bindingId()).map(b -> b.sourceKind()).orElse(null);
        if (kind == null) {
            return PollResult.SKIPPED;
        }
        Optional<AdvisoryLocks.Lease> lease = locks.tryLockStream(stream.bindingId(), stream.streamId());
        if (lease.isEmpty()) {
            log.debug("Poll of {}/{} skipped: another replica holds its lock", stream.bindingId(), stream.streamId());
            return PollResult.SKIPPED;
        }
        try (AdvisoryLocks.Lease held = lease.get()) {
            StreamContext ctx = new StreamContext(stream, kind, IngestMode.STREAM, clock, objects);
            int batches = 0;
            long records = 0;
            long quarantined = 0;
            int failed = 0;
            for (String partition : transport.get().partitions(ctx)) {
                PartitionResult r = pollPartition(transport.get(), ctx, partition);
                batches += r.batches;
                records += r.records;
                quarantined += r.quarantined;
                failed += r.failed ? 1 : 0;
            }
            return new PollResult(true, batches, records, quarantined, failed);
        }
    }

    private PartitionResult pollPartition(LogTransport transport, StreamContext ctx, String partition) {
        EffectiveStream stream = ctx.stream();
        TransportDescriptor d = transport.descriptor();
        CursorPosition cursor = cursors.load(stream.bindingId(), stream.streamId(), partition)
                .orElse(CursorPosition.INITIAL);
        Instant now = clock.instant();
        Instant to = now.minus(d.settle());
        Instant from = cursor.lowWatermark() == null
                ? now.minus(d.initialLookback())
                : cursor.lowWatermark().minus(stream.overlap());
        if (from.isAfter(to)) {
            from = to;
        }
        PartitionResult result = new PartitionResult();
        try (RawBatchIterator it = transport.fetch(ctx, partition, cursor, new FetchWindow(from, to))) {
            while (true) {
                RawBatch batch;
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    batch = it.next();
                } catch (ObjectReadException e) {
                    if (readFailures.skipUnreadable(ctx, partition, e, "poll")) {
                        result.quarantined++;
                        continue;
                    }
                    result.failed = true;
                    return result;
                }
                Optional<RawBatch.ObjectRef> object = batch.object();
                try {
                    if (object.isPresent() && ctx.alreadyProcessed(object.get().key(), object.get().etag())) {
                        cursors.commit(stream.bindingId(), stream.streamId(), partition, batch.advanceTo());
                        continue;
                    }
                    BatchReport report = ingest.ingestBatch(batch.records(), false);
                    object.ifPresent(o -> objects.markDone(stream.bindingId(), stream.streamId(), o.key(), o.etag(),
                            batch.records().size(), report.quarantinedCount()));
                    cursors.commit(stream.bindingId(), stream.streamId(), partition, batch.advanceTo());
                    transport.committed(ctx, batch);
                    result.batches++;
                    result.records += batch.records().size();
                    result.quarantined += report.quarantinedCount();
                } catch (RuntimeException e) {
                    object.ifPresent(o -> markFailed(stream, o));
                    log.error("Poll of {}/{} partition '{}' stopped; the cursor stays where it was: {}",
                            stream.bindingId(), stream.streamId(), partition, e.getMessage(), e);
                    result.failed = true;
                    return result;
                }
            }
        } catch (RuntimeException e) {
            log.error("Fetch of {}/{} partition '{}' failed: {}", stream.bindingId(), stream.streamId(), partition,
                    e.getMessage(), e);
            result.failed = true;
        }
        return result;
    }

    private void markFailed(EffectiveStream stream, RawBatch.ObjectRef o) {
        try {
            objects.markFailed(stream.bindingId(), stream.streamId(), o.key(), o.etag());
        } catch (RuntimeException e) {
            log.warn("Could not record the failed object of {}/{}: {}", stream.bindingId(), stream.streamId(),
                    e.getMessage());
        }
    }

    /** Counters of one partition. */
    private static final class PartitionResult {
        private int batches;
        private long records;
        private long quarantined;
        private boolean failed;
    }
}
