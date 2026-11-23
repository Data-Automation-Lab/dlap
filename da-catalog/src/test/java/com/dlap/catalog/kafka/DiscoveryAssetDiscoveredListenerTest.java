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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.dlap.common.logevent.LogEventDecodeException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The discovered-topic listener: every payload is read through the codec (both encodings),
 * applied in one transaction with discovery publishing on, and acknowledged only after the
 * commit. Failures propagate without an acknowledgment so the error handler retries and
 * dead-letters the record.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryAssetDiscoveredListenerTest {

    private static final String CREATE = """
        {"eventId":"evt-1","changeType":"CREATE","sourceProvider":"GCP","resultStatus":"SUCCESS",
         "eventTime":"2026-06-28T12:00:00Z",
         "targetResource":{"kind":"gcs_bucket","id":"//storage.googleapis.com/projects/_/buckets/customer-pii-2024",
                           "name":"customer-pii-2024","region":"us-central1"}}
        """;

    @Mock
    private AssetEventApplier applier;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private Acknowledgment ack;

    private DiscoveryAssetDiscoveredListener listener;

    @BeforeEach
    void setUp() {
        CatalogEventDecoder decoder = new CatalogEventDecoder(new CatalogIngestProperties(),
                new CatalogIngestMetrics(new SimpleMeterRegistry()));
        listener = new DiscoveryAssetDiscoveredListener(decoder, applier, transactionManager);
    }

    @Test
    void appliesInsideATransactionAndAcknowledgesAfterTheCommit() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        listener.onMessage(CREATE, ack);

        ArgumentCaptor<CatalogEvent> event = ArgumentCaptor.forClass(CatalogEvent.class);
        InOrder order = inOrder(transactionManager, applier, ack);
        order.verify(applier).awaitMatchIndex();
        order.verify(transactionManager).getTransaction(any());
        order.verify(applier).apply(event.capture(), eq(true));
        order.verify(transactionManager).commit(any());
        order.verify(ack).acknowledge();
        assertThat(event.getValue().canonicalTarget()).get().satisfies(id -> {
            assertThat(id.uri()).isEqualTo("gs://customer-pii-2024");
            assertThat(id.cloudProvider()).isEqualTo("GCP");
            assertThat(id.serviceType()).isEqualTo("GCS_BUCKET");
        });
    }

    @Test
    void readsTheLegacyDoubleEncoding() throws Exception {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        String doubled = new ObjectMapper().writeValueAsString(new ObjectMapper().readTree(CREATE).toString());

        listener.onMessage(doubled, ack);

        ArgumentCaptor<CatalogEvent> event = ArgumentCaptor.forClass(CatalogEvent.class);
        verify(applier).apply(event.capture(), eq(true));
        assertThat(event.getValue().isLegacy()).isTrue();
        assertThat(event.getValue().eventKey()).isEqualTo("evt-1");
        verify(ack).acknowledge();
    }

    @Test
    void aFailureRollsBackPropagatesAndIsNotAcknowledged() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(applier.apply(any(), anyBoolean())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> listener.onMessage(CREATE, ack)).isInstanceOf(IllegalStateException.class);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(ack, never()).acknowledge();
    }

    @Test
    void aPayloadThatIsNotALogEventGoesToTheErrorHandlerUntouched() {
        assertThatThrownBy(() -> listener.onMessage("{\"eventTime\": 42", ack))
                .isInstanceOf(LogEventDecodeException.class);

        verify(applier, never()).apply(any(), anyBoolean());
        verify(ack, never()).acknowledge();
    }
}
