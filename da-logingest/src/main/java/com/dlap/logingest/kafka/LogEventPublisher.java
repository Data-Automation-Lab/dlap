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

package com.dlap.logingest.kafka;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.kafka.DlapKafkaFactoryBuilder;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventDecodeException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.stereotype.Component;

/**
 * Publishes normalized events to {@link DlapConstants.KafkaTopic#LOG_EVENT_NORMALIZED} in the one
 * wire encoding of {@code docs/specs/log-event.md} section 2 (SPINE-01).
 *
 * <p><b>One encoding.</b> The event is printed once, by {@link DlapLogEventCodec#encode}, and the
 * text is sent through a {@code StringSerializer}. Sending a String through the service's shared
 * JSON template would wrap it in quotes (the legacy double encoding, spec section 2), so this
 * class owns a String-valued template built by the shared {@link DlapKafkaFactoryBuilder} from the
 * same {@code spring.kafka.*} properties as every other client of the service: security, SASL,
 * idempotence and {@code acks=all} included. A {@link Map} is read through the codec first, so
 * every caller's output reaches the topic as canonical proto3 JSON whatever shape it built.
 *
 * <p><b>Delivery is awaited.</b> {@link #publish} returns only after the broker acknowledged the
 * record, and throws {@link LogEventPublishException} when it did not. The callers store the event
 * in the same transaction, so a failed send rolls the row back (a retry is then not mistaken for a
 * stored duplicate and never republished), and the stream runner leaves its cursor where it was.
 * Before SPINE-01 the send was fire and forget: a broker outage left rows in the replay store, the
 * cursor moved on, and the events never reached the spine.
 *
 * <p>The wait is bounded by {@value #PUBLISH_TIMEOUT_PROPERTY} (default {@value #DEFAULT_TIMEOUT}).
 * A timeout can leave the record in flight; the event id is deterministic and every consumer is
 * idempotent per event id, so a later duplicate is harmless.
 */
@Component
public class LogEventPublisher implements DisposableBean {

    /** Property that bounds the wait for the broker's acknowledgement, for example {@code 30s}. */
    public static final String PUBLISH_TIMEOUT_PROPERTY = "dlap.logingest.publish-timeout";

    /** Default of {@value #PUBLISH_TIMEOUT_PROPERTY}. */
    public static final String DEFAULT_TIMEOUT = "30s";

    /**
     * Producer settings this class wants unless {@code spring.kafka.producer.properties.*} says
     * otherwise. {@code linger.ms=0}: each send is awaited, so the producer's default wait of
     * 5 ms (Kafka 4) to fill a batch that never fills would be added to every event.
     */
    static final Map<String, Object> PRODUCER_DEFAULTS = Map.of(ProducerConfig.LINGER_MS_CONFIG, 0);

    private static final Logger log = LoggerFactory.getLogger(LogEventPublisher.class);

    private final KafkaTemplate<String, String> template;
    private final Duration timeout;
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    /**
     * Creates the publisher with its own String-valued producer, built from the service's Kafka
     * properties by the shared factory builder. The service's {@code ProducerListener}, if any,
     * is attached, so failed sends are counted as for every other producer of the service.
     *
     * @param kafkaProperties the bound {@code spring.kafka.*} properties
     * @param beanFactory     the context, for the connection details and the factory customizers
     * @param listener        the service's producer listener, when one is defined
     * @param timeout         how long {@link #publish} waits for the acknowledgement
     */
    @Autowired
    public LogEventPublisher(KafkaProperties kafkaProperties, BeanFactory beanFactory,
                             ObjectProvider<ProducerListener<Object, Object>> listener,
                             @Value("${" + PUBLISH_TIMEOUT_PROPERTY + ":" + DEFAULT_TIMEOUT + "}") Duration timeout) {
        this(stringTemplate(DlapKafkaFactoryBuilder.of(kafkaProperties, beanFactory), listener.getIfAvailable()),
                timeout);
    }

    /**
     * Creates the publisher over a ready template, for tests.
     *
     * @param template a template whose key and value serializers are String serializers
     * @param timeout  how long {@link #publish} waits for the acknowledgement
     */
    LogEventPublisher(KafkaTemplate<String, String> template, Duration timeout) {
        this.template = template;
        this.timeout = timeout;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KafkaTemplate<String, String> stringTemplate(DlapKafkaFactoryBuilder kafka,
                                                                ProducerListener<Object, Object> listener) {
        KafkaTemplate<String, String> template = kafka.kafkaTemplate(
                kafka.<String, String>producerFactory(PRODUCER_DEFAULTS, StringSerializer.class, StringSerializer.class));
        if (listener != null) {
            template.setProducerListener((ProducerListener) listener);
        }
        return template;
    }

    /**
     * Publishes a normalized event and waits for the broker's acknowledgement. The
     * {@code partitionKey} is the target resource id (or the event id) so events for the same
     * resource land on the same partition (consumer-side causal-order guarantee).
     *
     * @param partitionKey    the record key
     * @param normalizedEvent the event as a map of its wire JSON: the canonical form
     *                        ({@code DlapLogEventCodec.toMap}) or the legacy flat form
     * @throws LogEventPublishException when the event does not encode, or the broker does not
     *                                  acknowledge it within the timeout
     */
    public void publish(String partitionKey, Map<String, Object> normalizedEvent) {
        String eventId = String.valueOf(normalizedEvent.get("eventId"));
        String json;
        try {
            json = codec.encode(codec.decode(normalizedEvent));
        } catch (LogEventDecodeException e) {
            // The decoder's message names the problem and the field, never values.
            log.error("Log event eventId={} does not fit the DlapLogEvent contract: {}", eventId, e.getMessage());
            throw new LogEventPublishException("log event " + eventId + " does not encode: " + e.getMessage(), e);
        }
        send(partitionKey, json, eventId);
        log.debug("Published log event eventId={} key={}", eventId, partitionKey);
    }

    private void send(String partitionKey, String json, String eventId) {
        String topic = DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED;
        try {
            template.send(topic, partitionKey, json).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LogEventPublishException("interrupted while sending log event " + eventId + " to " + topic, e);
        } catch (ExecutionException | org.springframework.kafka.KafkaException e) {
            // The broker refused the record. KafkaTemplate runs the ProducerListener first on both
            // routes (the future fails later: ExecutionException; the future was already done when
            // send returned: Spring's KafkaException "Send failed"), and PublishFailureListener
            // counts and logs it. The Kafka error stays out of the cause chain, which
            // StoreDispositionAspect walks to count a failure, so one failed send is counted once;
            // the error is kept as a suppressed exception for the stack trace.
            Throwable broker = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            LogEventPublishException failure = new LogEventPublishException("broker did not accept log event "
                    + eventId + " on " + topic + ": " + broker, null);
            failure.addSuppressed(broker);
            throw failure;
        } catch (TimeoutException e) {
            // Nothing counted this one: the cause is a Kafka timeout, so the aspect counts it.
            throw new LogEventPublishException("no acknowledgement for log event " + eventId + " on " + topic
                    + " within " + timeout, new org.apache.kafka.common.errors.TimeoutException(
                    "no acknowledgement within " + timeout));
        } catch (RuntimeException e) {
            // The producer threw before it took the record (for example a serialization error): no
            // listener ran, and the Kafka error stays in the chain so the aspect counts it.
            throw new LogEventPublishException("could not send log event " + eventId + " to " + topic, e);
        }
    }

    /**
     * The template the events go through, for tests of its configuration.
     *
     * @return the String-valued template
     */
    KafkaTemplate<String, String> template() {
        return template;
    }

    @Override
    public void destroy() {
        template.destroy();
    }
}
