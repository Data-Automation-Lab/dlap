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

package com.dlap.autoarchival.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.dlap.autoarchival.config.KafkaReliabilityConfiguration;
import com.dlap.autoarchival.exception.PermanentArchivalException;
import com.dlap.autoarchival.proof.ArchiveProofService;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventDecodeException;
import com.dlap.proto.log.v1.DlapLogEvent;

/**
 * Reads the normalized provider log ({@code dlap.log.event.normalized}) and lets
 * {@link ArchiveProofService} mark archives {@code EXECUTED} from it, and only from it.
 *
 * <p>Own consumer group, so this reader never competes with the policy-action consumer. A record
 * that cannot be decoded is a permanent defect and goes to the dead-letter topic at once; any other
 * failure is retried with backoff by the container (manual acknowledgement, KAFKA-01).
 */
@Component
public class ArchiveProofListener {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveProofListener.class);
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();

    private final ArchiveProofService proof;

    /**
     * Creates the listener.
     *
     * @param proof the proof service
     */
    public ArchiveProofListener(ArchiveProofService proof) {
        this.proof = proof;
    }

    /**
     * Handles one normalized log event.
     *
     * @param record the record; its value is proto3 JSON text or a JSON object. The listener takes the record
     *               itself because an {@code Object} parameter receives the {@code ConsumerRecord} whether or not
     *               it is annotated {@code @Payload}, which the live harness run showed as an undecodable event
     *               for every record
     * @param ack     manual acknowledgement
     */
    @KafkaListener(
            topics = "${dlap.kafka.topics.log-event-normalized:dlap.log.event.normalized}",
            groupId = "${spring.kafka.consumer.group-id}-proof",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)
    public void onLogEvent(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        Object payload = record == null ? null : record.value();
        if (payload == null) {
            ack.acknowledge();
            return;
        }
        DlapLogEvent event;
        try {
            event = CODEC.decode(payload);
        } catch (LogEventDecodeException e) {
            // The reason code never quotes the payload; the record itself is on the dead-letter topic.
            LOG.warn("undecodable log event ({}, value class {}), sent to the dead-letter topic", e.getReason(),
                    payload.getClass().getName());
            throw new PermanentArchivalException("undecodable normalized log event: " + e.getReason(), e);
        } catch (RuntimeException e) {
            LOG.warn("undecodable log event, sent to the dead-letter topic: {}", e.getClass().getSimpleName());
            throw new PermanentArchivalException("undecodable normalized log event", e);
        }
        proof.onEvent(event);
        ack.acknowledge();
    }
}
