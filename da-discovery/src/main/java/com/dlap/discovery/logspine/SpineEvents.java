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

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventDecodeException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * How da-discovery puts an event on {@link DlapConstants.KafkaTopic#LOG_EVENT_NORMALIZED}
 * (SPINE-01, {@code docs/specs/log-event.md} sections 2 and 8): the producers that build an event
 * as a Jackson tree ({@code CrawlerAssetSpineBridge}, {@code LogAnalyzerSpinePublisher}) share it.
 *
 * <p><b>One encoding.</b> {@link #encode} reads the tree through {@link DlapLogEventCodec}, which
 * also maps the legacy {@code source} marker onto {@code origin} and {@code source_format}, and
 * prints the event once as canonical proto3 JSON. {@link #sendAndAwait} sends that text on the
 * String-valued template, so the value on the wire is the JSON object itself. The shared
 * {@code KafkaTemplate<String, Object>} must not carry it: its JSON serializer wraps a String in
 * quotes (the legacy double encoding).
 *
 * <p><b>Delivery is awaited.</b> {@link #sendAndAwait} returns after the broker acknowledged the
 * record and throws {@link SpinePublishException} otherwise, so the caller learns of a lost event
 * before it acknowledges the crawler event it came from.
 */
public final class SpineEvents {

    /** Default wait for the broker's acknowledgement. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** Property that bounds the wait, for example {@code 30s}. */
    public static final String PUBLISH_TIMEOUT_PROPERTY = "dlap.discovery.spine-publish-timeout";

    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();

    private SpineEvents() {
    }

    /**
     * Prints an event, given as a Jackson tree in the flat shape the spine producers build, in the
     * single wire encoding.
     *
     * @param event the event tree
     * @return proto3 JSON object text
     * @throws SpinePublishException when a field holds a value its contract type cannot take
     */
    public static String encode(JsonNode event) {
        try {
            return CODEC.encode(CODEC.decode(event));
        } catch (LogEventDecodeException e) {
            // The codec's message names the problem and the field path, never values.
            throw new SpinePublishException("event does not fit the DlapLogEvent contract: " + e.getMessage(), e);
        }
    }

    /**
     * Sends encoded event text to the normalized topic and waits for the broker's acknowledgement.
     *
     * @param template the String-valued template ({@code StringSerializer} for key and value)
     * @param key      the record key; the asset identity, so events of one asset stay ordered
     * @param json     the text from {@link #encode}
     * @param timeout  how long to wait
     * @throws SpinePublishException when the broker does not acknowledge the record in time or
     *                               refuses it
     */
    public static void sendAndAwait(KafkaTemplate<String, String> template, String key, String json,
                                    Duration timeout) {
        String topic = DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED;
        try {
            template.send(topic, key, json).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpinePublishException("interrupted while sending an event to " + topic, e);
        } catch (ExecutionException e) {
            throw new SpinePublishException("broker did not accept an event on " + topic + ": " + e.getCause(),
                    e.getCause() == null ? e : e.getCause());
        } catch (TimeoutException e) {
            throw new SpinePublishException("no acknowledgement for an event on " + topic + " within " + timeout, e);
        } catch (RuntimeException e) {
            throw new SpinePublishException("could not send an event to " + topic, e);
        }
    }
}
