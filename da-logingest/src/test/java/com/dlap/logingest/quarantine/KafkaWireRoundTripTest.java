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

package com.dlap.logingest.quarantine;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.logingest.health.BindingStatusAssembler;
import com.dlap.logingest.health.BindingStatusTopicPublisher;
import com.dlap.logingest.health.HealthSnapshot.BindingSnapshot;
import com.dlap.logingest.health.LogHealthProperties;
import com.dlap.logingest.health.ReadCoverageEvaluator;
import com.dlap.logingest.metrics.ParseTelemetry;
import com.dlap.logingest.model.BindingState;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.ReadCoverage;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * The two new topics on a real broker (an embedded KRaft Kafka), with the same serializers as
 * {@code application.properties} (String key, {@code JsonSerializer} value): what a consumer reads is
 * one proto3 JSON object that the codec decodes. This is the live check that the mocked tests cannot
 * give: the double-encoding trap of {@code dlap.log.event.normalized} (a JSON string holding JSON) does
 * not repeat here.
 */
@EmbeddedKafka(partitions = 1, topics = {"dlap.log.event.quarantine", "dlap.logingest.binding.status"})
class KafkaWireRoundTripTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    private KafkaTemplate<String, Object> template(EmbeddedKafkaBroker broker) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(broker));
        props.put("enable.idempotence", true);
        props.put("acks", "all");
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props, new StringSerializer(),
                new JsonSerializer<>(new ObjectMapper())));
    }

    private ConsumerRecord<String, String> readOne(EmbeddedKafkaBroker broker, String topic) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(broker, "log03-" + topic, true));
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, topic);
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), 1);
            assertThat(records.count()).isEqualTo(1);
            return records.iterator().next();
        }
    }

    @Test
    void aQuarantineRecordCrossesTheBrokerAsOneJsonObjectKeyedByBinding(EmbeddedKafkaBroker broker) {
        KafkaTemplate<String, Object> kafka = template(broker);
        LogQuarantineRecord r = QuarantineFixtures.exact("dlap-rest-aws", "{\"user\":\"alice@example.com\"}",
                QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION, 4, T0);

        new TopicQuarantineSink(new QuarantineTopicPublisher(kafka, new ObjectMapper()), QuarantineProperties.defaults())
                .accept(r);
        kafka.flush();

        ConsumerRecord<String, String> got = readOne(broker, DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE);
        assertThat(got.key()).isEqualTo("dlap-rest-aws");
        assertThat(got.value()).startsWith("{").endsWith("}").doesNotContain("alice@example.com");
        LogQuarantineRecord back = DlapLogEventCodec.defaults().decodeMessage(got.value(),
                LogQuarantineRecord.getDefaultInstance());
        assertThat(back.getQuarantineId()).isEqualTo(r.getQuarantineId());
        assertThat(back.getReason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION);
        assertThat(back.getRawExcerpt()).contains("<email>");
        assertThat(back.getRawSha256()).isEqualTo(r.getRawSha256());
    }

    @Test
    void aBindingStatusCrossesTheBrokerAsOneJsonObjectKeyedByBinding(EmbeddedKafkaBroker broker) {
        KafkaTemplate<String, Object> kafka = template(broker);
        BindingSnapshot b = new BindingSnapshot("gcp-main", "gcp", "GCP_CLOUD_AUDIT", Optional.of(SourceProvider.GCP),
                Map.of("project-id", "dlap-demo"), BindingState.ACTIVE, true, "c".repeat(64), List.of(), List.of());
        LogBindingStatus status = new BindingStatusAssembler(LogHealthProperties.defaults()).assemble(b,
                new ParseTelemetry.WindowCounts(T0.minusSeconds(60), T0, 5, 1, 0, 0, 6, 0),
                new ReadCoverageEvaluator.Evaluation(ReadCoverage.READ_COVERAGE_NONE_OBSERVED, Optional.empty(), 0.0),
                true, T0);

        new BindingStatusTopicPublisher(kafka, new ObjectMapper()).publish(status);
        kafka.flush();

        ConsumerRecord<String, String> got = readOne(broker, DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS);
        assertThat(got.key()).isEqualTo("gcp-main");
        assertThat(got.value()).startsWith("{");
        LogBindingStatus back = DlapLogEventCodec.defaults().decodeMessage(got.value(),
                LogBindingStatus.getDefaultInstance());
        assertThat(back).isEqualTo(status);
        assertThat(back.getReadCoverage()).isEqualTo(ReadCoverage.READ_COVERAGE_NONE_OBSERVED);
    }
}
