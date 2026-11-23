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

import static com.dlap.logingest.backfill.BackfillTestKit.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.backfill.BackfillTestKit.Event;
import com.dlap.logingest.backfill.BackfillTestKit.FakePacing;
import com.dlap.logingest.backfill.BackfillTestKit.RecordingObjects;
import com.dlap.logingest.backfill.BackfillTestKit.ScriptedTransport;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.ObjectReadException;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one bounded reader: completion rule, handler stop, pacing, and the slice arithmetic. */
class BoundedReaderTest {

    private static final Instant FROM = T0;
    private static final Instant TO = T0.plus(Duration.ofHours(1));

    private final FakePacing pacing = new FakePacing();
    private final BoundedReader reader = new BoundedReader(pacing.pacer(2.0));
    private final EffectiveStream stream = BackfillTestKit.stream(true, false, null);
    private final StreamContext ctx = new StreamContext(stream, BackfillTestKit.KIND, IngestMode.BACKFILL,
            Clock.fixed(T0, ZoneOffset.UTC), new RecordingObjects());

    private ScriptedTransport transport(int events, int pageSize) {
        ScriptedTransport t = new ScriptedTransport(TransportDescriptor.CursorKind.TIME, pageSize);
        for (int i = 0; i < events; i++) {
            t.add("", Event.at(FROM.plusSeconds(60L * i), "e-" + i));
        }
        return t;
    }

    private BoundedReader.Result read(ScriptedTransport t, BoundedReader.Handler h) {
        return reader.readSlice(t, ctx, "", new CursorPosition(FROM, null, Map.of()), new FetchWindow(FROM, TO), h);
    }

    private ScriptedTransport objectsWithAnUnreadableOne() {
        ScriptedTransport t = new ScriptedTransport(TransportDescriptor.CursorKind.OBJECT, 10);
        t.add("", new Event(FROM.plusSeconds(10), "a-1", "bad"), new Event(FROM.plusSeconds(70), "b-1", "good"));
        t.unreadable.put("bad", 1);
        return t;
    }

    @Test
    void anUnreadableObjectGoesToTheHandlerAndTheSliceStillCompletesBehindIt() {
        List<String> unreadable = new ArrayList<>();
        List<RawBatch> seen = new ArrayList<>();

        BoundedReader.Result r = read(objectsWithAnUnreadableOne(), new BoundedReader.Handler() {
            @Override
            public BoundedReader.Verdict onBatch(RawBatch batch) {
                seen.add(batch);
                return BoundedReader.Verdict.CONTINUE;
            }

            @Override
            public BoundedReader.Verdict onUnreadable(ObjectReadException failure) {
                unreadable.add(failure.object().key());
                return BoundedReader.Verdict.CONTINUE;
            }
        });

        assertThat(unreadable).containsExactly("bad");
        assertThat(seen).as("the readable object and the closing empty batch").hasSize(2);
        assertThat(r.outcome()).isEqualTo(BoundedReader.Outcome.COMPLETE);
        assertThat(r.lastLow()).isEqualTo(TO);
    }

    @Test
    void aHandlerThatDoesNotCapReadFailuresLetsTheExceptionThrough() {
        assertThatThrownBy(() -> read(objectsWithAnUnreadableOne(), b -> BoundedReader.Verdict.CONTINUE))
                .isInstanceOfSatisfying(ObjectReadException.class, e -> assertThat(e.object().key()).isEqualTo("bad"));
    }

    @Test
    void aHandlerCanStopTheSliceOnAnUnreadableObject() {
        BoundedReader.Result r = read(objectsWithAnUnreadableOne(), new BoundedReader.Handler() {
            @Override
            public BoundedReader.Verdict onBatch(RawBatch batch) {
                return BoundedReader.Verdict.CONTINUE;
            }

            @Override
            public BoundedReader.Verdict onUnreadable(ObjectReadException failure) {
                return BoundedReader.Verdict.STOP;
            }
        });

        assertThat(r.outcome()).isEqualTo(BoundedReader.Outcome.STOPPED);
        assertThat(r.batches()).isZero();
    }

    @Test
    void aSliceThatReachesItsEndIsCompleteAndEveryBatchWaitsForThePacer() {
        List<RawBatch> seen = new ArrayList<>();

        BoundedReader.Result r = read(transport(10, 3), b -> {
            seen.add(b);
            return BoundedReader.Verdict.CONTINUE;
        });

        assertThat(r.outcome()).isEqualTo(BoundedReader.Outcome.COMPLETE);
        assertThat(r.batches()).isEqualTo(4);
        assertThat(r.lastLow()).isEqualTo(TO);
        assertThat(seen).hasSize(4);
        assertThat(pacing.sleeps).as("three waits between four batches").hasSize(3);
    }

    @Test
    void anEmptySliceIsComplete() {
        BoundedReader.Result r = reader.readSlice(new ScriptedTransport(TransportDescriptor.CursorKind.TIME, 3) {
            @Override
            public com.dlap.logingest.transport.RawBatchIterator fetch(StreamContext c, String p, CursorPosition cur,
                    FetchWindow w) {
                return com.dlap.logingest.transport.RawBatchIterator.of(List.of());
            }
        }, ctx, "", CursorPosition.INITIAL, new FetchWindow(FROM, TO), b -> BoundedReader.Verdict.CONTINUE);

        assertThat(r.outcome()).isEqualTo(BoundedReader.Outcome.COMPLETE);
        assertThat(r.batches()).isZero();
        assertThat(r.lastLow()).isNull();
    }

    @Test
    void aTransportThatEndsShortOfTheSliceEndIsIncomplete() {
        ScriptedTransport t = transport(10, 3);
        t.stopShort = true;

        BoundedReader.Result r = read(t, b -> BoundedReader.Verdict.CONTINUE);

        assertThat(r.outcome()).isEqualTo(BoundedReader.Outcome.INCOMPLETE);
        assertThat(r.lastLow()).isBefore(TO);
    }

    @Test
    void theHandlerCanStopTheReadAndNoFurtherBatchIsFetched() {
        List<RawBatch> seen = new ArrayList<>();

        BoundedReader.Result r = read(transport(10, 3), b -> {
            seen.add(b);
            return seen.size() == 2 ? BoundedReader.Verdict.STOP : BoundedReader.Verdict.CONTINUE;
        });

        assertThat(r.outcome()).isEqualTo(BoundedReader.Outcome.STOPPED);
        assertThat(r.batches()).isEqualTo(2);
        assertThat(seen).hasSize(2);
    }

    @Test
    void aHandlerFailureReachesTheCaller() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> read(transport(3, 3), b -> {
            throw new IllegalStateException("handler failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("handler failed");
    }

    @Test
    void sliceSizeFollowsTheCursorKindAndTheEndIsCappedAtTo() {
        BackfillProperties p = BackfillProperties.defaults();
        TransportDescriptor time = new TransportDescriptor("A_B", java.util.Set.of(), java.util.Set.of(),
                TransportDescriptor.CursorKind.TIME, Duration.ofHours(1), Duration.ZERO);
        TransportDescriptor object = new TransportDescriptor("A_B", java.util.Set.of(), java.util.Set.of(),
                TransportDescriptor.CursorKind.OBJECT, Duration.ofHours(1), Duration.ZERO);

        assertThat(BoundedReader.sliceSize(time, p)).isEqualTo(Duration.ofHours(1));
        assertThat(BoundedReader.sliceSize(object, p)).isEqualTo(Duration.ofDays(1));
        assertThat(BoundedReader.sliceEnd(FROM, TO, Duration.ofMinutes(20))).isEqualTo(FROM.plus(Duration.ofMinutes(20)));
        assertThat(BoundedReader.sliceEnd(FROM, TO, Duration.ofHours(5))).isEqualTo(TO);
        assertThat(BoundedReader.sliceEnd(TO, TO, Duration.ofHours(5))).isEqualTo(TO);
    }

    @Test
    void scopedObjectsKeepAJobsCheckpointsApartFromTheStreamAndNoneKeepsNothing() {
        RecordingObjects real = new RecordingObjects();
        java.util.UUID job = java.util.UUID.randomUUID();
        var scoped = ScopedObjects.forJob(real, job);

        scoped.markDone("b", "stream-id", "k", "e", 3, 1);
        scoped.markFailed("b", "stream-id", "k2", "e");

        assertThat(scoped.isDone("b", "stream-id", "k", "e")).isTrue();
        assertThat(real.isDone("b", "stream-id", "k", "e")).as("the stream's own checkpoint is untouched").isFalse();
        assertThat(real.done).containsExactly("backfill-" + job + "|k#e");
        assertThat(real.failed).containsExactly("backfill-" + job + "|k2#e");

        var none = ScopedObjects.none();
        none.markDone("b", "s", "k", "e", 1, 0);
        none.markFailed("b", "s", "k", "e");
        assertThat(none.isDone("b", "s", "k", "e")).isFalse();
    }
}
