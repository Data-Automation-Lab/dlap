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

package com.dlap.logingest.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.logingest.service.registry.Disposition;
import com.dlap.logingest.service.registry.LogEventStore;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.google.protobuf.Timestamp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The aspect around {@code LogEventStore.write}: it reads the disposition and counts a Kafka failure,
 * and it changes nothing about the result or the exception.
 */
class StoreDispositionAspectTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(LogHealthFixtures.T0);
    private final LogIngestMetricsProperties props = LogIngestMetricsProperties.defaults();
    private final LogIngestMetrics metrics = new LogIngestMetrics(meters, new ParseTelemetry(clock, props), clock,
            props, 2);
    private final LogEventRepository repository = mock(LogEventRepository.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

    private LogEventStore proxied() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AspectJProxyFactory factory = new AspectJProxyFactory(new LogEventStore(repository, tx));
        factory.setProxyTargetClass(true);
        factory.addAspect(new StoreDispositionAspect(metrics));
        return factory.getProxy();
    }

    private static DlapLogEvent event(String id) {
        return DlapLogEvent.newBuilder().setEventId(id).setBindingId("dlap-rest-aws")
                .setSourceProvider(SourceProvider.AWS).setOperation("GetObject").setChangeType(ChangeType.ACCESS)
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ).setResultStatus(ResultStatus.SUCCESS)
                .setEventTime(Timestamp.newBuilder().setSeconds(1_700_000_000L)).build();
    }

    @Test
    void aNewEventCountsAsPublishedAndAStoredOneAsDuplicate() {
        when(repository.existsById("e-new")).thenReturn(false);
        when(repository.existsById("e-old")).thenReturn(true);
        LogEventStore store = proxied();

        Disposition fresh = store.write(event("e-new"), Map.of(), false, IngestMode.STREAM, false, e -> {
        });
        Disposition dup = store.write(event("e-old"), Map.of(), false, IngestMode.STREAM, false, e -> {
        });

        assertThat(fresh).isEqualTo(Disposition.PUBLISHED);
        assertThat(dup).isEqualTo(Disposition.DUPLICATE);
        ParseTelemetry.WindowCounts counts = metrics.telemetry().peek("dlap-rest-aws");
        assertThat(counts.published()).isEqualTo(1);
        assertThat(counts.duplicates()).isEqualTo(1);
    }

    @Test
    void aKafkaFailureIsCountedAndRethrown() {
        when(repository.existsById(anyString())).thenReturn(false);
        LogEventStore store = proxied();

        assertThatThrownBy(() -> store.write(event("e1"), Map.of(), false, IngestMode.STREAM, false, e -> {
            throw new org.apache.kafka.common.errors.TimeoutException("metadata not available");
        })).isInstanceOf(org.apache.kafka.common.errors.TimeoutException.class);

        assertThat(meters.find("dlap.logingest.publish.failures").tag("topic", "dlap.log.event.normalized")
                .counter().count()).isEqualTo(1.0);
        assertThat(metrics.telemetry().peek("dlap-rest-aws").published()).isZero();
    }

    @Test
    void aFailureThatIsNotKafkaIsRethrownWithoutBeingCounted() {
        LogEventStore store = proxied();
        when(repository.existsById(anyString())).thenThrow(new IllegalStateException("database"));

        assertThatThrownBy(() -> store.write(event("e1"), Map.of(), false, IngestMode.STREAM, false, e -> {
        })).isInstanceOf(IllegalStateException.class);

        assertThat(meters.find("dlap.logingest.publish.failures").counter()).isNull();
    }

    @Test
    void theCauseChainIsSearchedForAKafkaError() {
        assertThat(StoreDispositionAspect.isKafkaFailure(new RuntimeException("x",
                new org.springframework.kafka.KafkaException("send")))).isTrue();
        assertThat(StoreDispositionAspect.isKafkaFailure(new RuntimeException("x", new IllegalStateException())))
                .isFalse();
    }
}
