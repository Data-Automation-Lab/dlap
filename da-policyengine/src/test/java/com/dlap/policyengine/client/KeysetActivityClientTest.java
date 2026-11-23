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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogPageDTO;

/**
 * POL-05 (Q-445): the keyset call of the circuit-broken catalog client sends exactly {@code after}, {@code size},
 * the statuses and the as-of instant, answers empty when the catalog is down, and the cache settings refuse
 * values that would break the freshness bound.
 */
class KeysetActivityClientTest {

    private static final Instant AS_OF = Instant.parse("2026-11-03T10:00:00Z");
    private static final List<String> STATUSES = List.of("ACTIVE", "ARCHIVED");

    private final ICatalogAssetClient feign = mock(ICatalogAssetClient.class);
    private final ResilientCatalogAssetClient client = new ResilientCatalogAssetClient(feign);

    @Test
    void theKeysetCallSendsTheCursorTheSizeTheStatusesAndTheAsOfInstantAndNoPageOrSort() {
        CatalogPageDTO<CatalogActivityRowDTO> page = new CatalogPageDTO<>(List.of(), null, null, null, null, "");
        when(feign.getAssetActivityAfter("gs://b", 200, STATUSES, AS_OF.toString())).thenReturn(page);

        Optional<CatalogPageDTO<CatalogActivityRowDTO>> answer = client.getAssetActivityAfter("gs://b", 200, STATUSES,
                AS_OF);

        assertThat(answer).containsSame(page);
        verify(feign).getAssetActivityAfter("gs://b", 200, STATUSES, AS_OF.toString());
    }

    @Test
    void anEmptyBodyIsNoPage() {
        assertThat(client.getAssetActivityAfter("gs://b", 200, STATUSES, AS_OF)).isEmpty();
    }

    @Test
    void theFallbackAnswersEmptyAndNeverThrows() throws Exception {
        Method fallback = ResilientCatalogAssetClient.class.getDeclaredMethod("fallbackActivityAfter", String.class,
                int.class, List.class, Instant.class, Throwable.class);
        fallback.setAccessible(true);

        Object answer = fallback.invoke(client, "gs://b", 200, STATUSES, AS_OF, new IllegalStateException("open"));

        assertThat(answer).isEqualTo(Optional.empty());
    }

    @Test
    void theCacheSettingsDefaultToThirtySecondsAndTenThousandEntries() {
        EventPathCatalogCacheProperties defaults = new EventPathCatalogCacheProperties(null, 10_000);

        assertThat(defaults.ttl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.maxEntries()).isEqualTo(10_000);
    }

    @Test
    void theCacheSettingsRefuseANegativeTtlAndAnEmptyBound() {
        assertThatThrownBy(() -> new EventPathCatalogCacheProperties(Duration.ofSeconds(-1), 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ttl");
        assertThatThrownBy(() -> new EventPathCatalogCacheProperties(Duration.ofSeconds(30), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-entries");
    }
}
