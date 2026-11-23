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

package com.dlap.discovery.coverage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;

/**
 * Sends observations to {@code dlap.log.event.normalized} in the single encoding (log-event.md
 * section 2: a proto3 JSON object through a {@code StringSerializer}), keyed by the root URI so
 * the observations of one subject stay in order.
 */
public class ReadCoveragePublisher {

    private static final Logger log = LoggerFactory.getLogger(ReadCoveragePublisher.class);

    private final KafkaTemplate<String, String> template;
    private final ReadCoverageEventEncoder encoder;
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();
    private final Duration sendTimeout;

    /**
     * Creates the publisher.
     *
     * @param template    a template with String key and value serializers
     * @param encoder     builds the events
     * @param sendTimeout how long to wait for the broker's acknowledgements
     */
    public ReadCoveragePublisher(KafkaTemplate<String, String> template, ReadCoverageEventEncoder encoder,
                                 Duration sendTimeout) {
        this.template = template;
        this.encoder = encoder;
        this.sendTimeout = sendTimeout;
    }

    /**
     * Sends the observations of one collector run and waits for the acknowledgements.
     *
     * @param observations the observations
     * @param observedAt   when the configuration was read
     * @return how many the broker acknowledged
     */
    public int publish(List<ReadCoverageObservation> observations, Instant observedAt) {
        List<CompletableFuture<?>> sends = new ArrayList<>(observations.size());
        for (ReadCoverageObservation o : observations) {
            String value = codec.encode(encoder.toEvent(o, observedAt));
            sends.add(template.send(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, o.rootUri(), value));
        }
        int acknowledged = 0;
        long deadline = System.nanoTime() + sendTimeout.toNanos();
        for (int i = 0; i < sends.size(); i++) {
            try {
                sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                acknowledged++;
            } catch (ExecutionException | TimeoutException e) {
                log.error("Read-coverage observation for {} was not acknowledged: {}",
                        observations.get(i).rootUri(), e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for read-coverage acknowledgements");
                break;
            }
        }
        return acknowledged;
    }
}
