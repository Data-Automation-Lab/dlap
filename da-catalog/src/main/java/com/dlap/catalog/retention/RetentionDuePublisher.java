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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dlap.common.constants.DlapConstants;

/**
 * Publishes the retention-due events that the sweep queued in {@link RetentionOutbox}, in queue
 * order, to {@code dlap.catalog.asset.retention-due}, keyed by the asset URI. The events are
 * written in the transaction that changes the status, so each one is sent after its change
 * committed, never for a change that rolled back. Delivery is at least once: a row is marked
 * published only after the broker acknowledged it, so a crash between the send and the mark
 * sends the event again, and consumers dedupe on {@code eventId}.
 *
 * <p>The scheduled run is a cluster-wide singleton under a ShedLock JDBC lock (plan rule R7), so
 * two replicas never interleave and the per-asset order of the queue is the order on the topic.
 * A send that fails or times out stops the run; the rows stay queued for the next run.
 */
@Component
@ConditionalOnProperty(name = "dlap.catalog.retention.publish.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionDuePublisher {

    /** Name of the ShedLock row that serializes publishing across replicas. */
    public static final String LOCK_NAME = "catalog-retention-due-publisher";

    private static final Logger log = LoggerFactory.getLogger(RetentionDuePublisher.class);

    private final RetentionOutbox outbox;
    private final KafkaTemplate<String, Object> kafka;
    private final RetentionProperties properties;
    private final RetentionMetrics metrics;
    private final Clock clock;

    /**
     * Creates the publisher.
     *
     * @param outbox     the queue
     * @param kafka      the producer (String values)
     * @param properties batch size, send timeout and outbox retention
     * @param metrics    the retention metrics
     * @param clock      the time source for pruning
     */
    public RetentionDuePublisher(RetentionOutbox outbox, KafkaTemplate<String, Object> kafka,
                                 RetentionProperties properties, RetentionMetrics metrics, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Scheduled entry point; a failure is logged and the next tick tries again. */
    @Scheduled(fixedDelayString = "${dlap.catalog.retention.publish.interval:PT15S}",
            initialDelayString = "${dlap.catalog.retention.publish.initial-delay:PT30S}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "${dlap.catalog.retention.publish.lock-at-most-for:PT10M}",
            lockAtLeastFor = "PT1S")
    public void scheduledPublish() {
        try {
            int published = publishPending();
            if (published > 0) {
                log.info("Published {} retention-due event(s)", published);
            }
            outbox.pruneBefore(clock.instant().minus(properties.getPublish().getOutboxRetention()));
            metrics.outboxBacklog(outbox.backlog());
        } catch (DataAccessException e) {
            log.warn("Retention-due publishing could not reach the database; retrying on the next run: {}",
                    e.getMessage());
        }
    }

    /**
     * Sends up to one batch of queued events, in order, and marks the acknowledged ones.
     *
     * @return how many events the broker acknowledged
     */
    public int publishPending() {
        List<RetentionOutbox.Pending> batch = outbox.pending(properties.getPublish().getBatchSize());
        List<Long> acknowledged = new ArrayList<>(batch.size());
        try {
            for (RetentionOutbox.Pending event : batch) {
                kafka.send(DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE, event.key(), event.payload())
                        .get(properties.getPublish().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                acknowledged.add(event.outboxId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Retention-due publishing was interrupted after {} of {} event(s)", acknowledged.size(),
                    batch.size());
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            metrics.published("failed", 1);
            log.warn("Retention-due event could not be sent after {} of {} event(s); it stays queued: {}",
                    acknowledged.size(), batch.size(), e.toString());
        }
        outbox.markPublished(acknowledged);
        metrics.published("published", acknowledged.size());
        return acknowledged.size();
    }
}
