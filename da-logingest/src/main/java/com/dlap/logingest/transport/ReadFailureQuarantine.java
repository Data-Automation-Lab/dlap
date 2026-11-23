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

import com.dlap.common.logevent.LogEventIds;
import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.logingest.service.impl.LoggingQuarantineSink;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.RawRecordRef;
import com.dlap.proto.log.v1.SourceProvider;
import com.google.protobuf.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The read-failure cap and quarantine that {@link StreamRunner} and the backfill runner share
 * (ADR-0110, Q-665, Q-702). An object that a transport could not read ({@link ObjectReadException})
 * is counted in {@code processed_object.attempts}; before
 * {@code dlap.logingest.transports.max-read-attempts} failures the caller stops and retries, and at the
 * cap this class writes one quarantine record to every {@link QuarantineSink} (reason
 * {@code PARSER_EXCEPTION}, detail starting {@value #READ_FAILED}, the exception class only, never its
 * message), checkpoints the object as done with 0 records and 1 quarantined, and tells the caller to go
 * on with the objects behind it.
 *
 * <p>The checkpoint store is the one of the {@link StreamContext}: a stream's own for a poll, the
 * job's pseudo stream for a backfill, so a backfill never touches the checkpoints of the stream.
 */
public final class ReadFailureQuarantine {

    /** Failed reads of one object before it is quarantined and skipped. */
    public static final int DEFAULT_MAX_READ_ATTEMPTS = 5;

    /** The start of the quarantine detail of an object that could not be read. */
    public static final String READ_FAILED = "READ_FAILED";

    private static final Logger log = LoggerFactory.getLogger(ReadFailureQuarantine.class);

    private final EffectiveLogSources sources;
    private final Clock clock;
    private final ObjectProvider<QuarantineSink> sinks;
    private final int maxReadAttempts;

    /**
     * Creates the policy.
     *
     * @param sources         the effective configuration, for the binding's provider
     * @param clock           the clock of the quarantine time
     * @param sinks           the quarantine sinks, or {@code null} for the logging sink only
     * @param maxReadAttempts failed reads of one object before it is quarantined and skipped; at least 1
     */
    public ReadFailureQuarantine(EffectiveLogSources sources, Clock clock, ObjectProvider<QuarantineSink> sinks,
                                 int maxReadAttempts) {
        this.sources = sources;
        this.clock = clock;
        this.sinks = sinks;
        this.maxReadAttempts = Math.max(1, maxReadAttempts);
    }

    /**
     * Records a failed read of an object and decides what happens next.
     *
     * @param ctx       the stream context; its checkpoint store holds the attempt count
     * @param partition the partition, for the log
     * @param e         the failure
     * @param who       what is reading ({@code poll} or {@code backfill job <id>}), for the log
     * @return true when the object reached its attempt cap, was quarantined and checkpointed, so the
     *         reader goes on; false when it stays to be retried
     */
    public boolean skipUnreadable(StreamContext ctx, String partition, ObjectReadException e, String who) {
        EffectiveStream stream = ctx.stream();
        ProcessedObjectStore objects = ctx.objects();
        RawBatch.ObjectRef o = e.object();
        int attempts;
        try {
            objects.markFailed(stream.bindingId(), stream.streamId(), o.key(), o.etag());
            attempts = objects.attempts(stream.bindingId(), stream.streamId(), o.key(), o.etag());
        } catch (RuntimeException ex) {
            log.warn("Could not record the failed read of an object of {}/{}: {}", stream.bindingId(),
                    stream.streamId(), ex.getMessage());
            log.error("Read of {}/{} partition '{}' failed ({}); the cursor stays where it was: {}",
                    stream.bindingId(), stream.streamId(), partition, who, e.getMessage(), e);
            return false;
        }
        if (attempts < maxReadAttempts) {
            log.error("Read of {}/{} partition '{}' failed (attempt {} of {}, {}); the cursor stays where it was: {}",
                    stream.bindingId(), stream.streamId(), partition, attempts, maxReadAttempts, who, e.getMessage(),
                    e);
            return false;
        }
        try {
            deliver(readFailedRecord(ctx, e, attempts));
            objects.markDone(stream.bindingId(), stream.streamId(), o.key(), o.etag(), 0, 1);
        } catch (RuntimeException ex) {
            log.error("Object of {}/{} partition '{}' failed {} reads but could not be quarantined ({}); retried "
                    + "next time: {}", stream.bindingId(), stream.streamId(), partition, attempts, who,
                    ex.getMessage(), ex);
            return false;
        }
        log.error("Object of {}/{} partition '{}' quarantined as {} after {} failed reads ({}); reading goes on: {}",
                stream.bindingId(), stream.streamId(), partition, READ_FAILED, attempts, who, e.getMessage());
        return true;
    }

    private void deliver(LogQuarantineRecord record) {
        List<QuarantineSink> registered = sinks == null ? List.of() : sinks.orderedStream().toList();
        if (registered.isEmpty()) {
            new LoggingQuarantineSink().accept(record);
            return;
        }
        registered.forEach(s -> s.accept(record));
    }

    /**
     * The quarantine record of an object that could not be read. The proto has no read-failure
     * reason, so the reason is {@code PARSER_EXCEPTION} and the detail starts with {@code READ_FAILED}
     * and names only the exception class, never its message (Q-701).
     */
    private LogQuarantineRecord readFailedRecord(StreamContext ctx, ObjectReadException e, int attempts) {
        EffectiveStream stream = ctx.stream();
        RawBatch.ObjectRef o = e.object();
        Optional<EffectiveBinding> binding = sources.binding(stream.bindingId());
        RawRecordRef ref = RawRecordRef.newBuilder().setTransport(stream.transport()).setUri(e.uri())
                .setRecordIndex(-1).setCursor(o.etag()).build();
        Throwable cause = e.getCause() == null ? e : e.getCause();
        Instant now = clock.instant();
        String sha = LogEventIds.sha256Hex(o.key() + "\n" + o.etag());
        return LogQuarantineRecord.newBuilder()
                .setQuarantineId(LogEventIds.quarantineId(stream.bindingId(), ref, sha))
                .setBindingId(stream.bindingId())
                .setSourceProvider(binding.flatMap(EffectiveBinding::provider)
                        .orElse(SourceProvider.SOURCE_PROVIDER_UNSPECIFIED))
                .setSourceKind(ctx.sourceKind())
                .setEnvelope(stream.envelope() == null ? "" : stream.envelope())
                .setReason(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION)
                .setReasonDetail(READ_FAILED + ": " + cause.getClass().getSimpleName() + " after " + attempts
                        + " failed reads")
                .setRawRef(ref)
                .setRawSha256(sha)
                .setRawSizeBytes(0)
                .setQuarantinedTime(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                .setSchemaVersion("v1")
                .build();
    }
}
