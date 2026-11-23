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

package com.dlap.catalog.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import com.dlap.catalog.kafka.CatalogIngestProperties;

/**
 * Pruning cuts at {@code clock - retention}, deletes in bounded batches, and survives a
 * database error.
 */
@ExtendWith(MockitoExtension.class)
class ProcessedEventPrunerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");

    @Mock
    private ProcessedEventStore store;

    private ProcessedEventPruner pruner(Duration retention, int batch) {
        CatalogIngestProperties properties = new CatalogIngestProperties();
        properties.getProcessedEvent().setRetention(retention);
        properties.getProcessedEvent().setPruneBatchSize(batch);
        return new ProcessedEventPruner(store, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void deletesInBatchesUntilAShortBatch() {
        Instant cutoff = NOW.minus(Duration.ofDays(100));
        when(store.pruneBefore(cutoff, 10)).thenReturn(10, 10, 3);

        int deleted = pruner(Duration.ofDays(100), 10).pruneOnce();

        assertThat(deleted).isEqualTo(23);
        verify(store, times(3)).pruneBefore(cutoff, 10);
    }

    @Test
    void stopsAfterTheBatchLimit() {
        when(store.pruneBefore(any(), anyInt())).thenReturn(5);

        int deleted = pruner(Duration.ofDays(1), 5).pruneOnce();

        assertThat(deleted).isEqualTo(5 * ProcessedEventPruner.MAX_BATCHES_PER_RUN);
    }

    @Test
    void aDatabaseErrorIsLoggedAndTheNextRunRetries() {
        when(store.pruneBefore(any(), anyInt())).thenThrow(new QueryTimeoutException("timeout"));

        pruner(Duration.ofDays(1), 5).scheduledPrune();

        verify(store).pruneBefore(NOW.minus(Duration.ofDays(1)), 5);
    }

    @Test
    void longKeysAreStoredAsTheirHash() {
        String longKey = "crawler-uri-".repeat(20);

        assertThat(ProcessedEventStore.storedKey("e-1")).isEqualTo("e-1");
        assertThat(ProcessedEventStore.storedKey(longKey)).startsWith("sha256:")
                .hasSizeLessThanOrEqualTo(ProcessedEventStore.MAX_KEY_LENGTH);
    }
}
