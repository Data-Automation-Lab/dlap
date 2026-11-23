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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.logingest.backfill.BackfillService.BackfillConflictException;
import com.dlap.logingest.backfill.BackfillService.BackfillNotFoundException;
import com.dlap.logingest.backfill.BackfillService.BackfillUnavailableException;
import com.dlap.logingest.backfill.BackfillService.CreateRequest;
import com.dlap.logingest.backfill.BackfillTestKit.InMemoryJobs;
import com.dlap.logingest.backfill.BackfillTestKit.ScriptedTransport;
import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.ValidationReport;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.transport.TransportDescriptor;
import com.dlap.logingest.transport.TransportRegistry;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** What a backfill request must satisfy before a job is accepted, and what the API shows afterwards. */
class BackfillServiceTest {

    private static final Instant NOW = T0.plus(Duration.ofDays(30));

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryJobs jobs = new InMemoryJobs();
    private final TransportRegistry transports = new TransportRegistry(List.of(
            new ScriptedTransport(TransportDescriptor.CursorKind.TIME, 5)));

    private BackfillService service(EffectiveLogSources sources) {
        return new BackfillService(jobs, sources, transports, BackfillProperties.defaults(),
                new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(java.time.Clock.class));
    }

    private BackfillService allowed() {
        return service(BackfillTestKit.sources(true, BackfillTestKit.stream(true, false, null)));
    }

    private static CreateRequest request(Instant from, Instant to) {
        return new CreateRequest(BINDING, null, from, to);
    }

    @Test
    void anAcceptedJobIsPendingWithItsWindowTheStreamAndWhoAsked() {
        BackfillJob job = allowed().create(request(T0, T0.plus(Duration.ofDays(2))), "ops-admin");

        assertThat(job.status()).isEqualTo(BackfillStatus.PENDING);
        assertThat(job.bindingId()).isEqualTo(BINDING);
        assertThat(job.streamId()).as("the only stream that allows backfill is chosen").isEqualTo(STREAM);
        assertThat(job.from()).isEqualTo(T0);
        assertThat(job.to()).isEqualTo(T0.plus(Duration.ofDays(2)));
        assertThat(job.requestedBy()).isEqualTo("ops-admin");
        assertThat(job.requestedAt()).isEqualTo(NOW);
        assertThat(job.progress()).isEqualTo(BackfillProgress.EMPTY);
    }

    @Test
    void theWindowIsBounded() {
        BackfillService s = allowed();

        assertThatThrownBy(() -> s.create(request(T0, T0), "a")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
        assertThatThrownBy(() -> s.create(request(T0.plusSeconds(1), T0), "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.create(request(null, T0), "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.create(request(T0, null), "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.create(request(NOW.minus(Duration.ofDays(1)), NOW.plusSeconds(1)), "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> s.create(request(NOW.minus(Duration.ofDays(90)).minusSeconds(1), NOW), "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-window");
        assertThat(jobs.rows).isEmpty();
        // Exactly the ceiling, and exactly now, are fine.
        assertThat(s.create(request(NOW.minus(Duration.ofDays(90)), NOW), "a").status())
                .isEqualTo(BackfillStatus.PENDING);
    }

    @Test
    void aBindingHasOneActiveJobAtATimeAndANewOneIsAllowedOnceItEnded() {
        BackfillService s = allowed();
        BackfillJob first = s.create(request(T0, T0.plus(Duration.ofDays(1))), "a");

        assertThatThrownBy(() -> s.create(request(T0, T0.plus(Duration.ofDays(1))), "b"))
                .isInstanceOf(BackfillConflictException.class)
                .hasMessageContaining("already has an active backfill job").hasMessageContaining(first.id().toString());

        jobs.markRunning(first.id(), NOW);
        assertThatThrownBy(() -> s.create(request(T0, T0.plus(Duration.ofDays(1))), "b"))
                .isInstanceOf(BackfillConflictException.class);

        jobs.finish(first.id(), BackfillStatus.SUCCEEDED, NOW, "", BackfillProgress.EMPTY);
        assertThat(s.create(request(T0, T0.plus(Duration.ofDays(1))), "b").status()).isEqualTo(BackfillStatus.PENDING);
    }

    @Test
    void historyIsReadOnlyWhereTheBindingsFileAllowsIt() {
        BackfillService denied = service(BackfillTestKit.sources(true, BackfillTestKit.stream(false, false, null)));

        assertThatThrownBy(() -> denied.create(request(T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(BackfillConflictException.class).hasMessageContaining("backfill.allowed");
        assertThatThrownBy(() -> denied.create(new CreateRequest(BINDING, STREAM, T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(BackfillConflictException.class).hasMessageContaining("does not allow backfill");
        assertThat(jobs.rows).isEmpty();
    }

    @Test
    void aPushChannelHasNoHistoryAndAnUnknownStreamOrBindingIsRefused() {
        BackfillService push = service(BackfillTestKit.sources(true, BackfillTestKit.stream(true, true, null)));
        assertThatThrownBy(() -> push.create(new CreateRequest(BINDING, STREAM, T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("push channel");
        assertThatThrownBy(() -> allowed().create(new CreateRequest(BINDING, "nope", T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no stream nope");
        assertThatThrownBy(() -> allowed().create(new CreateRequest("unknown", null, T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(BackfillNotFoundException.class);
        assertThatThrownBy(() -> allowed().create(new CreateRequest(" ", null, T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allowed().create(null, "a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDisabledBindingAndTheLegacyPipelineAreConflicts() {
        BackfillService disabled = service(BackfillTestKit.sources(false, BackfillTestKit.stream(true, false, null)));
        assertThatThrownBy(() -> disabled.create(request(T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(BackfillConflictException.class).hasMessageContaining("does not run");

        EffectiveLogSources legacy = new EffectiveLogSources(PipelineMode.LEGACY, "FILE", List.of(),
                ValidationReport.CLEAN);
        assertThatThrownBy(() -> service(legacy).create(request(T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(BackfillUnavailableException.class).hasMessageContaining("pipeline=registry");
    }

    @Test
    void whenSeveralStreamsAllowBackfillTheRequestMustNameOne() {
        EffectiveStream one = BackfillTestKit.stream(true, false, null);
        EffectiveStream two = new EffectiveStream(BINDING, "second", BackfillTestKit.TRANSPORT, "other-envelope",
                Map.of(), new BindingConfig(BINDING, BackfillTestKit.KIND, Optional.empty(), null, Map.of(), List.of(),
                null, BindingConfig.DEFAULT_MAX_RECORD_BYTES, AssetIdHints.none()), StreamFilters.NONE, Duration.ZERO,
                Duration.ofMinutes(1), null, null, true, false, false);
        EffectiveBinding binding = EffectiveBinding.of(BINDING, "src", BackfillTestKit.KIND,
                Optional.of(SourceProvider.AWS), ConfigOrigin.FILE, true, true, Map.of(), List.of(one, two));
        BackfillService s = service(new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", List.of(binding),
                ValidationReport.CLEAN));

        assertThatThrownBy(() -> s.create(request(T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name one with streamId");
        assertThat(s.create(new CreateRequest(BINDING, "second", T0, T0.plusSeconds(60)), "a").streamId())
                .isEqualTo("second");
    }

    @Test
    void aStreamWhoseTransportIsNotRegisteredIsRefused() {
        BackfillService noTransport = new BackfillService(jobs,
                BackfillTestKit.sources(true, BackfillTestKit.stream(true, false, null)),
                new TransportRegistry(List.of()), BackfillProperties.defaults(),
                new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(java.time.Clock.class));

        assertThatThrownBy(() -> noTransport.create(new CreateRequest(BINDING, STREAM, T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(BackfillConflictException.class).hasMessageContaining("SCRIPTED");
        assertThatThrownBy(() -> noTransport.create(request(T0, T0.plusSeconds(60)), "a"))
                .isInstanceOf(BackfillConflictException.class);
    }

    @Test
    void getListAndCancel() {
        BackfillService s = allowed();
        BackfillJob job = s.create(request(T0, T0.plus(Duration.ofDays(1))), "a");

        assertThat(s.get(job.id())).isEqualTo(job);
        assertThatThrownBy(() -> s.get(UUID.randomUUID())).isInstanceOf(BackfillNotFoundException.class);
        assertThat(s.list(BINDING, BackfillStatus.PENDING, 0, 10)).containsExactly(job);
        assertThat(s.list(BINDING, BackfillStatus.RUNNING, 0, 10)).isEmpty();
        assertThat(s.count(BINDING, null)).isEqualTo(1);

        BackfillJob cancelled = s.cancel(job.id(), "ops-admin");

        assertThat(cancelled.status()).isEqualTo(BackfillStatus.CANCELLED);
        assertThat(cancelled.errorDetail()).isEqualTo("cancelled by ops-admin");
        assertThat(cancelled.finishedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> s.cancel(job.id(), "ops-admin")).isInstanceOf(BackfillConflictException.class)
                .hasMessageContaining("already ended as CANCELLED");
        assertThatThrownBy(() -> s.cancel(UUID.randomUUID(), "x")).isInstanceOf(BackfillNotFoundException.class);
        assertThat(s.create(request(T0, T0.plus(Duration.ofDays(1))), "a").status())
                .as("a cancelled job frees its binding").isEqualTo(BackfillStatus.PENDING);
    }
}
