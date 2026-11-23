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
import static com.dlap.logingest.backfill.BackfillTestKit.STREAM;
import static com.dlap.logingest.backfill.BackfillTestKit.T0;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.backfill.BackfillRunner.RunResult;
import com.dlap.logingest.backfill.BackfillService.CreateRequest;
import com.dlap.logingest.backfill.BackfillTestKit.CountingIngest;
import com.dlap.logingest.backfill.BackfillTestKit.Event;
import com.dlap.logingest.backfill.BackfillTestKit.FakeLocks;
import com.dlap.logingest.backfill.BackfillTestKit.FakePacing;
import com.dlap.logingest.backfill.BackfillTestKit.InMemoryJobs;
import com.dlap.logingest.backfill.BackfillTestKit.RecordingObjects;
import com.dlap.logingest.backfill.BackfillTestKit.ScriptedTransport;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.logingest.transport.TransportDescriptor;
import com.dlap.logingest.transport.TransportRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Q-558 acceptance: a backfill job exposes records read, records quarantined, objects failed, and job
 * duration and status as Micrometer meters. The runner, the ingest path and the job table are the
 * in-memory doubles of {@link BackfillTestKit}; the meter registry is real, so the numbers asserted
 * are the numbers a scrape would show.
 */
class BackfillMetricsTest {

    private static final Instant NOW = T0.plus(Duration.ofDays(30));
    private static final Instant FROM = T0;
    private static final Instant TO = T0.plus(Duration.ofHours(6));

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final BackfillMetrics metrics = new BackfillMetrics(registry, b -> b);
    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryJobs jobs = new InMemoryJobs();
    private final CountingIngest ingest = new CountingIngest();
    private final FakeLocks locks = new FakeLocks();
    private final RecordingObjects objects = new RecordingObjects();
    private final FakePacing pacing = new FakePacing();
    private ScriptedTransport transport;
    private EffectiveLogSources sources;

    @BeforeEach
    void setUp() {
        transport = new ScriptedTransport(TransportDescriptor.CursorKind.OBJECT, 2);
        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(true, false, null));
    }

    private BackfillRunner runner(int maxReadAttempts) {
        ObjectProvider<QuarantineSink> noSinks = new StaticListableBeanFactory(new HashMap<>())
                .getBeanProvider(QuarantineSink.class);
        return new BackfillRunner(jobs, sources, new TransportRegistry(List.of(transport)), ingest.service, objects,
                locks, BackfillProperties.defaults(), clock, pacing::pacer, noSinks, maxReadAttempts, metrics);
    }

    private UUID newJob() {
        UUID id = UUID.randomUUID();
        jobs.insert(id, BINDING, STREAM, FROM, TO, "ops-admin", NOW);
        return id;
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private Timer duration(String status) {
        return registry.find(BackfillMetrics.JOB_DURATION).tag("binding", BINDING).tag("status", status).timer();
    }

    @Test
    void aSucceededJobCountsRecordsQuarantinedBatchesAndItsDuration() {
        // Two objects of two records each, the second object holds one record the parser quarantines.
        transport.add("", new Event(FROM.plusSeconds(10), "ok-1", "o1"), new Event(FROM.plusSeconds(11), "ok-2", "o1"),
                new Event(FROM.plusSeconds(20), "ok-3", "o2"), new Event(FROM.plusSeconds(21), "bad-1", "o2"));
        ingest.beforeBatch = () -> clock.advance(Duration.ofSeconds(30));
        UUID id = newJob();

        RunResult result = runner(5).run(id, () -> false);

        assertThat(result).isEqualTo(RunResult.COMPLETED);
        BackfillProgress progress = jobs.find(id).orElseThrow().progress();
        assertThat(progress.records()).isEqualTo(4);
        assertThat(counter(BackfillMetrics.RECORDS_READ, "binding", BINDING))
                .as("records read equals the job's own count").isEqualTo(progress.records());
        assertThat(counter(BackfillMetrics.RECORDS_QUARANTINED, "binding", BINDING)).isEqualTo(1);
        assertThat(counter(BackfillMetrics.BATCHES, "binding", BINDING)).isEqualTo(progress.batches());
        assertThat(counter(BackfillMetrics.OBJECTS_FAILED, "binding", BINDING, "reason", "ingest")).isZero();
        Timer succeeded = duration("SUCCEEDED");
        assertThat(succeeded).as("one SUCCEEDED duration").isNotNull();
        assertThat(succeeded.count()).isEqualTo(1);
        assertThat(succeeded.totalTime(TimeUnit.SECONDS)).as("30 s of clock per ingested batch")
                .isEqualTo(30.0 * ingest.calls);
        assertThat(duration("FAILED")).isNull();
    }

    @Test
    void aFailedIngestCountsTheObjectEveryAttemptAndTheFailedJobHasADuration() {
        transport.add("", new Event(FROM.plusSeconds(10), "ok-1", "o1"));
        ingest.failWith = new IllegalStateException("kafka down");
        UUID id = newJob();
        BackfillRunner runner = runner(5);

        RunResult last = null;
        for (int attempt = 1; attempt <= BackfillProperties.defaults().maxAttempts(); attempt++) {
            clock.advance(Duration.ofHours(1));
            last = runner.run(id, () -> false);
        }

        assertThat(last).isEqualTo(RunResult.FAILED);
        assertThat(jobs.find(id).orElseThrow().status()).isEqualTo(BackfillStatus.FAILED);
        assertThat(counter(BackfillMetrics.OBJECTS_FAILED, "binding", BINDING, "reason", "ingest"))
                .isEqualTo(BackfillProperties.defaults().maxAttempts());
        assertThat(counter(BackfillMetrics.RECORDS_READ, "binding", BINDING)).as("nothing was ingested").isZero();
        assertThat(duration("FAILED")).isNotNull();
        assertThat(duration("FAILED").count()).isEqualTo(1);
        assertThat(duration("SUCCEEDED")).isNull();
    }

    @Test
    void anUnreadableObjectCountsEveryFailedRead() {
        transport.add("", new Event(FROM.plusSeconds(10), "a-1", "bad"), new Event(FROM.plusSeconds(70), "b-1", "good"));
        transport.unreadable.put("bad", Integer.MAX_VALUE);
        UUID id = newJob();
        BackfillRunner runner = runner(3);

        RunResult result = null;
        for (int run = 1; run <= 3; run++) {
            clock.advance(Duration.ofMinutes(20));
            result = runner.run(id, () -> false);
        }

        assertThat(result).as("the third failed read quarantines the object and the job completes")
                .isEqualTo(RunResult.COMPLETED);
        assertThat(counter(BackfillMetrics.OBJECTS_FAILED, "binding", BINDING, "reason", "read")).isEqualTo(3);
        assertThat(counter(BackfillMetrics.RECORDS_READ, "binding", BINDING)).isEqualTo(1);
        assertThat(duration("SUCCEEDED").count()).isEqualTo(1);
    }

    @Test
    void aCancelledJobRecordsItsDurationWithStatusCancelled() {
        BackfillService service = new BackfillService(jobs, sources, new TransportRegistry(List.of(transport)),
                BackfillProperties.defaults(),
                new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(java.time.Clock.class), metrics);
        UUID id = service.create(new CreateRequest(BINDING, STREAM, FROM, TO), "ops-admin").id();
        clock.advance(Duration.ofMinutes(5));

        service.cancel(id, "ops-admin");

        Timer cancelled = duration("CANCELLED");
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.count()).isEqualTo(1);
        assertThat(cancelled.totalTime(TimeUnit.SECONDS)).as("from the request to the cancel").isEqualTo(300.0);
    }

    @Test
    void theStatusGaugesReadTheJobTableAndRefreshAfterTheTtl() {
        metrics.bindJobGauges(jobs, clock);
        UUID first = newJob();
        assertThat(gauge("PENDING")).isEqualTo(1.0);
        assertThat(gauge("SUCCEEDED")).isZero();

        // A change inside the ttl is not visible yet; after it the gauge follows the table.
        jobs.cancel(first, clock.instant(), "test");
        assertThat(gauge("CANCELLED")).as("still the cached read").isZero();
        clock.advance(Duration.ofSeconds(BackfillMetrics.GAUGE_TTL_SECONDS + 1));
        assertThat(gauge("CANCELLED")).isEqualTo(1.0);
        assertThat(gauge("PENDING")).isZero();
        assertThat(registry.find(BackfillMetrics.JOBS).gauges()).extracting(g -> g.getId().getTag("status"))
                .containsExactlyInAnyOrder("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    }

    @Test
    void theBindingLabelFollowsTheBoundedLabelRule() {
        BackfillMetrics many = new BackfillMetrics(registry, b -> "_all");

        many.batch("binding-1", 5, 1);
        many.batch("binding-2", 7, 0);
        many.objectFailed("binding-3", BackfillMetrics.REASON_READ);

        assertThat(counter(BackfillMetrics.RECORDS_READ, "binding", "_all")).isEqualTo(12);
        assertThat(registry.find(BackfillMetrics.RECORDS_READ).counters()).hasSize(1);
        assertThat(counter(BackfillMetrics.OBJECTS_FAILED, "binding", "_all", "reason", "read")).isEqualTo(1);
    }

    @Test
    void aDetachedMetricsObjectNeverThrows() {
        BackfillMetrics detached = BackfillMetrics.detached();

        detached.batch("b", 1, 0);
        detached.objectFailed("b", BackfillMetrics.REASON_INGEST);
        detached.jobEnded("b", BackfillStatus.FAILED, null, NOW);
        detached.jobEnded("b", BackfillStatus.FAILED, NOW, NOW.minusSeconds(5));
    }

    private double gauge(String status) {
        return registry.get(BackfillMetrics.JOBS).tag("status", status).gauge().value();
    }
}
