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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.common.logevent.LogEventIds;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.metrics.LogHealthFixtures;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.quarantine.QuarantineReplayService.ReplayRequest;
import com.dlap.logingest.quarantine.QuarantineReplayService.ReplayResult;
import com.dlap.logingest.quarantine.SourceReplayHook.Outcome;
import com.dlap.logingest.service.registry.BatchReport;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.lock.PgAdvisoryLocks;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Decision D26 (Q-362) at the replay service: rows the table cannot replay go to the source hook, each
 * outcome is booked once, and the old behaviour is unchanged without the hook or with
 * {@code fromSource=false}.
 */
class QuarantineReplaySourceTest {

    private final QuarantineRecordRepository repository = mock(QuarantineRecordRepository.class);
    private final EffectiveLogSources sources = mock(EffectiveLogSources.class);
    private final PgAdvisoryLocks locks = mock(PgAdvisoryLocks.class);
    private final RegistryIngestService ingest = mock(RegistryIngestService.class);
    private final MutableClock clock = new MutableClock(LogHealthFixtures.T0);
    private final Map<String, Outcome> outcomes = new HashMap<>();
    private final List<List<QuarantineRow>> hookCalls = new ArrayList<>();
    private final List<Boolean> hookDryRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(locks.tryLock(anyInt(), anyInt())).thenReturn(Optional.of(() -> {
        }));
        when(sources.runningStream(any(), any())).thenReturn(Optional.of(mock(EffectiveStream.class)));
        when(ingest.ingestBatch(anyList(), anyBoolean())).thenReturn(new BatchReport(List.of(
                new RecordReport("PARSED", List.of(), Optional.empty()))));
    }

    private QuarantineReplayService service(boolean withHook) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ingest", ingest);
        StaticListableBeanFactory hooks = new StaticListableBeanFactory();
        if (withHook) {
            hooks.addBean("hook", (SourceReplayHook) (rows, dryRun) -> {
                hookCalls.add(rows);
                hookDryRuns.add(dryRun);
                Map<String, Outcome> out = new HashMap<>();
                rows.forEach(r -> out.put(r.quarantineId(), outcomes.getOrDefault(r.quarantineId(),
                        Outcome.NOT_ELIGIBLE)));
                return out;
            });
        }
        return new QuarantineReplayService(repository, beans.getBeanProvider(RegistryIngestService.class), sources,
                locks, QuarantineProperties.defaults(), hooks.getBeanProvider(SourceReplayHook.class),
                new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(java.time.Clock.class));
    }

    private static QuarantineRow exact(String id) {
        String text = "{\"id\":\"" + id + "\"}";
        return row(id, text, LogEventIds.sha256Hex(text), text.length(), false);
    }

    private static QuarantineRow truncated(String id) {
        String text = "{\"id\":\"" + id + "\"}";
        return row(id, text.substring(0, 5), LogEventIds.sha256Hex(text), text.getBytes(StandardCharsets.UTF_8).length,
                true);
    }

    private static QuarantineRow masked(String id) {
        String text = "{\"id\":\"" + id + "\"}";
        return row(id, text.replace(id, "<email>"), LogEventIds.sha256Hex(text), text.length(), false);
    }

    private static QuarantineRow row(String id, String excerpt, String sha, long size, boolean truncated) {
        return new QuarantineRow("q-" + id, "b1", "AWS", "K", "env", "", "", "", "INVALID_JSON", "",
                Map.of("transport", "T", "uri", "u", "recordIndex", 0, "cursor", ""), sha, size, excerpt, truncated,
                null, "", "", LogHealthFixtures.T0, null, 0);
    }

    @Test
    void onlyTheRowsTheTableCannotReplayGoToTheSource() {
        when(repository.pending(any(), anyInt())).thenReturn(List.of(exact("a"), truncated("b"), masked("c")));
        outcomes.put("q-b", Outcome.RESOLVED);
        outcomes.put("q-c", Outcome.STILL_QUARANTINED);

        ReplayResult r = service(true).replay(new ReplayRequest(null, null, null, null, null, false), "admin");

        assertThat(hookCalls).hasSize(1);
        assertThat(hookCalls.get(0)).extracting(QuarantineRow::quarantineId).containsExactly("q-b", "q-c");
        assertThat(hookDryRuns).containsExactly(false);
        assertThat(r.selected()).isEqualTo(3);
        assertThat(r.replayable()).as("the table-replayable row, as before").isEqualTo(1);
        assertThat(r.resolved()).as("row a from the table, row b from the source").isEqualTo(2);
        assertThat(r.stillQuarantined()).isEqualTo(1);
        assertThat(r.resolvedIds()).containsExactly("q-a", "q-b");
        assertThat(r.notReplayableTruncated()).isZero();
        assertThat(r.notReplayableAltered()).isZero();
        assertThat(r.fromSource()).isEqualTo(new QuarantineReplayService.SourceCounts(0, 1, 1, 0, 0, 0, 0));
        verify(repository).markReplayAttempt(eq("q-a"), eq(true), any());
        verify(repository).markReplayAttempt(eq("q-b"), eq(true), any());
        verify(repository).markReplayAttempt(eq("q-c"), eq(false), any());
    }

    @Test
    void everyOutcomeIsCountedOnceAndBooked() {
        when(repository.pending(any(), anyInt())).thenReturn(List.of(truncated("nf"), truncated("ch"),
                truncated("fl"), truncated("na"), truncated("ne"), masked("ne2")));
        outcomes.put("q-nf", Outcome.NOT_FOUND);
        outcomes.put("q-ch", Outcome.CHANGED);
        outcomes.put("q-fl", Outcome.FAILED);
        outcomes.put("q-na", Outcome.NOT_ALLOWED);
        outcomes.put("q-ne", Outcome.NOT_ELIGIBLE);

        ReplayResult r = service(true).replay(new ReplayRequest(null, null, null, null, null, false), "admin");

        assertThat(r.fromSource()).isEqualTo(new QuarantineReplayService.SourceCounts(0, 0, 0, 1, 1, 1, 1));
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.notReplayableTruncated()).as("not allowed and not eligible stay counted as before").isEqualTo(2);
        assertThat(r.notReplayableAltered()).isEqualTo(1);
        assertThat(r.resolved()).isZero();
        verify(repository).markReplayAttempt(eq("q-nf"), eq(false), any());
        verify(repository).markReplayAttempt(eq("q-ch"), eq(false), any());
        verify(repository, never()).markReplayAttempt(eq("q-fl"), anyBoolean(), any());
        verify(repository, never()).markReplayAttempt(eq("q-na"), anyBoolean(), any());
        verify(repository, never()).markReplayAttempt(eq("q-ne"), anyBoolean(), any());
        verify(repository, times(2)).markReplayAttempt(any(), anyBoolean(), any());
    }

    @Test
    void aDryRunPlansTheSourceReadsWithoutBookingAnything() {
        when(repository.pending(any(), anyInt())).thenReturn(List.of(truncated("b"), masked("c")));
        outcomes.put("q-b", Outcome.WOULD_RE_READ);
        outcomes.put("q-c", Outcome.NOT_ALLOWED);

        ReplayResult r = service(true).replay(new ReplayRequest(null, null, null, null, null, true), "admin");

        assertThat(hookDryRuns).containsExactly(true);
        assertThat(r.dryRun()).isTrue();
        assertThat(r.fromSource().planned()).isEqualTo(1);
        assertThat(r.fromSource().notAllowed()).isEqualTo(1);
        assertThat(r.notReplayableAltered()).isEqualTo(1);
        verify(repository, never()).markReplayAttempt(any(), anyBoolean(), any());
    }

    @Test
    void withoutTheHookOrWithFromSourceFalseTheOldCountsHold() {
        when(repository.pending(any(), anyInt())).thenReturn(List.of(truncated("b"), masked("c")));
        outcomes.put("q-b", Outcome.RESOLVED);

        ReplayResult noHook = service(false).replay(new ReplayRequest(null, null, null, null, null, false), "admin");
        ReplayResult off = service(true).replay(new ReplayRequest(null, null, null, null, null, false, false),
                "admin");

        for (ReplayResult r : List.of(noHook, off)) {
            assertThat(r.notReplayableTruncated()).isEqualTo(1);
            assertThat(r.notReplayableAltered()).isEqualTo(1);
            assertThat(r.resolved()).isZero();
            assertThat(r.fromSource()).isEqualTo(QuarantineReplayService.SourceCounts.NONE);
        }
        assertThat(hookCalls).as("fromSource=false never calls the hook").isEmpty();
        verify(repository, never()).markReplayAttempt(any(), anyBoolean(), any());
    }

    @Test
    void aHookThatThrowsFailsOnlyTheRowsItWasGivenAndTheTableRowsStayBooked() {
        when(repository.pending(any(), anyInt())).thenReturn(List.of(exact("a"), truncated("b"), masked("c")));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ingest", ingest);
        StaticListableBeanFactory hooks = new StaticListableBeanFactory();
        hooks.addBean("hook", (SourceReplayHook) (rows, dryRun) -> {
            throw new IllegalStateException("token=secret-value");
        });
        QuarantineReplayService s = new QuarantineReplayService(repository,
                beans.getBeanProvider(RegistryIngestService.class), sources, locks, QuarantineProperties.defaults(),
                hooks.getBeanProvider(SourceReplayHook.class),
                new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(java.time.Clock.class));

        ReplayResult r = s.replay(new ReplayRequest(null, null, null, null, null, false), "admin");

        assertThat(r.resolved()).as("row a was replayed from the table").isEqualTo(1);
        assertThat(r.failed()).isEqualTo(2);
        assertThat(r.fromSource().failed()).isEqualTo(2);
        verify(repository).markReplayAttempt(eq("q-a"), eq(true), any());
        verify(repository, times(1)).markReplayAttempt(any(), anyBoolean(), any());
    }

    @Test
    void theHookIsNotCalledWhenNoRowNeedsIt() {
        when(repository.pending(any(), anyInt())).thenReturn(List.of(exact("a")));

        service(true).replay(new ReplayRequest(null, null, null, null, null, false), "admin");

        assertThat(hookCalls).isEmpty();
    }

    @Test
    void theRequestAndResultKeepTheirOldConstructors() {
        ReplayRequest old = new ReplayRequest("b", "r", "v", List.of("q"), 3, true);
        assertThat(old.fromSource()).isNull();
        ReplayResult result = new ReplayResult(true, 1, 2, 3, 4, 5, 6, 7, 8, List.of("x"));
        assertThat(result.fromSource()).isEqualTo(QuarantineReplayService.SourceCounts.NONE);
    }
}
