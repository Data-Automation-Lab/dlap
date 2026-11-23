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

package com.dlap.discovery.logspine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.AcknowledgingConsumerAwareMessageListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;

import com.dlap.common.constants.DlapConstants;
import com.dlap.discovery.config.KafkaReliabilityConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Proves the {@code @KafkaListener} method itself binds both wire forms through Spring Kafka's own
 * listener adapter: the value the container's JSON deserializer produces for the legacy double
 * encoding (a {@code String}) and for the single-encoded object (a {@code Map}), and a record with
 * no value. No broker is needed.
 *
 * <p>This test exists because a first version took an {@code @Payload Object} parameter: Spring
 * Kafka offers the consumer record, the acknowledgment and the consumer as provided arguments and
 * binds any parameter they are assignable to before it looks at annotations, so an {@code Object}
 * parameter received the {@code ConsumerRecord} and every record failed to decode. Direct calls
 * to the routing method could not see that.
 */
class DiscoveryLogEventListenerBindingTest {

    private static final String EVENT = "{\"eventId\":\"e-1\",\"changeType\":\"CREATE\","
            + "\"targetResource\":{\"id\":\"s3://b\",\"canonicalUri\":\"s3://b\"}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaTemplate<String, Object> kafka;
    private Acknowledgment ack;
    private AcknowledgingConsumerAwareMessageListener<String, Object> adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        kafka = RouterKafkaMocks.acceptingTemplate();
        ack = mock(Acknowledgment.class);
        DiscoveryLogEventProcessor router =
                new DiscoveryLogEventProcessor(kafka, objectMapper, new SimpleMeterRegistry());

        DefaultMessageHandlerMethodFactory factory = new DefaultMessageHandlerMethodFactory();
        factory.afterPropertiesSet();
        Method method = DiscoveryLogEventProcessor.class.getMethod(
                "onLogEvent", ConsumerRecord.class, Acknowledgment.class);
        MethodKafkaListenerEndpoint<String, Object> endpoint = new MethodKafkaListenerEndpoint<>();
        endpoint.setBean(router);
        endpoint.setMethod(method);
        endpoint.setMessageHandlerMethodFactory(factory);
        // The endpoint builds Spring Kafka's own listener adapter and hands it to the container.
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        endpoint.setupListenerContainer(container, null);
        ArgumentCaptor<Object> listener = ArgumentCaptor.forClass(Object.class);
        verify(container).setupMessageListener(listener.capture());
        adapter = (AcknowledgingConsumerAwareMessageListener<String, Object>) listener.getValue();
    }

    @Test
    void aStringValueIsBoundAsTheLegacyDoubleEncodedEvent() {
        adapter.onMessage(new ConsumerRecord<>(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0, 41L, "k", EVENT),
                ack, mock(Consumer.class));

        ArgumentCaptor<Object> forwarded = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED), eq("s3://b"), forwarded.capture());
        assertThat(((JsonNode) forwarded.getValue()).path("eventId").asText()).isEqualTo("e-1");
        verify(ack).acknowledge();
    }

    @Test
    void aMapValueIsBoundAsTheSingleEncodedObject() throws Exception {
        Map<String, Object> value = objectMapper.readValue(EVENT, LinkedHashMap.class);

        adapter.onMessage(new ConsumerRecord<>(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0, 42L, "k", value),
                ack, mock(Consumer.class));

        ArgumentCaptor<Object> forwarded = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED), eq("s3://b"), forwarded.capture());
        assertThat((JsonNode) forwarded.getValue()).isEqualTo(objectMapper.readTree(EVENT));
        verify(ack).acknowledge();
    }

    @Test
    void theConfiguredDeserializerDeliversTheTwoWireFormsAsAStringAndAMap() throws Exception {
        // The consumer factory the listener really uses (value type Object, JSON, no type headers).
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(List.of("localhost:9092"));
        kafkaProperties.getConsumer().getProperties().put("spring.json.trusted.packages", "*");
        KafkaReliabilityConfiguration configuration =
                new KafkaReliabilityConfiguration(kafkaProperties, new StaticListableBeanFactory());
        Deserializer<Object> deserializer = configuration.discoveryManualAckConsumerFactory().getValueDeserializer();

        // What a producer that sends the event as a pre-serialized String through the JSON serializer
        // puts on the wire (a JSON string holding the event), and what one that sends the object does.
        byte[] doubleEncoded = objectMapper.writeValueAsBytes(EVENT);
        byte[] singleEncoded = EVENT.getBytes(StandardCharsets.UTF_8);
        Object fromDoubleEncoded = deserializer.deserialize(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, doubleEncoded);
        Object fromSingleEncoded = deserializer.deserialize(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, singleEncoded);

        assertThat(fromDoubleEncoded).isInstanceOf(String.class);
        assertThat(fromSingleEncoded).isInstanceOf(Map.class);
        for (Object value : new Object[] {fromDoubleEncoded, fromSingleEncoded}) {
            adapter.onMessage(new ConsumerRecord<>(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0, 45L, "k", value),
                    ack, mock(Consumer.class));
        }

        ArgumentCaptor<Object> forwarded = ArgumentCaptor.forClass(Object.class);
        verify(kafka, org.mockito.Mockito.times(2)).send(
                eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED), eq("s3://b"), forwarded.capture());
        assertThat(forwarded.getAllValues().get(0)).isEqualTo(forwarded.getAllValues().get(1));
    }

    @Test
    void aRecordWithNoValueIsDropped() {
        adapter.onMessage(new ConsumerRecord<>(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0, 44L, "k", null),
                ack, mock(Consumer.class));

        verify(kafka, org.mockito.Mockito.never()).send(any(String.class), any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void anEmptyMapIsDropped() {
        adapter.onMessage(new ConsumerRecord<>(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, 0, 43L, "k",
                new LinkedHashMap<String, Object>()), ack, mock(Consumer.class));

        verify(kafka, org.mockito.Mockito.never()).send(any(String.class), any(), any());
        verify(ack).acknowledge();
    }
}
