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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.metrics.LogHealthFixtures;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.quarantine.QuarantineReplayService.ReplayRequest;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.lock.PgAdvisoryLocks;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The parts of the replay that need no database: the legacy pipeline refuses, the limit is capped,
 * a bad request is refused before anything is read, and a stored row becomes the exact record.
 */
class QuarantineReplayServiceTest {

    private final QuarantineRecordRepository repository = mock(QuarantineRecordRepository.class);
    private final EffectiveLogSources sources = mock(EffectiveLogSources.class);
    private final PgAdvisoryLocks locks = mock(PgAdvisoryLocks.class);
    private final MutableClock clock = new MutableClock(LogHealthFixtures.T0);

    private QuarantineReplayService service(boolean registryActive, QuarantineProperties props) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (registryActive) {
            beans.addBean("registryIngestService", mock(RegistryIngestService.class));
        }
        StaticListableBeanFactory clocks = new StaticListableBeanFactory(Map.of("clock", clock));
        return new QuarantineReplayService(repository, beans.getBeanProvider(RegistryIngestService.class), sources,
                locks, props, clocks.getBeanProvider(java.time.Clock.class));
    }

    @Test
    void onTheLegacyPipelineThereIsNothingToReplayIntoAndNothingIsRead() {
        QuarantineReplayService s = service(false, QuarantineProperties.defaults());

        assertThatThrownBy(() -> s.replay(new ReplayRequest(null, null, null, null, null, false), "t"))
                .isInstanceOf(QuarantineReplayService.ReplayUnavailableException.class)
                .hasMessageContaining("pipeline=registry");
        verifyNoInteractions(repository, locks);
    }

    @Test
    void aBadRequestIsRefusedBeforeTheLockOrTheTable() {
        QuarantineReplayService s = service(true, QuarantineProperties.defaults());

        assertThatThrownBy(() -> s.replay(new ReplayRequest(null, "NOT_A_REASON", null, null, null, false), "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.replay(new ReplayRequest(null, null, null, null, 0, false), "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.replay(new ReplayRequest(null, null, null, null, -3, false), "t"))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> tooMany = java.util.stream.IntStream.range(0, 501).mapToObj(i -> "q" + i).toList();
        assertThatThrownBy(() -> s.replay(new ReplayRequest(null, null, null, tooMany, null, false), "t"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 500");
        verifyNoInteractions(repository, locks);
    }

    @Test
    void theLimitDefaultsTo100AndNeverExceedsTheConfiguredBatch() {
        when(locks.tryLock(anyInt(), anyInt())).thenReturn(java.util.Optional.of(() -> {
        }));
        when(repository.pending(any(), anyInt())).thenReturn(List.of());
        QuarantineProperties small = new QuarantineProperties(true, true, 2048, 30, true, 5000, 40);

        service(true, small).replay(new ReplayRequest(null, null, null, null, null, true), "t");
        service(true, small).replay(new ReplayRequest(null, null, null, null, 1000, true), "t");
        service(true, small).replay(new ReplayRequest(null, null, null, null, 7, true), "t");
        service(true, QuarantineProperties.defaults()).replay(new ReplayRequest(null, null, null, null, null, true), "t");

        verify(repository).pending(any(), eq(7));
        verify(repository, org.mockito.Mockito.times(2)).pending(any(), eq(40));
        verify(repository).pending(any(), eq(100));
    }

    @Test
    void aReasonIsNormalizedToTheStoredCodeInTheFilter() {
        when(locks.tryLock(anyInt(), anyInt())).thenReturn(java.util.Optional.of(() -> {
        }));
        when(repository.pending(any(), anyInt())).thenReturn(List.of());

        service(true, QuarantineProperties.defaults()).replay(
                new ReplayRequest("b1", "QUARANTINE_REASON_INVALID_JSON", "2.0", List.of("q1"), 5, true), "t");

        org.mockito.ArgumentCaptor<QuarantineRecordRepository.Filter> f = org.mockito.ArgumentCaptor.forClass(
                QuarantineRecordRepository.Filter.class);
        verify(repository).pending(f.capture(), eq(5));
        assertThat(f.getValue().reason()).isEqualTo("INVALID_JSON");
        assertThat(f.getValue().bindingId()).isEqualTo("b1");
        assertThat(f.getValue().detectedVersion()).isEqualTo("2.0");
        assertThat(f.getValue().ids()).containsExactly("q1");
    }

    @Test
    void aStoredRowBecomesTheExactRecordInReplayMode() {
        String text = LogHealthFixtures.write("rebuild-1");
        QuarantineRow row = new QuarantineRow("q1", "dlap-rest-aws", "AWS", "DLAP_NORMALIZED_AWS", "ingest-dto", "v1",
                "", "p@1", "INVALID_JSON", "d",
                Map.of("transport", "S3_TRAIL_FILES", "uri", "s3://b/AWSLogs/x.json.gz", "recordIndex", 12,
                        "cursor", "tok"),
                com.dlap.common.logevent.LogEventIds.sha256Hex(text), text.length(), text, false, null, "", "",
                LogHealthFixtures.T0, null, 0);

        RawRecord r = service(true, QuarantineProperties.defaults()).toRecord(row);

        assertThat(r.bindingId()).isEqualTo("dlap-rest-aws");
        assertThat(r.sourceKind()).isEqualTo("DLAP_NORMALIZED_AWS");
        assertThat(r.envelope()).isEqualTo("ingest-dto");
        assertThat(r.mode()).isEqualTo(IngestMode.REPLAY);
        assertThat(r.ref()).isEqualTo(new RawRef("S3_TRAIL_FILES", "s3://b/AWSLogs/x.json.gz", 12, "tok"));
        assertThat(r.fetchedAt()).isEqualTo(LogHealthFixtures.T0);
        assertThat(r.sha256()).as("same bytes, same hash, so the same quarantine id if it fails again")
                .isEqualTo(row.rawSha256());
    }

    @Test
    void aRowIsReplayableOnlyWhenItsExcerptIsTheOriginal() {
        String text = "{\"a\":1}";
        String sha = com.dlap.common.logevent.LogEventIds.sha256Hex(text);
        QuarantineRow exact = row(text, sha, text.length(), false);

        assertThat(exact.notReplayableReason()).isNull();
        assertThat(exact.replayable()).isTrue();
        assertThat(row(text, sha, text.length(), true).notReplayableReason())
                .isEqualTo(QuarantineRow.NotReplayable.TRUNCATED);
        assertThat(row("{\"a\":2}", sha, text.length(), false).notReplayableReason())
                .as("same size, other bytes").isEqualTo(QuarantineRow.NotReplayable.ALTERED);
        assertThat(row(text, sha, text.length() + 1, false).notReplayableReason())
                .as("the size of the original differs").isEqualTo(QuarantineRow.NotReplayable.ALTERED);
    }

    private static QuarantineRow row(String excerpt, String sha, long size, boolean truncated) {
        return new QuarantineRow("q", "b", "AWS", "K", "e", "", "", "", "INVALID_JSON", "", Map.of(), sha, size, excerpt,
                truncated, null, "", "", LogHealthFixtures.T0, null, 0);
    }
}
