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

package com.dlap.policyengine.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.dlap.policyengine.client.dto.CatalogAssetDTO;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-05 (decision D45, Q-437): the bounded, short-lived cache of the catalog lookups on the policy event
 * path. It never serves a value older than its TTL (so the P2D coverage freshness rule of ADR-0130 still
 * holds), holds at most the configured number of entries, stores no empty answer, and counts hits and misses.
 */
class EventPathCatalogLookupTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000001");
    private static final String URI = "gs://prod-customer-exports";

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(T0);

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final MovableClock clock = new MovableClock();
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private EventPathCatalogLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new EventPathCatalogLookup(catalog, clock, registry, TTL, 10_000);
    }

    private static CatalogAssetDTO asset(UUID id) {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(id);
        a.setAssetUri(URI);
        return a;
    }

    private double count(String lookupKind, String result) {
        return registry.get(EventPathCatalogLookup.COUNTER).tag("lookup", lookupKind).tag("result", result)
                .counter().count();
    }

    @Test
    void aRepeatedAssetLookupInsideTheTtlIsServedFromMemoryAndCountedAsAHit() {
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset(ASSET)));

        Optional<CatalogAssetDTO> first = lookup.getAssetById(ASSET);
        clock.advance(Duration.ofSeconds(29));
        Optional<CatalogAssetDTO> second = lookup.getAssetById(ASSET);

        assertThat(first).isPresent();
        assertThat(second).containsSame(first.get());
        verify(catalog, times(1)).getAssetById(ASSET);
        assertThat(count("asset", "miss")).isEqualTo(1.0);
        assertThat(count("asset", "hit")).isEqualTo(1.0);
    }

    @Test
    void aRepeatedUriResolutionInsideTheTtlIsServedFromMemory() {
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.of(ASSET));

        assertThat(lookup.resolveAssetIdByUri(URI)).contains(ASSET);
        assertThat(lookup.resolveAssetIdByUri("  " + URI + " ")).contains(ASSET);

        verify(catalog, times(1)).resolveAssetIdByUri(URI);
        assertThat(count("uri", "miss")).isEqualTo(1.0);
        assertThat(count("uri", "hit")).isEqualTo(1.0);
    }

    @Test
    void neverServesAValueAsOldAsTheTtlOrOlder() {
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset(ASSET)));
        lookup.getAssetById(ASSET);

        clock.set(T0.plus(TTL).minusMillis(1));
        lookup.getAssetById(ASSET);
        verify(catalog, times(1)).getAssetById(ASSET);

        clock.set(T0.plus(TTL));
        lookup.getAssetById(ASSET);
        verify(catalog, times(2)).getAssetById(ASSET);

        // The reload restarted the entry's age: it is served again until a full TTL after the reload.
        clock.set(T0.plus(TTL).plusSeconds(29));
        lookup.getAssetById(ASSET);
        verify(catalog, times(2)).getAssetById(ASSET);

        clock.set(T0.plus(TTL).plus(TTL).plusSeconds(1));
        lookup.getAssetById(ASSET);
        verify(catalog, times(3)).getAssetById(ASSET);
    }

    @Test
    void anExpiredValueIsReplacedByTheNewAnswerNotKept() {
        CatalogAssetDTO old = asset(ASSET);
        CatalogAssetDTO fresh = asset(ASSET);
        fresh.setReadCoverage("FULL");
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(old), Optional.of(fresh));
        lookup.getAssetById(ASSET);

        clock.advance(Duration.ofMinutes(5));

        assertThat(lookup.getAssetById(ASSET)).containsSame(fresh);
        assertThat(lookup.getAssetById(ASSET)).containsSame(fresh);
        assertThat(lookup.size()).isEqualTo(1);
    }

    @Test
    void aValueStoredBeforeTheClockWentBackIsNotServed() {
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset(ASSET)));
        lookup.getAssetById(ASSET);

        clock.set(T0.minusSeconds(5));
        lookup.getAssetById(ASSET);

        verify(catalog, times(2)).getAssetById(ASSET);
    }

    @Test
    void anEmptyAnswerIsNeverStoredSoANewAssetOrARecoveredCatalogIsSeenAtOnce() {
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.empty(), Optional.of(asset(ASSET)));
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.empty(), Optional.of(ASSET));

        assertThat(lookup.getAssetById(ASSET)).isEmpty();
        assertThat(lookup.getAssetById(ASSET)).isPresent();
        assertThat(lookup.resolveAssetIdByUri(URI)).isEmpty();
        assertThat(lookup.resolveAssetIdByUri(URI)).contains(ASSET);

        verify(catalog, times(2)).getAssetById(ASSET);
        verify(catalog, times(2)).resolveAssetIdByUri(URI);
        assertThat(count("asset", "hit")).isZero();
        assertThat(count("uri", "hit")).isZero();
    }

    @Test
    void holdsAtMostTheConfiguredNumberOfEntriesAndDropsTheLeastRecentlyUsed() {
        MeterRegistry own = new SimpleMeterRegistry();
        lookup = new EventPathCatalogLookup(catalog, clock, own, TTL, 3);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        for (UUID id : new UUID[] {a, b, c, d}) {
            when(catalog.getAssetById(id)).thenReturn(Optional.of(asset(id)));
        }

        lookup.getAssetById(a);
        lookup.getAssetById(b);
        lookup.getAssetById(c);
        lookup.getAssetById(a);
        lookup.getAssetById(d);

        assertThat(lookup.size()).isEqualTo(3);
        lookup.getAssetById(a);
        lookup.getAssetById(c);
        lookup.getAssetById(d);
        verify(catalog, times(1)).getAssetById(a);
        verify(catalog, times(1)).getAssetById(c);
        verify(catalog, times(1)).getAssetById(d);
        lookup.getAssetById(b);
        verify(catalog, times(2)).getAssetById(b);
        assertThat(own.get(EventPathCatalogLookup.GAUGE).gauge().value()).isEqualTo(3.0);
    }

    @Test
    void theBoundCoversAssetAndUriEntriesTogether() {
        lookup = new EventPathCatalogLookup(catalog, clock, registry, TTL, 2);
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset(ASSET)));
        when(catalog.resolveAssetIdByUri(any())).thenReturn(Optional.of(ASSET));

        lookup.getAssetById(ASSET);
        lookup.resolveAssetIdByUri("gs://one");
        lookup.resolveAssetIdByUri("gs://two");

        assertThat(lookup.size()).isEqualTo(2);
    }

    @Test
    void aTenThousandEntryBoundHoldsUnderLoad() {
        when(catalog.getAssetById(any())).thenAnswer(inv -> Optional.of(asset(inv.getArgument(0))));

        for (int i = 0; i < 12_000; i++) {
            lookup.getAssetById(UUID.randomUUID());
        }

        assertThat(lookup.size()).isEqualTo(10_000);
    }

    @Test
    void aTtlOfZeroTurnsTheCacheOffAndEveryCallGoesToTheCatalog() {
        lookup = new EventPathCatalogLookup(catalog, clock, registry, Duration.ZERO, 10);
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset(ASSET)));

        lookup.getAssetById(ASSET);
        lookup.getAssetById(ASSET);

        verify(catalog, times(2)).getAssetById(ASSET);
        assertThat(lookup.size()).isZero();
        assertThat(count("asset", "miss")).isEqualTo(2.0);
        assertThat(count("asset", "hit")).isZero();
    }

    @Test
    void nullAndBlankKeysAreAnsweredEmptyWithoutACatalogCall() {
        assertThat(lookup.getAssetById(null)).isEmpty();
        assertThat(lookup.resolveAssetIdByUri(null)).isEmpty();
        assertThat(lookup.resolveAssetIdByUri("  ")).isEmpty();

        verify(catalog, never()).getAssetById(any());
        verify(catalog, never()).resolveAssetIdByUri(any());
    }

    @Test
    void clearDropsEveryEntry() {
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset(ASSET)));
        lookup.getAssetById(ASSET);

        lookup.clear();
        lookup.getAssetById(ASSET);

        verify(catalog, times(2)).getAssetById(ASSET);
    }

    @Test
    void refusesANegativeTtlAndAnEmptyBound() {
        assertThatThrownBy(() -> new EventPathCatalogLookup(catalog, clock, registry, Duration.ofSeconds(-1), 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ttl");
        assertThatThrownBy(() -> new EventPathCatalogLookup(catalog, clock, registry, TTL, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-entries");
    }

    @Test
    void theComponentReadsItsTtlAndBoundFromTheEnvironmentWithD45Defaults() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(ResilientCatalogAssetClient.class, () -> mock(ResilientCatalogAssetClient.class))
                .withBean(Clock.class, () -> Clock.fixed(T0, ZoneId.of("UTC")))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(EventPathCatalogLookup.class);

        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EventPathCatalogLookup.class)).hasFieldOrPropertyWithValue("ttl", Duration.ofSeconds(30))
                    .hasFieldOrPropertyWithValue("maxEntries", 10_000);
        });
        runner.withPropertyValues(EventPathCatalogLookup.PROP_TTL + "=PT5S", EventPathCatalogLookup.PROP_MAX_ENTRIES + "=50")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(EventPathCatalogLookup.class)).hasFieldOrPropertyWithValue("ttl",
                            Duration.ofSeconds(5)).hasFieldOrPropertyWithValue("maxEntries", 50);
                });
    }
}
