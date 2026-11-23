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

package com.dlap.autodelete.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.config.KafkaReliabilityConfiguration;
import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.retire.CanonicalTarget;
import com.dlap.autodelete.retire.RetireAuditLog;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.SourceProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Q-637: the delete proof listener on a real KRaft broker, through the service's own listener container
 * factory and the real {@link ProofRecorder} and {@link DeletionProofMatcher}. The live harness run showed
 * every record arriving as a {@code ConsumerRecord} the codec did not accept, so it went to the dead-letter
 * topic and no disposition could ever be marked executed; the tests that called the listener method directly
 * could not see that. Only the repositories and the audit log are replaced.
 *
 * <p>A negative case is shown by a sentinel: a second disposition on another bucket is proved by an event sent
 * after the case under test, and the topic has one partition, so once the sentinel is executed the earlier
 * record has been through the listener, the recorder and the matcher. The recorder must also have looked the
 * earlier record up, which a dead-lettered record would not have.
 */
class DeletionProofListenerBrokerTest {

    private static final String TOPIC = "dlap.discovery.asset.deleted";
    private static final Instant APPLIED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");
    private static final List<RetireDispositionEntity> DISPOSITIONS = new CopyOnWriteArrayList<>();
    private static final RetireDispositionRepository DISPOSITION_REPO = mock(RetireDispositionRepository.class);
    private static final SoftDeleteRecordRepository RECORD_REPO = mock(SoftDeleteRecordRepository.class);
    private static final RetireAuditLog AUDIT = mock(RetireAuditLog.class);
    private static EmbeddedKafkaKraftBroker broker;

    @Configuration
    @EnableKafka
    static class ListenerConfig {

        @Bean
        ProofRecorder proofRecorder() {
            return new ProofRecorder(DISPOSITION_REPO, RECORD_REPO, AUDIT, RetireProperties.defaults(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        DeletionProofListener deletionProofListener(ProofRecorder recorder) {
            return new DeletionProofListener(recorder);
        }
    }

    @BeforeAll
    static void start() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        broker.destroy();
    }

    @BeforeEach
    void resetRepositories() {
        DISPOSITIONS.clear();
        reset(DISPOSITION_REPO, RECORD_REPO, AUDIT);
        when(DISPOSITION_REPO.findByTargetUriInAndStatusIn(anyCollection(), anyCollection())).thenAnswer(call -> {
            Collection<String> uris = call.getArgument(0);
            Collection<String> statuses = call.getArgument(1);
            return DISPOSITIONS.stream()
                    .filter(d -> uris.contains(d.getTargetUri()))
                    .filter(d -> statuses.contains(d.getStatus()))
                    .toList();
        });
        when(DISPOSITION_REPO.findByIdForUpdate(any())).thenAnswer(call -> DISPOSITIONS.stream()
                .filter(d -> d.getId().equals(call.getArgument(0))).findFirst());
        when(RECORD_REPO.findByIdForUpdate(any())).thenAnswer(call -> Optional.of(SoftDeleteRecordEntity.builder()
                .id(call.getArgument(0)).status(SoftDeleteRecordEntity.AWAITING_PROOF).build()));
    }

    private static KafkaTemplate<String, String> producer() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }

    private static RetireDispositionEntity awaitingProof(String bucket) {
        RetireDispositionEntity d = DeletionProofMatcherTest.disposition("AWS", "NATIVE_RULE", "S3_LIFECYCLE",
                "s3://" + bucket);
        DISPOSITIONS.add(d);
        return d;
    }

    private static String bucket() {
        return "q637-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** What the provider's own S3 lifecycle expiration looks like once the router has typed it. */
    private static DlapLogEvent.Builder lifecycleExpiry(String bucket, Instant when) {
        return DeletionProofMatcherTest.delete(SourceProvider.AWS, "s3://" + bucket + "/k/a.parquet", when)
                .setSourceService("s3lifecycle.amazonaws.com").setOperation("DeleteObject")
                .setSourceFormat("AWS_CLOUDTRAIL/s3-file");
    }

    /**
     * Publishes and waits for the broker's acknowledgement. Each call uses its own producer, so without the wait
     * a later record (the sentinel) can reach the partition before an earlier one (Q-649).
     */
    private static void publish(DlapLogEvent event) {
        try {
            producer().send(TOPIC, event.getEventId(), DlapLogEventCodec.defaults().encode(event))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing " + event.getEventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not publish " + event.getEventId(), e);
        }
    }

    private static void runListener(Runnable body) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                        "spring.kafka.consumer.auto-offset-reset=earliest",
                        "logging.level.org.apache.kafka=ERROR")
                .withUserConfiguration(KafkaReliabilityConfiguration.class, ListenerConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    body.run();
                });
    }

    private static void awaitExecuted(RetireDispositionEntity d) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(d.getStatus()).isEqualTo(RetireDispositionEntity.EXECUTED));
    }

    /**
     * Sends the event under test, then a sentinel proof for another bucket, and returns once the sentinel is
     * executed: by then the first record has been through the listener and the recorder.
     */
    private static void sendThenSentinel(DlapLogEvent underTest) {
        RetireDispositionEntity sentinel = awaitingProof(bucket());
        publish(underTest);
        publish(lifecycleExpiry(sentinel.getTargetUri().substring("s3://".length()), APPLIED.plus(Duration.ofHours(1)))
                .setEventId("sentinel-" + UUID.randomUUID()).build());
        awaitExecuted(sentinel);
        verify(DISPOSITION_REPO).findByTargetUriInAndStatusIn(
                eq(CanonicalTarget.selfAndParents(underTest.getTargetResource().getCanonicalUri())), anyCollection());
    }

    @Test
    void aProviderLifecycleDeleteEventMarksTheDispositionExecuted() {
        runListener(() -> {
            String bucket = bucket();
            RetireDispositionEntity d = awaitingProof(bucket);
            Instant when = APPLIED.plus(Duration.ofHours(20));
            publish(lifecycleExpiry(bucket, when).setEventId("evt-proof-q637").build());

            awaitExecuted(d);

            assertThat(d.getProofEventId()).isEqualTo("evt-proof-q637");
            assertThat(d.getProofEventTime()).isEqualTo(when);
            assertThat(d.getProofSource()).isEqualTo("AWS_CLOUDTRAIL/s3-file");
            verify(AUDIT).record(eq(RetireAuditLog.EXECUTED), eq(when), any(), eq("s3://" + bucket), any(),
                    eq(d.getSoftDeleteRecordId().toString()), any());
        });
    }

    @Test
    void aCrawlerObservationDoesNotMarkTheDispositionExecuted() {
        runListener(() -> {
            String bucket = bucket();
            RetireDispositionEntity d = awaitingProof(bucket);

            sendThenSentinel(lifecycleExpiry(bucket, APPLIED.plus(Duration.ofHours(20)))
                    .setOrigin(Origin.ORIGIN_CRAWLER).build());

            assertThat(d.getStatus()).isEqualTo(RetireDispositionEntity.AWAITING_PROOF);
            assertThat(d.getProofEventId()).isNull();
            verify(AUDIT, never()).record(any(), any(), any(), eq("s3://" + bucket), any(), any(), any());
        });
    }

    @Test
    void aPersonsDeleteDoesNotMarkTheDispositionExecuted() {
        runListener(() -> {
            String bucket = bucket();
            RetireDispositionEntity d = awaitingProof(bucket);

            sendThenSentinel(DeletionProofMatcherTest.delete(SourceProvider.AWS, "s3://" + bucket + "/k/a.parquet",
                    APPLIED.plus(Duration.ofHours(1))).setSourceService("s3.amazonaws.com").setOperation("DeleteObject")
                    .setPrincipal(Principal.newBuilder().setType(PrincipalType.USER).setId("arn:aws:iam::1:user/bob"))
                    .build());

            assertThat(d.getStatus()).isEqualTo(RetireDispositionEntity.AWAITING_PROOF);
            assertThat(d.getProofEventId()).isNull();
            verify(AUDIT, never()).record(any(), any(), any(), eq("s3://" + bucket), any(), any(), any());
        });
    }

    @Test
    void anEventEarlierThanTheApplyTimeDoesNotMarkTheDispositionExecuted() {
        runListener(() -> {
            String bucket = bucket();
            RetireDispositionEntity d = awaitingProof(bucket);

            sendThenSentinel(lifecycleExpiry(bucket, APPLIED.minus(Duration.ofDays(1))).build());

            assertThat(d.getStatus()).isEqualTo(RetireDispositionEntity.AWAITING_PROOF);
            assertThat(d.getProofEventId()).isNull();
            verify(AUDIT, never()).record(any(), any(), any(), eq("s3://" + bucket), any(), any(), any());
        });
    }
}
