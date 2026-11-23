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

package com.dlap.catalog.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.BiConsumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Every typed lifecycle topic goes through the same path: codec, one transaction, the applier
 * with discovery publishing off, and an acknowledgment after the commit.
 */
@ExtendWith(MockitoExtension.class)
class AssetLifecycleEventListenerTest {

    private static final String ACCESS = """
        {"eventId":"evt-a","changeType":"ACCESS","sourceProvider":"AWS","resultStatus":"SUCCESS",
         "eventTime":"2026-07-05T10:10:00Z","operation":"GetObject",
         "targetResource":{"id":"s3://dlap-aws-e2e-finance"}}
        """;

    @Mock
    private AssetEventApplier applier;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private Acknowledgment ack;

    private AssetLifecycleEventListener listener;

    @BeforeEach
    void setUp() {
        CatalogEventDecoder decoder = new CatalogEventDecoder(new CatalogIngestProperties(),
                new CatalogIngestMetrics(new SimpleMeterRegistry()));
        listener = new AssetLifecycleEventListener(decoder, applier, transactionManager);
    }

    @Test
    void everyTopicAppliesInATransactionThenAcknowledges() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        BiConsumer<String, Acknowledgment>[] topics = handlers();

        for (BiConsumer<String, Acknowledgment> topic : topics) {
            topic.accept(ACCESS, ack);
        }

        verify(applier, times(topics.length)).apply(any(), eq(false));
        verify(transactionManager, times(topics.length)).commit(any());
        verify(ack, times(topics.length)).acknowledge();
        InOrder order = inOrder(applier, transactionManager, ack);
        order.verify(applier).awaitMatchIndex();
        order.verify(transactionManager).getTransaction(any());
        order.verify(applier).apply(any(), eq(false));
        order.verify(transactionManager).commit(any());
        order.verify(ack).acknowledge();
    }

    @Test
    void aFailureIsNotAcknowledged() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(applier.apply(any(), anyBoolean())).thenThrow(new IllegalStateException("lock timeout"));

        assertThatThrownBy(() -> listener.onAccessed(ACCESS, ack)).isInstanceOf(IllegalStateException.class);

        verify(transactionManager).rollback(any());
        verify(ack, never()).acknowledge();
    }

    @SuppressWarnings("unchecked")
    private BiConsumer<String, Acknowledgment>[] handlers() {
        return new BiConsumer[] {
            (BiConsumer<String, Acknowledgment>) listener::onModified,
            (BiConsumer<String, Acknowledgment>) listener::onDeleted,
            (BiConsumer<String, Acknowledgment>) listener::onAccessed,
            (BiConsumer<String, Acknowledgment>) listener::onPermissionChanged,
            (BiConsumer<String, Acknowledgment>) listener::onArchived
        };
    }
}
