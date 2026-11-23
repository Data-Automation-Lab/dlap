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

import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.LogTransport;
import com.dlap.logingest.transport.ObjectReadException;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import java.time.Duration;
import java.time.Instant;

/**
 * The one way history is read, for backfill jobs and for the source re-read of quarantine replay
 * (decision D26): a transport is asked for a bounded event-time slice, a batch at a time, and every
 * batch waits for the {@link BatchPacer}. Nothing here parses, stores or publishes; the caller's
 * {@link Handler} does, so a job commits its cursor and a re-read keeps only the record it wanted.
 *
 * <p><b>Completion rule.</b> A slice is complete when its iterator is exhausted and either it
 * delivered nothing or its last batch moved the low watermark to the slice end. A transport that
 * stops short of the end without raising (the BigQuery JOBS reader does when the view's columns
 * drifted: it emits one marker record and leaves the cursor) leaves the slice
 * {@link Outcome#INCOMPLETE}, and the caller must not move past it. That is what keeps a
 * history read free of silent gaps.
 */
final class BoundedReader {

    /** What the handler wants after a batch. */
    enum Verdict {
        /** Read the next batch. */
        CONTINUE,
        /** Stop reading this slice now. */
        STOP
    }

    /** How a slice read ended. */
    enum Outcome {
        /** Every batch was delivered up to the slice end. */
        COMPLETE,
        /** The handler stopped the read. */
        STOPPED,
        /** The transport ended before the slice end without an error. */
        INCOMPLETE
    }

    /**
     * Receives the batches of a slice.
     */
    @FunctionalInterface
    interface Handler {

        /**
         * Takes one batch. A runtime exception aborts the read and reaches the caller.
         *
         * @param batch the batch
         * @return whether to go on
         */
        Verdict onBatch(RawBatch batch);

        /**
         * Takes an object the transport could not read ({@link ObjectReadException}). The iterator is
         * positioned past the object. The default rethrows, so a reader that does not cap read failures
         * (the source re-read of a quarantine replay) fails the attempt as before; the backfill runner
         * counts the failure and, at the cap, quarantines the object and returns
         * {@link Verdict#CONTINUE} (Q-702).
         *
         * @param failure the read failure
         * @return whether to go on with the objects behind it
         */
        default Verdict onUnreadable(ObjectReadException failure) {
            throw failure;
        }
    }

    /**
     * The result of one slice.
     *
     * @param outcome  how it ended
     * @param batches  batches delivered to the handler
     * @param lastLow  the low watermark of the last batch, or {@code null} when none came
     */
    record Result(Outcome outcome, int batches, Instant lastLow) {
    }

    private final BatchPacer pacer;

    /**
     * Creates a reader.
     *
     * @param pacer spaces the batches
     */
    BoundedReader(BatchPacer pacer) {
        this.pacer = pacer;
    }

    /**
     * Reads one slice of one partition.
     *
     * @param transport the transport
     * @param ctx       the stream context (mode {@code BACKFILL})
     * @param partition the partition key
     * @param cursor    the position to start from
     * @param slice     the event-time slice
     * @param handler   what to do with each batch
     * @return how the read ended
     */
    Result readSlice(LogTransport transport, StreamContext ctx, String partition, CursorPosition cursor,
                     FetchWindow slice, Handler handler) {
        int batches = 0;
        Instant lastLow = null;
        try (RawBatchIterator it = transport.fetch(ctx, partition, cursor, slice)) {
            while (true) {
                RawBatch batch;
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    pacer.acquire();
                    batch = it.next();
                } catch (ObjectReadException e) {
                    if (handler.onUnreadable(e) == Verdict.STOP) {
                        return new Result(Outcome.STOPPED, batches, lastLow);
                    }
                    continue;
                }
                Verdict verdict = handler.onBatch(batch);
                batches++;
                lastLow = batch.advanceTo().lowWatermark();
                if (verdict == Verdict.STOP) {
                    return new Result(Outcome.STOPPED, batches, lastLow);
                }
            }
        }
        boolean reachedEnd = batches == 0 || (lastLow != null && !lastLow.isBefore(slice.to()));
        return new Result(reachedEnd ? Outcome.COMPLETE : Outcome.INCOMPLETE, batches, lastLow);
    }

    /**
     * The span of one fetch for a transport.
     *
     * @param descriptor the transport
     * @param props      the settings
     * @return {@code slice-time} for time cursors, {@code slice-object} for object cursors
     */
    static Duration sliceSize(TransportDescriptor descriptor, BackfillProperties props) {
        return descriptor.cursorKind() == TransportDescriptor.CursorKind.OBJECT ? props.sliceObject()
                : props.sliceTime();
    }

    /**
     * The end of the slice that starts at {@code low}.
     *
     * @param low  the slice start
     * @param to   the end of the whole window
     * @param size the slice span
     * @return {@code low + size}, capped at {@code to}
     */
    static Instant sliceEnd(Instant low, Instant to, Duration size) {
        Instant end = low.plus(size);
        return end.isAfter(to) ? to : end;
    }
}
