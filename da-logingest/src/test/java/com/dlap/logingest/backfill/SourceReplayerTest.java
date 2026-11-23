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

import static com.dlap.logingest.backfill.BackfillTestKit.BINDING;
import static com.dlap.logingest.backfill.BackfillTestKit.ENVELOPE;
import static com.dlap.logingest.backfill.BackfillTestKit.KIND;
import static com.dlap.logingest.backfill.BackfillTestKit.T0;
import static com.dlap.logingest.backfill.BackfillTestKit.TRANSPORT;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.logevent.LogEventIds;
import com.dlap.logingest.backfill.BackfillTestKit.CountingIngest;
import com.dlap.logingest.backfill.BackfillTestKit.Event;
import com.dlap.logingest.backfill.BackfillTestKit.FakePacing;
import com.dlap.logingest.backfill.BackfillTestKit.ScriptedTransport;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.quarantine.QuarantineRow;
import com.dlap.logingest.quarantine.SourceReplayHook.Outcome;
import com.dlap.logingest.transport.TransportDescriptor;
import com.dlap.logingest.transport.TransportRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Decision D26 (Q-362): a quarantined record that the table cannot replay is re-read from its source by
 * its {@code raw_ref}, through the bounded reader, and nothing raw is stored for it.
 */
class SourceReplayerTest {

    private static final Instant NOW = T0.plus(Duration.ofDays(30));
    private static final Instant EVENT = T0.plus(Duration.ofDays(3)).plus(Duration.ofHours(5));

    private final MutableClock clock = new MutableClock(NOW);
    private final CountingIngest ingest = new CountingIngest();
    private final FakePacing pacing = new FakePacing();
    private ScriptedTransport transport;
    private EffectiveLogSources sources;
    private BackfillProperties props = BackfillProperties.defaults();

    @BeforeEach
    void setUp() {
        transport = new ScriptedTransport(TransportDescriptor.CursorKind.TIME, 5);
        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(true, false, null));
    }

    private SourceReplayer replayer() {
        return new SourceReplayer(sources, new TransportRegistry(List.of(transport)), ingest.service, props, clock,
                pacing::pacer);
    }

    private QuarantineRow row(String id, Instant eventTime, boolean truncated, long index) {
        String text = BackfillTestKit.json(id);
        Map<String, Object> ref = Map.of("transport", TRANSPORT, "uri", "scripted://b", "recordIndex", index,
                "cursor", "tok-" + id);
        return new QuarantineRow("q-" + id, BINDING, "AWS", KIND, ENVELOPE, "v1", "", "p@1", "INVALID_JSON", "d", ref,
                LogEventIds.sha256Hex(text), text.getBytes(StandardCharsets.UTF_8).length,
                truncated ? text.substring(0, 6) : text.replace(id, "<masked>"), truncated, eventTime, "", "",
                NOW.minus(Duration.ofDays(1)), null, 0);
    }

    @Test
    void aTruncatedRecordIsReReadByItsRawRefAndRunsThroughTheParsersInReplayMode() {
        transport.add("", Event.at(EVENT, "e-1"), Event.at(EVENT.plusSeconds(30), "e-2"),
                Event.at(EVENT.minus(Duration.ofHours(2)), "e-far"));
        QuarantineRow r = row("e-1", EVENT, true, 7);

        Map<String, Outcome> out = replayer().replay(List.of(r), false);

        assertThat(out).containsEntry("q-e-1", Outcome.RESOLVED);
        assertThat(ingest.seen).as("only the replayed record is ingested, not the rest of the window").hasSize(1);
        RawRecord copy = ingest.seen.get(0);
        assertThat(copy.mode()).isEqualTo(IngestMode.REPLAY);
        assertThat(copy.ref()).as("the stored identity, so a second failure updates the same row")
                .isEqualTo(new RawRef(TRANSPORT, "scripted://b", 7, "tok-e-1"));
        assertThat(copy.sha256()).isEqualTo(r.rawSha256());
        assertThat(new String(copy.bytes(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"e-1\"}");
        assertThat(copy.bindingId()).isEqualTo(BINDING);
        assertThat(copy.sourceKind()).isEqualTo(KIND);
        assertThat(copy.envelope()).isEqualTo(ENVELOPE);
    }

    @Test
    void aMaskedRecordIsReadWithItsRealContent() {
        transport.add("", Event.at(EVENT, "e-3"));
        QuarantineRow masked = row("e-3", EVENT, false, 0);
        assertThat(masked.notReplayableReason()).isEqualTo(QuarantineRow.NotReplayable.ALTERED);

        assertThat(replayer().replay(List.of(masked), false)).containsEntry("q-e-3", Outcome.RESOLVED);
        assertThat(new String(ingest.seen.get(0).bytes(), StandardCharsets.UTF_8)).doesNotContain("<masked>");
    }

    @Test
    void theSearchWindowIsTheEventTimePaddedAndNoWiderThanOneSlice() {
        transport.add("", Event.at(EVENT, "e-1"));

        replayer().replay(List.of(row("e-1", EVENT, true, 0)), false);

        assertThat(transport.calls).hasSize(1);
        BackfillTestKit.FetchCall call = transport.calls.get(0);
        assertThat(call.window().from()).isEqualTo(EVENT.minus(Duration.ofMinutes(5)));
        assertThat(call.window().to()).isEqualTo(EVENT.plus(Duration.ofMinutes(5)).plusMillis(1));
        assertThat(pacing.rates).containsExactly(5.0);
    }

    @Test
    void aRecordThatIsStillBrokenIsQuarantinedAgainUnderTheSameIdentity() {
        transport.add("", Event.at(EVENT, "bad-1"));

        Map<String, Outcome> out = replayer().replay(List.of(row("bad-1", EVENT, true, 2)), false);

        assertThat(out).containsEntry("q-bad-1", Outcome.STILL_QUARANTINED);
        assertThat(ingest.seen.get(0).ref().recordIndex()).isEqualTo(2);
    }

    @Test
    void aRecordTheSourceNoLongerHoldsIsNotFound() {
        transport.add("", Event.at(EVENT, "e-other"));

        Map<String, Outcome> out = replayer().replay(List.of(row("e-1", EVENT, true, 0)), false);

        assertThat(out).containsEntry("q-e-1", Outcome.NOT_FOUND);
        assertThat(ingest.calls).isZero();
    }

    @Test
    void aFileRecordRewrittenAtTheSamePlaceIsChangedAndNeverReplayed() {
        transport = new ScriptedTransport(TransportDescriptor.CursorKind.OBJECT, 5);
        transport.add("", new Event(EVENT, "e-REWRITTEN", "shard-1"));
        // The quarantined record sat at index 0 of the same object, with other bytes.
        QuarantineRow r = withRef(row("e-1", EVENT, true, 0), "scripted://b/shard-1", 0);

        Map<String, Outcome> out = replayer().replay(List.of(r), false);

        assertThat(out).containsEntry("q-e-1", Outcome.CHANGED);
        assertThat(ingest.calls).isZero();
    }

    @Test
    void anObjectRecordMatchesOnlyAtItsOwnIndexEvenWhenTwoRecordsHaveTheSameBytes() {
        transport = new ScriptedTransport(TransportDescriptor.CursorKind.OBJECT, 5);
        transport.add("", new Event(EVENT, "dup", "shard-1"), new Event(EVENT.plusSeconds(1), "dup", "shard-1"));
        QuarantineRow second = withRef(row("dup", EVENT, true, 1), "scripted://b/shard-1", 1);

        Map<String, Outcome> out = replayer().replay(List.of(second), false);

        assertThat(out).containsEntry("q-dup", Outcome.RESOLVED);
        assertThat(ingest.seen).hasSize(1);
        assertThat(ingest.seen.get(0).ref().recordIndex()).isEqualTo(1);
        assertThat(ingest.seen.get(0).ref().uri()).isEqualTo("scripted://b/shard-1");
    }

    @Test
    void rowsOfOneStreamWhoseWindowsTouchShareOneFetch() {
        transport.add("", Event.at(EVENT, "e-1"), Event.at(EVENT.plusSeconds(90), "e-2"),
                Event.at(EVENT.plus(Duration.ofHours(3)), "e-3"));

        Map<String, Outcome> out = replayer().replay(List.of(row("e-1", EVENT, true, 0),
                row("e-2", EVENT.plusSeconds(90), true, 1), row("e-3", EVENT.plus(Duration.ofHours(3)), true, 2)), false);

        assertThat(out.values()).containsOnly(Outcome.RESOLVED);
        assertThat(transport.calls).as("one fetch for the two close rows and one for the far one").hasSize(2);
    }

    @Test
    void mergedWindowsNeverExceedTheConfiguredMaximum() {
        BackfillProperties d = BackfillProperties.defaults();
        props = new BackfillProperties(true, d.maxWindow(), d.sliceTime(), d.sliceObject(), 5, 2, d.pollInterval(), 5,
                d.retryBackoff(), true, Duration.ofMinutes(5), d.replayFallbackLookback(), Duration.ofMinutes(20));
        transport.add("", Event.at(EVENT, "e-1"), Event.at(EVENT.plusSeconds(500), "e-2"),
                Event.at(EVENT.plusSeconds(1000), "e-3"));

        replayer().replay(List.of(row("e-1", EVENT, true, 0), row("e-2", EVENT.plusSeconds(500), true, 1),
                row("e-3", EVENT.plusSeconds(1000), true, 2)), false);

        assertThat(transport.calls).as("the three windows would span 26 minutes; the cap is 20").hasSize(2);
        assertThat(transport.calls).allSatisfy(c -> assertThat(Duration.between(c.window().from(), c.window().to()))
                .isLessThanOrEqualTo(Duration.ofMinutes(20)));
    }

    @Test
    void aRowWithoutAnEventTimeSearchesTheFallbackLookbackInSlicesAndStopsWhenFound() {
        Instant quarantinedAt = NOW.minus(Duration.ofDays(1));
        transport.add("", Event.at(quarantinedAt.minus(Duration.ofHours(20)), "e-1"));

        Map<String, Outcome> out = replayer().replay(List.of(row("e-1", null, true, 0)), false);

        assertThat(out).containsEntry("q-e-1", Outcome.RESOLVED);
        assertThat(transport.calls).allSatisfy(c -> {
            assertThat(c.window().from()).isAfterOrEqualTo(quarantinedAt.minus(Duration.ofHours(24)));
            assertThat(Duration.between(c.window().from(), c.window().to())).isLessThanOrEqualTo(Duration.ofHours(1));
        });
        assertThat(transport.calls.size()).as("found in the fifth hour, so the later hours are not read").isEqualTo(5);
    }

    @Test
    void aRowWithoutAnEventTimeThatIsNowhereIsNotFoundAfterTheWholeLookback() {
        Map<String, Outcome> out = replayer().replay(List.of(row("e-1", null, true, 0)), false);

        assertThat(out).containsEntry("q-e-1", Outcome.NOT_FOUND);
        assertThat(transport.calls.size()).isBetween(24, 25);
    }

    @Test
    void everyPartitionIsSearchedUntilTheRecordIsFound() {
        transport = new ScriptedTransport(TransportDescriptor.CursorKind.TIME, 5, "east", "west");
        transport.add("west", Event.at(EVENT, "e-1"));

        Map<String, Outcome> out = replayer().replay(List.of(row("e-1", EVENT, true, 0)), false);

        assertThat(out).containsEntry("q-e-1", Outcome.RESOLVED);
        assertThat(transport.calls).extracting(c -> c.partition()).containsExactly("east", "west");
    }

    @Test
    void aDryRunClassifiesWithoutReadingAnything() {
        Map<String, Outcome> out = replayer().replay(List.of(row("e-1", EVENT, true, 0)), true);

        assertThat(out).containsEntry("q-e-1", Outcome.WOULD_RE_READ);
        assertThat(transport.calls).isEmpty();
        assertThat(ingest.calls).isZero();
    }

    @Test
    void historyIsOnlyReadFromStreamsThatAllowBackfill() {
        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(false, false, null));

        assertThat(replayer().replay(List.of(row("e-1", EVENT, true, 0)), false))
                .containsEntry("q-e-1", Outcome.NOT_ALLOWED);
        assertThat(replayer().replay(List.of(row("e-1", EVENT, true, 0)), true))
                .containsEntry("q-e-1", Outcome.NOT_ALLOWED);
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void rowsThatCannotBeLocatedAreNotEligible() {
        QuarantineRow ok = row("e-1", EVENT, true, 0);
        QuarantineRow otherBinding = new QuarantineRow("q-x", "unknown-binding", "AWS", KIND, ENVELOPE, "", "", "", "R",
                "", ok.rawRef(), ok.rawSha256(), ok.rawSizeBytes(), "", true, EVENT, "", "", NOW, null, 0);
        QuarantineRow otherTransport = withRef(ok, "scripted://b", 0, "REST", "q-t");
        QuarantineRow noRef = new QuarantineRow("q-n", BINDING, "AWS", KIND, ENVELOPE, "", "", "", "R", "", Map.of(),
                ok.rawSha256(), ok.rawSizeBytes(), "", true, EVENT, "", "", NOW, null, 0);
        QuarantineRow otherEnvelope = new QuarantineRow("q-e", BINDING, "AWS", KIND, "another-envelope", "", "", "",
                "R", "", ok.rawRef(), ok.rawSha256(), ok.rawSizeBytes(), "", true, EVENT, "", "", NOW, null, 0);

        Map<String, Outcome> out = replayer().replay(List.of(otherBinding, otherTransport, noRef, otherEnvelope),
                false);

        assertThat(out.values()).containsOnly(Outcome.NOT_ELIGIBLE);
        assertThat(out).hasSize(4);
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void aPushChannelADisabledBindingAndTheSwitchMakeRowsNotEligible() {
        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(true, true, null));
        assertThat(replayer().replay(List.of(row("e-1", EVENT, true, 0)), false))
                .containsEntry("q-e-1", Outcome.NOT_ELIGIBLE);

        sources = BackfillTestKit.sources(false, BackfillTestKit.stream(true, false, null));
        assertThat(replayer().replay(List.of(row("e-1", EVENT, true, 0)), false))
                .containsEntry("q-e-1", Outcome.NOT_ELIGIBLE);

        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(true, false, null));
        BackfillProperties d = BackfillProperties.defaults();
        props = new BackfillProperties(true, d.maxWindow(), d.sliceTime(), d.sliceObject(), 5, 2, d.pollInterval(), 5,
                d.retryBackoff(), false, d.replayTimePad(), d.replayFallbackLookback(), d.replayMaxWindow());
        assertThat(replayer().replay(List.of(row("e-1", EVENT, true, 0)), false))
                .containsEntry("q-e-1", Outcome.NOT_ELIGIBLE);
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void aTransportFailureFailsTheRowsOfThatWindowOnlyAndTheMessageIsNotLogged() {
        ScriptedTransport broken = new ScriptedTransport(TransportDescriptor.CursorKind.TIME, 5) {
            @Override
            public com.dlap.logingest.transport.RawBatchIterator fetch(com.dlap.logingest.transport.StreamContext ctx,
                    String partition, com.dlap.logingest.transport.CursorPosition cursor,
                    com.dlap.logingest.transport.FetchWindow window) {
                throw new IllegalStateException("token=secret-value");
            }
        };
        transport = broken;

        Map<String, Outcome> out = replayer().replay(List.of(row("e-1", EVENT, true, 0)), false);

        assertThat(out).containsEntry("q-e-1", Outcome.FAILED);
    }

    @Test
    void anIngestFailureFailsThatRowAndTheOthersStillRun() {
        transport.add("", Event.at(EVENT, "e-1"), Event.at(EVENT.plusSeconds(10), "e-2"));
        ingest.beforeBatch = () -> {
            if (ingest.calls == 1) {
                throw new IllegalStateException("database down");
            }
        };

        Map<String, Outcome> out = replayer().replay(List.of(row("e-1", EVENT, true, 0),
                row("e-2", EVENT.plusSeconds(10), true, 1)), false);

        assertThat(out.values()).containsExactlyInAnyOrder(Outcome.FAILED, Outcome.RESOLVED);
    }

    @Test
    void aRecordDatedAfterNowCannotBeInTheWindowAndIsNotFound() {
        Map<String, Outcome> out = replayer().replay(List.of(row("e-1", NOW.plus(Duration.ofDays(2)), true, 0)), false);

        assertThat(out).containsEntry("q-e-1", Outcome.NOT_FOUND);
        assertThat(transport.calls).isEmpty();
    }

    private QuarantineRow withRef(QuarantineRow r, String uri, long index) {
        return withRef(r, uri, index, TRANSPORT, r.quarantineId());
    }

    private QuarantineRow withRef(QuarantineRow r, String uri, long index, String transportId, String id) {
        Map<String, Object> ref = Map.of("transport", transportId, "uri", uri, "recordIndex", index, "cursor", "");
        return new QuarantineRow(id, r.bindingId(), r.sourceProvider(), r.sourceKind(), r.envelope(),
                r.detectedVersion(), r.pinnedVersion(), r.parserVersion(), r.reason(), r.reasonDetail(), ref,
                r.rawSha256(), r.rawSizeBytes(), r.rawExcerpt(), r.rawTruncated(), r.eventTime(), r.nativeEventId(),
                r.passThroughEventId(), r.quarantinedAt(), r.replayedAt(), r.replayCount());
    }
}
