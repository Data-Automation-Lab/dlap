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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * The enrichment listener no longer swallows failures: an exception rolls the transaction
 * back, is not acknowledged and reaches the error handler (retry, then DLT).
 */
@ExtendWith(MockitoExtension.class)
class LogEventEnrichmentListenerTest {

    private static final String EVENT = """
        {"eventId":"evt-l","changeType":"UPDATE","sourceProvider":"GCP","resultStatus":"SUCCESS",
         "eventTime":"2026-07-05T10:12:30Z","targetResource":{"id":"projects/p/datasets/d/tables/t"}}
        """;

    @Mock
    private LogEventEnricher enricher;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private Acknowledgment ack;

    private LogEventEnrichmentListener listener;

    @BeforeEach
    void setUp() {
        CatalogEventDecoder decoder = new CatalogEventDecoder(new CatalogIngestProperties(),
                new CatalogIngestMetrics(new SimpleMeterRegistry()));
        listener = new LogEventEnrichmentListener(decoder, enricher, transactionManager);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void enrichesInATransactionThenAcknowledges() {
        listener.onLogEvent(EVENT, ack);

        InOrder order = inOrder(enricher, transactionManager, ack);
        order.verify(enricher).awaitMatchIndex();
        order.verify(transactionManager).getTransaction(any());
        order.verify(enricher).enrich(any(), eq(EVENT));
        order.verify(transactionManager).commit(any());
        order.verify(ack).acknowledge();
    }

    @Test
    void anEnrichmentFailureRollsBackAndPropagates() {
        doThrow(new IllegalStateException("lineage insert failed")).when(enricher).enrich(any(), anyString());

        assertThatThrownBy(() -> listener.onLogEvent(EVENT, ack)).isInstanceOf(IllegalStateException.class);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(ack, never()).acknowledge();
    }
}
