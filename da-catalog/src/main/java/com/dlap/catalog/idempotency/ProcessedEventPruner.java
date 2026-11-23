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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dlap.catalog.kafka.CatalogIngestProperties;

/**
 * Prunes {@code dlap_catalog.processed_event} rows older than
 * {@code dlap.catalog.ingest.processed-event.retention} (default 100 days).
 *
 * <p>Not a singleton job: every replica may run it at the same time, because each delete
 * removes a bounded batch of rows that are already past the cutoff, and deleting a row twice
 * is a no-op. So it needs no ShedLock (plan rule R7 applies to singleton jobs only).
 *
 * <p>The retention must be longer than the window in which da-logingest can replay stored
 * events ({@code dlap.logingest.retention-days}, default 90): a pruned row lets a replay of
 * that old event count its read again.
 */
@Component
@ConditionalOnProperty(name = "dlap.catalog.ingest.processed-event.prune-enabled", havingValue = "true",
        matchIfMissing = true)
public class ProcessedEventPruner {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventPruner.class);

    /** Upper bound on batches per run, so one run never holds the scheduler thread for long. */
    static final int MAX_BATCHES_PER_RUN = 100;

    private final ProcessedEventStore store;
    private final CatalogIngestProperties properties;
    private final Clock clock;

    /**
     * Creates the pruner.
     *
     * @param store      the processed-event table
     * @param properties retention and batch size
     * @param clock      the time source for the cutoff
     */
    public ProcessedEventPruner(ProcessedEventStore store, CatalogIngestProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /** Scheduled entry point; failures are logged and the next run tries again. */
    @Scheduled(fixedDelayString = "${dlap.catalog.ingest.processed-event.prune-interval:PT1H}",
            initialDelayString = "${dlap.catalog.ingest.processed-event.prune-initial-delay:PT5M}")
    public void scheduledPrune() {
        try {
            int deleted = pruneOnce();
            if (deleted > 0) {
                log.info("Pruned {} processed_event rows older than {}", deleted,
                        properties.getProcessedEvent().getRetention());
            }
        } catch (DataAccessException e) {
            log.warn("processed_event prune failed; retrying on the next run: {}", e.getMessage());
        }
    }

    /**
     * Deletes rows older than the retention in batches until a batch comes back short or
     * {@value #MAX_BATCHES_PER_RUN} batches ran.
     *
     * @return rows deleted
     */
    public int pruneOnce() {
        Duration retention = properties.getProcessedEvent().getRetention();
        int batchSize = properties.getProcessedEvent().getPruneBatchSize();
        Instant cutoff = clock.instant().minus(retention);
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int deleted = store.pruneBefore(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
