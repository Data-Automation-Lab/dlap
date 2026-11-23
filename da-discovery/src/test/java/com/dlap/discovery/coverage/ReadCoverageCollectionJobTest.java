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

package com.dlap.discovery.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

/** COV-01: the job runs every collector under the lock and publishes in the single encoding. */
class ReadCoverageCollectionJobTest {

    private static final Instant NOW = Instant.parse("2026-10-20T06:41:00Z");

    /** A lock provider that grants or refuses every lock and records the configurations. */
    static final class FakeLocks implements LockProvider {
        final List<LockConfiguration> taken = new ArrayList<>();
        boolean free = true;

        @Override
        public Optional<SimpleLock> lock(LockConfiguration config) {
            taken.add(config);
            return free ? Optional.of(() -> { }) : Optional.empty();
        }
    }

    private static ReadCoverageObservation obs(String root) {
        return new ReadCoverageObservation("AZURE", "sub-1", root, List.of("BLOB_STORAGE"), "AZURE_STORAGE_RESOURCE_LOG",
                CoverageVerdict.none(List.of("no diagnostic setting on the blob service")), "azure@1.0.0");
    }

    private static ReadCoverageCollector collector(String id, List<ReadCoverageObservation> out) {
        return new ReadCoverageCollector() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<ReadCoverageObservation> collect() {
                return out;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> template() {
        KafkaTemplate<String, String> t = mock(KafkaTemplate.class);
        when(t.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        return t;
    }

    private static ReadCoverageCollectionJob job(KafkaTemplate<String, String> t, FakeLocks locks,
                                                 SimpleMeterRegistry meters, ReadCoverageCollector... collectors) {
        ReadCoveragePublisher publisher = new ReadCoveragePublisher(t, new ReadCoverageEventEncoder(CoverageFixtures.MAPPER),
                Duration.ofSeconds(5));
        return new ReadCoverageCollectionJob(List.of(collectors), publisher, new DefaultLockingTaskExecutor(locks),
                new ReadCoverageProperties(), meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesEachObservationKeyedByItsRootInTheSingleEncoding() {
        KafkaTemplate<String, String> t = template();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FakeLocks locks = new FakeLocks();
        job(t, locks, meters, collector("azure", List.of(obs("az://a"), obs("az://b")))).scheduledRun();

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(t).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED), eq("az://a"), value.capture());
        verify(t).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED), eq("az://b"), anyString());
        assertThat(value.getValue()).startsWith("{").as("single encoding: an object, not a quoted string");
        assertThat(DlapLogEventCodec.defaults().decodeStrict(value.getValue()).getEventTime().getSeconds())
                .isEqualTo(NOW.getEpochSecond());

        assertThat(locks.taken).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo(ReadCoverageCollectionJob.LOCK_NAME);
            assertThat(c.getLockAtMostFor()).isEqualTo(Duration.ofHours(1));
        });
        assertThat(meters.counter(ReadCoverageCollectionJob.METER_RUNS, "outcome", "completed").count()).isEqualTo(1.0);
        assertThat(meters.counter(ReadCoverageCollectionJob.METER_OBSERVATIONS, "collector", "azure", "level", "none",
                "outcome", "sent").count()).isEqualTo(2.0);
    }

    @Test
    void aHeldLockSkipsTheRun() {
        KafkaTemplate<String, String> t = template();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FakeLocks locks = new FakeLocks();
        locks.free = false;
        job(t, locks, meters, collector("azure", List.of(obs("az://a")))).scheduledRun();

        verify(t, never()).send(anyString(), anyString(), anyString());
        assertThat(meters.counter(ReadCoverageCollectionJob.METER_RUNS, "outcome", "locked").count()).isEqualTo(1.0);
    }

    @Test
    void aFailingCollectorPublishesNothingAndTheOthersStillRun() {
        KafkaTemplate<String, String> t = template();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReadCoverageCollector broken = new ReadCoverageCollector() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public List<ReadCoverageObservation> collect() {
                throw new CoverageHttp.CoverageTransportException("provider unreachable");
            }
        };
        int sent = job(t, new FakeLocks(), meters, broken, collector("azure", List.of(obs("az://a")))).runOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(meters.counter(ReadCoverageCollectionJob.METER_OBSERVATIONS, "collector", "broken", "level", "all",
                "outcome", "failed").count()).isEqualTo(1.0);
    }

    @Test
    void anUnacknowledgedSendIsCounted() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> t = mock(KafkaTemplate.class);
        when(t.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        int sent = job(t, new FakeLocks(), meters, collector("azure", List.of(obs("az://a")))).runOnce();

        assertThat(sent).isZero();
        assertThat(meters.counter(ReadCoverageCollectionJob.METER_OBSERVATIONS, "collector", "azure", "level", "all",
                "outcome", "unacknowledged").count()).isEqualTo(1.0);
    }
}
