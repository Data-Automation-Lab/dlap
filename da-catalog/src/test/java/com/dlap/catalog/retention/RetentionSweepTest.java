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

package com.dlap.catalog.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;
import com.dlap.catalog.retention.RetentionAssetStore.StoredOutcome;
import com.dlap.catalog.retention.RetentionAssetStore.TechnicalKeys;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The sweep pages through the catalog, writes only what changed, queues one event per status
 * change, survives a failing asset, and is a function of the injected clock. The stores are mocks
 * here (the stateful, database-backed behaviour is in {@code RetentionSweepContainerTest}); the
 * evaluator and calculator are the real ones.
 */
class RetentionSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID FIRST = new UUID(0L, 0L);

    private final RetentionAssetStore assets = mock(RetentionAssetStore.class);
    private final RetentionRuleStore rules = mock(RetentionRuleStore.class);
    private final RetentionOutbox outbox = mock(RetentionOutbox.class);
    private final ReadCoverageSource coverage = mock(ReadCoverageSource.class);
    private final RetentionProperties properties = new RetentionProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RetentionMetrics metrics = new RetentionMetrics(registry);

    private RetentionSweep sweep(Instant now) {
        when(coverage.snapshot()).thenReturn(ReadCoverageSource.View.none());
        RetentionEvaluator evaluator = new RetentionEvaluator(assets, rules, outbox, properties, metrics);
        return new RetentionSweep(assets, rules, evaluator, coverage, properties, metrics,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static UUID id(int n) {
        return new UUID(0L, n);
    }

    private static StoredOutcome unevaluated() {
        return new StoredOutcome(RetentionStatus.UNKNOWN, null, null, null, null, null, null);
    }

    private static AssetRow row(int n, String created, StoredOutcome current) {
        return new AssetRow(id(n), "gs://bucket-" + n, "GCP", "GCS_BUCKET", "ACTIVE",
                created == null ? null : Instant.parse(created), null, null, null, false,
                new TechnicalKeys(null, null, null, null, null, null), current);
    }

    /** Makes the page query return the given rows in pages by keyset, as the real query does. */
    private void servePages(List<AssetRow> all) {
        when(assets.page(any(), anyInt())).thenAnswer(inv -> {
            UUID after = inv.getArgument(0);
            int limit = inv.getArgument(1);
            return all.stream().filter(r -> r.assetId().compareTo(after) > 0).limit(limit).toList();
        });
        when(assets.labelNames(any())).thenReturn(Map.of());
        when(rules.findByAssets(any())).thenReturn(Map.of());
        for (AssetRow r : all) {
            when(assets.lockById(r.assetId())).thenReturn(Optional.of(r));
        }
    }

    @Test
    void readsEveryAssetOnceInKeysetPages() {
        properties.setPageSize(2);
        List<AssetRow> all = new ArrayList<>();
        for (int n = 1; n <= 5; n++) {
            all.add(row(n, null, new StoredOutcome(RetentionStatus.NO_POLICY, null, null, null, null, null, NOW)));
        }
        servePages(all);

        RetentionSweep.Result r = sweep(NOW).run();

        assertThat(r.scanned()).isEqualTo(5);
        assertThat(r.pages()).isEqualTo(3);
        ArgumentCaptor<UUID> after = ArgumentCaptor.forClass(UUID.class);
        verify(assets, times(3)).page(after.capture(), eq(2));
        assertThat(after.getAllValues()).containsExactly(FIRST, id(2), id(4));
    }

    @Test
    void anEmptyCatalogIsOnePageRead() {
        servePages(List.of());

        RetentionSweep.Result r = sweep(NOW).run();

        assertThat(r.scanned()).isZero();
        assertThat(r.pages()).isZero();
        assertThat(r.aborted()).isFalse();
    }

    @Test
    void aStatusChangeIsWrittenOnceWithItsEvent() {
        // Created 2025-01-01, label default 365 days from CREATED: expired 2026-01-01.
        properties.setLabelDefaults(List.of(labelDefault("PII", 365, RetentionBasis.CREATED)));
        AssetRow due = row(1, "2025-01-01T00:00:00Z", unevaluated());
        servePages(List.of(due));
        when(assets.labelNames(any())).thenReturn(Map.of(id(1), Set.of("pii")));
        when(assets.labelNames(List.of(id(1)))).thenReturn(Map.of(id(1), Set.of("pii")));

        RetentionSweep.Result r = sweep(NOW).run();

        assertThat(r.changed()).isEqualTo(1);
        assertThat(r.transitions()).isEqualTo(1);
        ArgumentCaptor<RetentionEvaluation> eval = ArgumentCaptor.forClass(RetentionEvaluation.class);
        verify(assets).writeOutcome(eq(id(1)), eval.capture(), eq(NOW));
        assertThat(eval.getValue().status()).isEqualTo(RetentionStatus.DUE);
        assertThat(eval.getValue().expiresAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        ArgumentCaptor<RetentionDueEvent> event = ArgumentCaptor.forClass(RetentionDueEvent.class);
        verify(outbox).add(event.capture());
        assertThat(event.getValue().payload()).contains("\"previousStatus\":\"UNKNOWN\"")
                .contains("\"status\":\"DUE\"");
    }

    @Test
    void anUnchangedAssetWithAFreshEvaluationIsNeitherLockedNorWritten() {
        // NO_POLICY evaluated at NOW with a one-day refresh age: a second run at NOW changes nothing.
        AssetRow settled = row(1, "2025-01-01T00:00:00Z",
                new StoredOutcome(RetentionStatus.NO_POLICY, null, null, null, null, null, NOW));
        servePages(List.of(settled));

        RetentionSweep.Result r = sweep(NOW).run();

        assertThat(r.unchanged()).isEqualTo(1);
        assertThat(r.changed()).isZero();
        verify(assets, never()).lockById(any());
        verify(assets, never()).writeOutcome(any(), any(), any());
        verify(outbox, never()).add(any());
    }

    @Test
    void anUnchangedAssetIsRefreshedWhenItsEvaluationIsOlderThanTheRefreshAgeButQueuesNoEvent() {
        AssetRow stale = row(1, "2025-01-01T00:00:00Z", new StoredOutcome(RetentionStatus.NO_POLICY, null, null, null,
                null, null, NOW.minus(Duration.ofDays(2))));
        servePages(List.of(stale));

        RetentionSweep.Result r = sweep(NOW).run();

        assertThat(r.changed()).isEqualTo(1);
        assertThat(r.transitions()).isZero();
        verify(assets).writeOutcome(eq(id(1)), any(), eq(NOW));
        verify(outbox, never()).add(any());
    }

    @Test
    void aZeroRefreshAgeRewritesEveryAssetEveryRun() {
        properties.setEvaluatedAtRefresh(Duration.ZERO);
        AssetRow settled = row(1, null, new StoredOutcome(RetentionStatus.NO_POLICY, null, null, null, null, null, NOW));
        servePages(List.of(settled));

        assertThat(sweep(NOW).run().changed()).isEqualTo(1);
    }

    @Test
    void theSameAssetIsRetainedTodayAndDueOnceTheInjectedClockPassesItsExpiry() {
        properties.setLabelDefaults(List.of(labelDefault("PII", 10, RetentionBasis.CREATED)));
        // Created 2026-10-15 + 10 days = 2026-10-25.
        StoredOutcome retained = new StoredOutcome(RetentionStatus.RETAINED, 10, RetentionBasis.CREATED,
                "LABEL_DEFAULT", null, Instant.parse("2026-10-25T00:00:00Z"), NOW);
        AssetRow asset = row(1, "2026-10-15T00:00:00Z", retained);
        servePages(List.of(asset));
        when(assets.labelNames(any())).thenReturn(Map.of(id(1), Set.of("pii")));

        // Before the expiry: nothing changes and no event is queued.
        RetentionSweep.Result before = sweep(Instant.parse("2026-10-24T23:59:59Z")).run();
        // After it: DUE, with the RETAINED -> DUE event.
        RetentionSweep.Result after = sweep(Instant.parse("2026-10-25T00:00:00Z")).run();

        assertThat(before.transitions()).isZero();
        assertThat(after.transitions()).isEqualTo(1);
        ArgumentCaptor<RetentionDueEvent> event = ArgumentCaptor.forClass(RetentionDueEvent.class);
        verify(outbox).add(event.capture());
        assertThat(event.getValue().payload()).contains("\"previousStatus\":\"RETAINED\"")
                .contains("\"status\":\"DUE\"").contains("\"evaluatedAt\":\"2026-10-25T00:00:00Z\"");
    }

    @Test
    void aFailingAssetIsCountedAndTheSweepGoesOn() {
        properties.setLabelDefaults(List.of(labelDefault("PII", 1, RetentionBasis.CREATED)));
        AssetRow bad = row(1, "2025-01-01T00:00:00Z", unevaluated());
        AssetRow good = row(2, "2025-01-01T00:00:00Z", unevaluated());
        servePages(List.of(bad, good));
        when(assets.labelNames(any())).thenReturn(Map.of(id(1), Set.of("pii"), id(2), Set.of("pii")));
        when(assets.lockById(id(1))).thenThrow(new IllegalStateException("db hiccup"));

        RetentionSweep.Result r = sweep(NOW).run();

        assertThat(r.errors()).isEqualTo(1);
        assertThat(r.changed()).isEqualTo(1);
        assertThat(r.aborted()).isFalse();
        verify(assets).writeOutcome(eq(id(2)), any(), eq(NOW));
    }

    @Test
    void repeatedConsecutiveFailuresAbortThePassSoADownDatabaseIsNotHammered() {
        properties.setLabelDefaults(List.of(labelDefault("PII", 1, RetentionBasis.CREATED)));
        List<AssetRow> all = new ArrayList<>();
        for (int n = 1; n <= RetentionSweep.ABORT_AFTER_CONSECUTIVE_ERRORS + 10; n++) {
            all.add(row(n, "2025-01-01T00:00:00Z", unevaluated()));
        }
        servePages(all);
        when(assets.labelNames(any())).thenAnswer(inv -> {
            Map<UUID, Set<String>> m = new java.util.HashMap<>();
            all.forEach(a -> m.put(a.assetId(), Set.of("pii")));
            return m;
        });
        when(assets.lockById(any())).thenThrow(new IllegalStateException("db down"));

        RetentionSweep.Result r = sweep(NOW).run();

        assertThat(r.aborted()).isTrue();
        assertThat(r.errors()).isEqualTo(RetentionSweep.ABORT_AFTER_CONSECUTIVE_ERRORS);
        assertThat(registry.find(RetentionMetrics.SWEEP_RUNS).tag("outcome", "aborted").counter().count())
                .isEqualTo(1);
    }

    @Test
    void aDeletedAssetIsSkippedNotEvaluated() {
        properties.setLabelDefaults(List.of(labelDefault("PII", 1, RetentionBasis.CREATED)));
        AssetRow deleted = new AssetRow(id(1), "gs://b", "GCP", "GCS_BUCKET", "DELETED",
                Instant.parse("2025-01-01T00:00:00Z"), null, null, null, false,
                new TechnicalKeys(null, null, null, null, null, null), unevaluated());
        servePages(List.of(deleted));
        when(assets.labelNames(any())).thenReturn(Map.of(id(1), Set.of("pii")));

        RetentionSweep.Result r = sweep(NOW).run();

        assertThat(r.changed()).isZero();
        verify(assets, never()).writeOutcome(any(), any(), any());
    }

    @Test
    void aNewerStoredEvaluationIsNotOverwrittenByASweepThatStartedEarlier() {
        properties.setLabelDefaults(List.of(labelDefault("PII", 1, RetentionBasis.CREATED)));
        // Stored at NOW+1h (an API action during the sweep); the sweep's as-of is NOW.
        AssetRow newer = row(1, "2025-01-01T00:00:00Z", new StoredOutcome(RetentionStatus.UNKNOWN, null, null, null,
                null, null, NOW.plus(Duration.ofHours(1))));
        servePages(List.of(newer));
        when(assets.labelNames(any())).thenReturn(Map.of(id(1), Set.of("pii")));

        RetentionSweep.Result r = sweep(NOW).run();

        assertThat(r.changed()).isZero();
        verify(assets, never()).writeOutcome(any(), any(), any());
    }

    private static RetentionProperties.LabelDefault labelDefault(String label, int days, RetentionBasis basis) {
        RetentionProperties.LabelDefault d = new RetentionProperties.LabelDefault();
        d.setLabel(label);
        d.setDays(days);
        d.setBasis(basis);
        return d;
    }
}
