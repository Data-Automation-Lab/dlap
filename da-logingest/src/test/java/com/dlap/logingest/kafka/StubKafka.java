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

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * A broker stand-in under a real {@link KafkaTemplate}: the template calls a stub producer that
 * serializes keys and values with the real {@link StringSerializer}, keeps the bytes a broker would
 * receive, and answers the send as the test tells it (acknowledged, refused, or never). It lets a
 * test check the wire bytes of a producer and what the producer does when the broker does not
 * answer, without Docker.
 */
public final class StubKafka {

    /** What the stub broker does with a send. */
    public enum Mode {
        /** Acknowledge it. */
        ACK,
        /** Refuse it at once: the callback and the future see {@link #failure} before {@code send} returns. */
        FAIL,
        /** Refuse it a moment after {@code send} returned, as a real producer does. */
        FAIL_LATER,
        /** Never answer. */
        HANG
    }

    /** One record as the broker received it. */
    public record Wire(String topic, String key, byte[] value) {

        /**
         * The value as text.
         *
         * @return the UTF-8 text of the value bytes
         */
        public String text() {
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    private final List<Wire> received = Collections.synchronizedList(new ArrayList<>());
    private volatile Mode mode = Mode.ACK;
    private volatile int acknowledgementsLeft = Integer.MAX_VALUE;
    private volatile Exception failure = new org.apache.kafka.common.errors.NetworkException("broker is down");
    private final KafkaTemplate<String, String> template;

    /** Creates a stub in {@link Mode#ACK}. */
    public StubKafka() {
        StringSerializer serializer = new StringSerializer();
        Producer<String, String> producer = producer(serializer);
        this.template = new KafkaTemplate<>(new ProducerFactory<String, String>() {
            @Override
            public Producer<String, String> createProducer() {
                return producer;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Producer<String, String> producer(StringSerializer serializer) {
        return (Producer<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Producer.class}, (proxy, method, args) -> {
                    if ("send".equals(method.getName())) {
                        ProducerRecord<String, String> record = (ProducerRecord<String, String>) args[0];
                        Callback callback = args.length > 1 ? (Callback) args[1] : null;
                        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
                        Mode now = mode == Mode.ACK && acknowledgementsLeft-- <= 0 ? Mode.FAIL_LATER : mode;
                        switch (now) {
                            case ACK -> {
                                received.add(new Wire(record.topic(), record.key(),
                                        serializer.serialize(record.topic(), record.value())));
                                RecordMetadata meta = new RecordMetadata(new TopicPartition(record.topic(), 0), 0L, 0,
                                        0L, 0, 0);
                                future.complete(meta);
                                if (callback != null) {
                                    callback.onCompletion(meta, null);
                                }
                            }
                            case FAIL -> {
                                future.completeExceptionally(failure);
                                if (callback != null) {
                                    callback.onCompletion(null, failure);
                                }
                            }
                            case FAIL_LATER -> Thread.ofVirtual().start(() -> {
                                try {
                                    Thread.sleep(30);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                future.completeExceptionally(failure);
                                if (callback != null) {
                                    callback.onCompletion(null, failure);
                                }
                            });
                            case HANG -> {
                                // no answer
                            }
                            default -> throw new IllegalStateException();
                        }
                        return future;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type.isPrimitive() && type != void.class) {
                        return 0;
                    }
                    return null;
                });
    }

    /**
     * Sets what the next sends do.
     *
     * @param mode the behaviour
     * @return this stub
     */
    public StubKafka mode(Mode mode) {
        this.mode = mode;
        return this;
    }

    /**
     * Acknowledges this many more sends in {@link Mode#ACK}, then refuses the rest a moment after each send returns (as {@link Mode#FAIL_LATER}).
     *
     * @param sends how many sends the broker still accepts
     * @return this stub
     */
    public StubKafka failAfter(int sends) {
        this.acknowledgementsLeft = sends;
        return this;
    }

    /**
     * Sets the refusal used in {@link Mode#FAIL}.
     *
     * @param failure the error the broker answers with
     * @return this stub
     */
    public StubKafka failure(Exception failure) {
        this.failure = failure;
        return this;
    }

    /**
     * The template whose producer is the stub.
     *
     * @return a String-valued template
     */
    public KafkaTemplate<String, String> template() {
        return template;
    }

    /**
     * The acknowledged records, oldest first.
     *
     * @return a copy of the received records
     */
    public List<Wire> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    /**
     * A publisher over this stub, as the application would build it.
     *
     * @param timeout how long the publisher waits for an acknowledgement
     * @return the publisher
     */
    public LogEventPublisher publisher(Duration timeout) {
        return new LogEventPublisher(template, timeout);
    }
}
