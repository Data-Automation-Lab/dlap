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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.logingest.LogHealthIntegrationBase;
import com.dlap.logingest.backfill.BackfillRunner.RunResult;
import com.dlap.logingest.backfill.BackfillTestKit.Event;
import com.dlap.logingest.backfill.BackfillTestKit.Killed;
import com.dlap.logingest.backfill.BackfillTestKit.ScriptedTransport;
import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.ValidationReport;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.PersistRaw;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.logingest.quarantine.QuarantineFixtures;
import com.dlap.logingest.quarantine.QuarantineRecordRepository;
import com.dlap.logingest.quarantine.QuarantineReplayService;
import com.dlap.logingest.quarantine.QuarantineReplayService.ReplayRequest;
import com.dlap.logingest.quarantine.QuarantineReplayService.ReplayResult;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.LogTransport;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import com.dlap.logingest.transport.lock.AdvisoryLocks;
import com.dlap.logingest.transport.lock.PgAdvisoryLocks;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.RawRecordRef;
import com.dlap.proto.log.v1.SourceProvider;
import com.google.protobuf.Timestamp;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * LOG-04 acceptance against PostgreSQL and the real registry pipeline (Testcontainers; needs Docker):
 * ADMIN only, one job per binding, {@code origin=BACKFILL}, a kill-and-resume run that leaves exactly one
 * stored and one published event per source event, and the quarantine replay that re-reads truncated and
 * masked records from their source instead of storing raw data (decision D26, Q-362). Kafka is mocked.
 */
@Import(BackfillPipelineIntegrationTest.Beans.class)
class BackfillPipelineIntegrationTest extends LogHealthIntegrationBase {

    private static final String BINDING = "it-backfill";
    private static final String STREAM = "it-stream";
    private static final Instant BASE = Instant.now().minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.HOURS);
    private static final String API = "/api/v1/logingest/backfills";

    /** Serves the scripted events as normalized DTO records, in backfill mode only. */
    static final class IntegrationTransport extends ScriptedTransport {
        volatile int killAtBatch;
        final AtomicInteger served = new AtomicInteger();

        IntegrationTransport() {
            super(TransportDescriptor.CursorKind.TIME, 5);
        }

        @Override
        public RawBatchIterator fetch(StreamContext ctx, String partition, CursorPosition cursor, FetchWindow window) {
            if (ctx.mode() != IngestMode.BACKFILL) {
                // The stream poller also runs this binding; it must not read the scripted history.
                return RawBatchIterator.of(List.of());
            }
            RawBatchIterator inner = super.fetch(ctx, partition, cursor, window);
            return new RawBatchIterator() {
                @Override
                public boolean hasNext() {
                    return inner.hasNext();
                }

                @Override
                public com.dlap.logingest.transport.RawBatch next() {
                    if (killAtBatch > 0 && served.incrementAndGet() == killAtBatch) {
                        throw new Killed();
                    }
                    return inner.next();
                }
            };
        }

        void reset() {
            events.clear();
            calls.clear();
            killAtBatch = 0;
            served.set(0);
            body = BackfillPipelineIntegrationTest::dto;
        }
    }

    private static final IntegrationTransport TRANSPORT = new IntegrationTransport();

    /** The binding under test and the transport it reads. */
    @TestConfiguration
    static class Beans {

        @Bean
        LogTransport integrationTransport() {
            return TRANSPORT;
        }

        @Bean
        @Primary
        EffectiveLogSources integrationSources() {
            List<EffectiveBinding> all = new ArrayList<>(BuiltinBindings.all());
            String kind = DlapNormalizedFormat.sourceKind(SourceProvider.AWS);
            BindingConfig parse = new BindingConfig(BINDING, kind, Optional.empty(), OnMismatch.DEFAULT, Map.of(),
                    List.of(), PersistRaw.of(PersistRaw.Policy.ALL), BindingConfig.DEFAULT_MAX_RECORD_BYTES,
                    AssetIdHints.of(AssetProvider.AWS));
            EffectiveStream stream = new EffectiveStream(BINDING, STREAM, BackfillTestKit.TRANSPORT,
                    DlapNormalizedFormat.ENVELOPE, Map.of("bucket", "b"), parse, StreamFilters.NONE, Duration.ZERO,
                    Duration.ofHours(1), null, null, true, false, false);
            all.add(EffectiveBinding.of(BINDING, "it-source", kind, Optional.of(SourceProvider.AWS), ConfigOrigin.FILE,
                    true, true, Map.of(), List.of(stream)));
            return new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", all, ValidationReport.CLEAN);
        }
    }

    @DynamicPropertySource
    static void backfill(DynamicPropertyRegistry r) {
        // The tests tick the scheduler themselves.
        r.add("dlap.logingest.backfill.poll-interval", () -> "PT1H");
    }

    @Autowired
    private BackfillService service;

    @Autowired
    private BackfillRunner runner;

    @Autowired
    private BackfillScheduler scheduler;

    @Autowired
    private PgAdvisoryLocks locks;

    @Autowired
    private QuarantineReplayService replay;

    @Autowired
    private QuarantineRecordRepository quarantine;

    private final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger publishAttempts = new AtomicInteger();
    private volatile int killPublishAt;

    static String dto(String id) {
        return dto(id, "arn:aws:iam::111111111111:user/u");
    }

    static String dto(String id, String principal) {
        return "{\"sourceProvider\":\"AWS\",\"sourceService\":\"s3.amazonaws.com\",\"operation\":\"GetObject\","
                + "\"changeType\":\"ACCESS\",\"resultStatus\":\"SUCCESS\",\"eventTime\":\"2026-07-05T12:00:00Z\","
                + "\"principalId\":\"" + principal + "\",\"targetResourceId\":\"s3://dlap-it-logs/" + id
                + "\",\"requestId\":\"" + id + "\"}";
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_logingest.backfill_job");
        jdbc.update("DELETE FROM dlap_logingest.processed_object WHERE binding_id = ?", BINDING);
        jdbc.update("DELETE FROM dlap_logingest.stream_cursor WHERE binding_id = ?", BINDING);
        TRANSPORT.reset();
        delivered.clear();
        publishAttempts.set(0);
        killPublishAt = 0;
        doAnswer(inv -> {
            if (killPublishAt > 0 && publishAttempts.incrementAndGet() == killPublishAt) {
                throw new Killed();
            }
            Map<String, Object> wire = inv.getArgument(1);
            delivered.add(String.valueOf(wire.get("eventId")));
            return null;
        }).when(publisher).publish(anyString(), anyMap());
    }

    /** Scripts {@code count} events, 7.5 minutes apart from {@link #BASE}. */
    private List<String> script(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "bf-" + String.format("%03d", i);
            TRANSPORT.add("", Event.at(BASE.plus(Duration.ofSeconds(450L * i)), id));
            ids.add(id);
        }
        return ids;
    }

    private BackfillJob job(UUID id) {
        return service.get(id);
    }

    private long storedBy(String origin) {
        return jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE binding_id = ? AND origin = ?",
                Long.class, BINDING, origin);
    }

    @Test
    @WithMockUser(username = "ops-admin", roles = "ADMIN")
    void anAdminBackfillsHistoryOverHttpAndEveryEventCarriesOriginBackfill() throws Exception {
        script(30);
        String body = "{\"bindingId\":\"" + BINDING + "\",\"from\":\"" + BASE + "\",\"to\":\""
                + BASE.plus(Duration.ofHours(4)) + "\"}";

        MvcResult created = mvc.perform(post(API).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(post(API).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        assertThat(scheduler.tick()).isEqualTo(1);
        String state = "";
        for (int i = 0; i < 600 && !"SUCCEEDED".equals(state); i++) {
            Thread.sleep(100);
            state = JsonPath.read(mvc.perform(get(API + "/" + id)).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(), "$.status");
        }

        assertThat(state).isEqualTo("SUCCEEDED");
        String json = mvc.perform(get(API + "/" + id)).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(json, "$.progress.records")).isEqualTo(30);
        assertThat((Double) JsonPath.read(json, "$.fractionComplete")).isEqualTo(1.0);
        assertThat(storedBy("ORIGIN_BACKFILL")).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE binding_id = ?",
                Long.class, BINDING)).as("nothing else was stored").isEqualTo(30);
        assertThat(delivered).hasSize(30).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.stream_cursor WHERE binding_id = ?",
                Long.class, BINDING)).as("a backfill does not move the stream's own cursor").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.processed_object WHERE binding_id = ? "
                + "AND stream_id = ?", Long.class, BINDING, STREAM)).isZero();
        mvc.perform(post(API).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = {"DATA_ENGINEER", "CDO", "AUDITOR"})
    void aNonAdminCannotStartListOrCancelJobs() throws Exception {
        String body = "{\"bindingId\":\"" + BINDING + "\",\"from\":\"" + BASE + "\",\"to\":\"" + BASE.plusSeconds(60)
                + "\"}";

        mvc.perform(post(API).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get(API)).andExpect(status().isForbidden());
        mvc.perform(post(API + "/" + UUID.randomUUID() + "/cancel").with(csrf())).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.backfill_job", Long.class)).isZero();
    }

    @Test
    void aJobKilledBetweenBatchesAndDuringPublishResumesWithNoGapsAndNoDuplicates() {
        List<String> ids = script(60);
        UUID id = service.create(new BackfillService.CreateRequest(BINDING, null, BASE, BASE.plus(Duration.ofHours(8))),
                "ops-admin").id();

        TRANSPORT.killAtBatch = 4;
        assertThatThrownBy(() -> runner.run(id, () -> false)).isInstanceOf(Killed.class);
        BackfillJob afterFirstKill = job(id);
        assertThat(afterFirstKill.status()).isEqualTo(BackfillStatus.RUNNING);
        assertThat(afterFirstKill.progress().batches()).isEqualTo(3);
        long storedAtFirstKill = jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE binding_id "
                + "= ?", Long.class, BINDING);
        assertThat(storedAtFirstKill).isPositive().isLessThan(60);

        // Second kill: the process dies while publishing the third event of the batch it resumed with.
        TRANSPORT.killAtBatch = 0;
        killPublishAt = 3;
        assertThatThrownBy(() -> runner.run(id, () -> false)).isInstanceOf(Killed.class);
        assertThat(job(id).status()).isEqualTo(BackfillStatus.RUNNING);

        killPublishAt = 0;
        assertThat(runner.run(id, () -> false)).isEqualTo(RunResult.COMPLETED);

        BackfillJob done = job(id);
        assertThat(done.status()).isEqualTo(BackfillStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE binding_id = ?",
                Long.class, BINDING)).as("no gaps").isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT event_id) FROM dlap_logingest.log_events WHERE "
                + "binding_id = ?", Long.class, BINDING)).as("no duplicates in the store").isEqualTo(60);
        assertThat(storedBy("ORIGIN_BACKFILL")).isEqualTo(60);
        assertThat(delivered).as("each event reached the topic exactly once").hasSize(60).doesNotHaveDuplicates();
        assertThat(new HashSet<>(delivered)).isEqualTo(new HashSet<>(ids.stream()
                .map(i -> LogEventIds.eventId(SourceProvider.AWS, i)).toList()));
        assertThat(done.progress().duplicates()).as("the re-read batches were absorbed by the store").isPositive();
        assertThat(jdbc.queryForObject("SELECT progress ->> 'records' FROM dlap_logingest.backfill_job WHERE id = ?",
                String.class, id)).isNotNull();
    }

    @Test
    void aFinishedJobIsNotRunAgainAndItsEventsAreStoredOnce() {
        script(3);
        UUID id = service.create(new BackfillService.CreateRequest(BINDING, STREAM, BASE, BASE.plus(Duration.ofHours(1))),
                "ops-admin").id();

        assertThat(runner.run(id, () -> false)).isEqualTo(RunResult.COMPLETED);
        assertThat(runner.run(id, () -> false)).isEqualTo(RunResult.NOT_RUNNABLE);
        assertThat(storedBy("ORIGIN_BACKFILL")).isEqualTo(3);
    }

    @Test
    void aCancelledJobStopsAndStaysCancelled() {
        script(40);
        UUID id = service.create(new BackfillService.CreateRequest(BINDING, null, BASE, BASE.plus(Duration.ofHours(8))),
                "ops-admin").id();
        TRANSPORT.killAtBatch = 0;
        killPublishAt = 0;
        doAnswer(inv -> {
            Map<String, Object> wire = inv.getArgument(1);
            delivered.add(String.valueOf(wire.get("eventId")));
            if (delivered.size() == 6) {
                service.cancel(id, "ops-admin");
            }
            return null;
        }).when(publisher).publish(anyString(), anyMap());

        RunResult r = runner.run(id, () -> false);

        assertThat(r).isEqualTo(RunResult.CANCELLED);
        assertThat(job(id).status()).isEqualTo(BackfillStatus.CANCELLED);
        assertThat(delivered.size()).as("the batch in flight finished, nothing after it").isLessThan(40);
        int after = delivered.size();
        assertThat(runner.run(id, () -> false)).isEqualTo(RunResult.NOT_RUNNABLE);
        assertThat(delivered).hasSize(after);
    }

    @Test
    void aJobWhoseLockIsHeldByAnotherSessionIsSkippedUntilItIsReleased() {
        script(3);
        UUID id = service.create(new BackfillService.CreateRequest(BINDING, null, BASE, BASE.plus(Duration.ofHours(1))),
                "ops-admin").id();
        Optional<AdvisoryLocks.Lease> held = locks.tryLockStream(BackfillRunner.LOCK_SCOPE, id.toString());
        assertThat(held).isPresent();
        try {
            assertThat(runner.run(id, () -> false)).isEqualTo(RunResult.SKIPPED);
            assertThat(job(id).status()).isEqualTo(BackfillStatus.PENDING);
            assertThat(storedBy("ORIGIN_BACKFILL")).isZero();
        } finally {
            held.get().close();
        }

        assertThat(runner.run(id, () -> false)).isEqualTo(RunResult.COMPLETED);
        assertThat(storedBy("ORIGIN_BACKFILL")).isEqualTo(3);
    }

    // ---- decision D26, Q-362: replay re-reads raw_ref through the bounded backfill ----

    private LogQuarantineRecord quarantined(String id, String fullText, String excerpt, boolean truncated, long index,
                                            Instant eventTime) {
        RawRecordRef ref = RawRecordRef.newBuilder().setTransport(BackfillTestKit.TRANSPORT).setUri("scripted://b")
                .setRecordIndex(index).setCursor("tok-" + id).build();
        LogQuarantineRecord base = QuarantineFixtures.exact(BINDING, fullText,
                QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION, index, BASE);
        return base.toBuilder()
                .setQuarantineId(LogEventIds.quarantineId(BINDING, ref, base.getRawSha256()))
                .setRawRef(ref)
                .setRawExcerpt(excerpt)
                .setRawTruncated(truncated)
                .setEventTime(Timestamp.newBuilder().setSeconds(eventTime.getEpochSecond()))
                .build();
    }

    @Test
    void truncatedAndMaskedQuarantineRowsAreReplayedFromTheirSourceAndNothingRawIsStored() {
        String email = "bob@example.com";
        String full = dto("rp-trunc");
        String withEmail = dto("rp-mask", email);
        Instant at = BASE.plus(Duration.ofMinutes(30));
        TRANSPORT.body = id -> "rp-mask".equals(id) ? withEmail : dto(id);
        TRANSPORT.add("", Event.at(at, "rp-trunc"), Event.at(at.plusSeconds(20), "rp-mask"),
                Event.at(at.plusSeconds(40), "unrelated"));
        LogQuarantineRecord truncated = quarantined("rp-trunc", full, full.substring(0, 40), true, 3, at);
        LogQuarantineRecord masked = quarantined("rp-mask", withEmail, withEmail.replace(email, "<email>"), false, 4,
                at.plusSeconds(20));
        quarantine.upsert(truncated, BASE);
        quarantine.upsert(masked, BASE);
        int excerptBefore = jdbc.queryForObject("SELECT length(raw_excerpt) FROM dlap_logingest.quarantine_record "
                + "WHERE quarantine_id = ?", Integer.class, truncated.getQuarantineId());

        ReplayResult dry = replay.replay(new ReplayRequest(null, null, null, null, null, true), "ops-admin");
        assertThat(dry.fromSource().planned()).isEqualTo(2);
        assertThat(dry.notReplayableTruncated() + dry.notReplayableAltered()).isZero();
        assertThat(storedBy("ORIGIN_REPLAY")).isZero();

        ReplayResult r = replay.replay(new ReplayRequest(null, null, null, null, null, false), "ops-admin");

        assertThat(r.selected()).isEqualTo(2);
        assertThat(r.resolved()).isEqualTo(2);
        assertThat(r.fromSource().resolved()).isEqualTo(2);
        assertThat(r.notReplayableTruncated()).isZero();
        assertThat(r.notReplayableAltered()).isZero();
        assertThat(r.resolvedIds()).containsExactlyInAnyOrder(truncated.getQuarantineId(), masked.getQuarantineId());
        assertThat(jdbc.queryForObject("SELECT principal_id FROM dlap_logingest.log_events WHERE native_event_id = "
                + "'rp-mask'", String.class)).as("the real value, not the masked one").isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT origin FROM dlap_logingest.log_events WHERE native_event_id = "
                + "'rp-trunc'", String.class)).isEqualTo("ORIGIN_REPLAY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE native_event_id = "
                + "'unrelated'", Long.class)).as("only the quarantined records are ingested").isZero();
        assertThat(quarantine.findById(truncated.getQuarantineId()).orElseThrow().replayedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT length(raw_excerpt) FROM dlap_logingest.quarantine_record WHERE "
                + "quarantine_id = ?", Integer.class, truncated.getQuarantineId()))
                .as("no raw provider data was stored to make this possible").isEqualTo(excerptBefore);
        assertThat(delivered).hasSize(2);

        ReplayResult again = replay.replay(new ReplayRequest(null, null, null, null, null, false), "ops-admin");
        assertThat(again.selected()).as("idempotent").isZero();
    }

    @Test
    void aRecordTheSourceNoLongerHoldsStaysPendingAndIsCounted() {
        String full = dto("rp-gone");
        Instant at = BASE.plus(Duration.ofMinutes(30));
        LogQuarantineRecord q = quarantined("rp-gone", full, full.substring(0, 30), true, 1, at);
        quarantine.upsert(q, BASE);

        ReplayResult r = replay.replay(new ReplayRequest(null, null, null, null, null, false), "ops-admin");

        assertThat(r.fromSource().notFound()).isEqualTo(1);
        assertThat(r.resolved()).isZero();
        QuarantineRecordRepository.Filter pending = new QuarantineRecordRepository.Filter(null, null, null, false,
                null, null, null);
        assertThat(quarantine.count(pending)).isEqualTo(1);
        assertThat(quarantine.findById(q.getQuarantineId()).orElseThrow().replayCount()).isEqualTo(1);
    }
}
