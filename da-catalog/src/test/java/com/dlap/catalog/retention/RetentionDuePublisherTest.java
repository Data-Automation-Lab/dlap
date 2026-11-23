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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;

import com.dlap.catalog.retention.RetentionOutbox.Pending;
import com.dlap.common.constants.DlapConstants;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * The publisher sends the queued events in order to {@code dlap.catalog.asset.retention-due}, keyed
 * by the asset URI, and marks a row published only after the broker acknowledged it. A failed send
 * stops the run and leaves the row queued. Both singleton jobs carry a ShedLock lock (plan rule
 * R7) with a bound of at least twice the expected run.
 */
class RetentionDuePublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final RetentionOutbox outbox = mock(RetentionOutbox.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final RetentionProperties properties = new RetentionProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RetentionDuePublisher publisher = new RetentionDuePublisher(outbox, kafka, properties,
            new RetentionMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC));

    private static Pending pending(long id) {
        return new Pending(id, "event-" + id, "gs://bucket-" + id, "{\"n\":" + id + "}");
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, Object>> ok() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    private static CompletableFuture<SendResult<String, Object>> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
    }

    @Test
    void sendsInQueueOrderToTheRetentionDueTopicKeyedByTheAssetUriThenMarksThemPublished() {
        when(outbox.pending(anyInt())).thenReturn(List.of(pending(1), pending(2), pending(3)));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(ok());

        int published = publisher.publishPending();

        assertThat(published).isEqualTo(3);
        InOrder order = inOrder(kafka, outbox);
        for (long n = 1; n <= 3; n++) {
            order.verify(kafka).send(DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE, "gs://bucket-" + n,
                    "{\"n\":" + n + "}");
        }
        order.verify(outbox).markPublished(List.of(1L, 2L, 3L));
        assertThat(DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE).isEqualTo("dlap.catalog.asset.retention-due");
    }

    @Test
    void aFailedSendStopsTheRunAndMarksOnlyTheEventsTheBrokerAcknowledged() {
        when(outbox.pending(anyInt())).thenReturn(List.of(pending(1), pending(2), pending(3)));
        when(kafka.send(anyString(), eq("gs://bucket-1"), any())).thenReturn(ok());
        when(kafka.send(anyString(), eq("gs://bucket-2"), any())).thenReturn(failed());

        int published = publisher.publishPending();

        assertThat(published).isEqualTo(1);
        verify(outbox).markPublished(List.of(1L));
        verify(kafka, never()).send(anyString(), eq("gs://bucket-3"), any());
        assertThat(registry.find(RetentionMetrics.PUBLISHED).tag("outcome", "failed").counter().count()).isEqualTo(1);
        assertThat(registry.find(RetentionMetrics.PUBLISHED).tag("outcome", "published").counter().count())
                .isEqualTo(1);
    }

    @Test
    void anEmptyQueueSendsNothing() {
        when(outbox.pending(anyInt())).thenReturn(List.of());

        assertThat(publisher.publishPending()).isZero();

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    void theBatchSizeComesFromTheSettings() {
        properties.getPublish().setBatchSize(7);
        when(outbox.pending(anyInt())).thenReturn(List.of());

        publisher.publishPending();

        verify(outbox).pending(7);
    }

    @Test
    void theScheduledRunPrunesOldRowsRecordsTheBacklogAndSurvivesADatabaseOutage() {
        when(outbox.pending(anyInt())).thenReturn(List.of());
        when(outbox.backlog()).thenReturn(4L);

        publisher.scheduledPublish();

        verify(outbox).pruneBefore(NOW.minus(properties.getPublish().getOutboxRetention()));
        assertThat(registry.find(RetentionMetrics.OUTBOX_BACKLOG).gauge().value()).isEqualTo(4.0);

        when(outbox.pending(anyInt())).thenThrow(new DataAccessResourceFailureException("down"));
        publisher.scheduledPublish();
    }

    @Test
    void theScheduledJobsAreClusterWideSingletonsWithLocksBoundedAtLeastTwiceARun() throws Exception {
        Method publish = RetentionDuePublisher.class.getMethod("scheduledPublish");
        Method sweep = RetentionSweepJob.class.getMethod("scheduledSweep");

        assertThat(publish.getAnnotation(Scheduled.class)).isNotNull();
        SchedulerLock publishLock = publish.getAnnotation(SchedulerLock.class);
        assertThat(publishLock.name()).isEqualTo(RetentionDuePublisher.LOCK_NAME);
        assertThat(publishLock.lockAtMostFor()).contains("PT10M");

        assertThat(sweep.getAnnotation(Scheduled.class)).isNotNull();
        SchedulerLock sweepLock = sweep.getAnnotation(SchedulerLock.class);
        assertThat(sweepLock.name()).isEqualTo(RetentionSweepJob.LOCK_NAME);
        assertThat(sweepLock.lockAtMostFor()).contains("PT2H");
        assertThat(sweepLock.lockAtLeastFor()).isEqualTo("PT1M");
        assertThat(RetentionSweepJob.LOCK_NAME).isNotEqualTo(RetentionDuePublisher.LOCK_NAME);
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
