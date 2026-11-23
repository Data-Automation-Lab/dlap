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

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends a quarantine record to {@code dlap.log.event.quarantine}, keyed by binding id, as
 * proto3 JSON ({@code DlapLogEventCodec#encodeMessage}, log-event spec section 9).
 *
 * <p>The value goes to the shared {@code KafkaTemplate} as a map, so the template's JSON
 * serializer writes the JSON object itself. Sending the encoded text instead would be written as a
 * JSON string holding JSON, the double encoding the normalized-event topic already suffers; the
 * codec tolerates it on read, but a new topic starts clean. A failed send is counted by
 * {@code PublishFailureListener}.
 */
@Component
public class QuarantineTopicPublisher {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper json;
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    /**
     * Creates the publisher.
     *
     * @param kafkaTemplate the service's template
     * @param json          the JSON mapper
     */
    public QuarantineTopicPublisher(KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper json) {
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
    }

    /**
     * Publishes a record.
     *
     * @param record the record, already bounded
     * @throws IllegalStateException when the record cannot be encoded
     */
    public void publish(LogQuarantineRecord record) {
        Map<String, Object> payload = toPayload(record);
        kafkaTemplate.send(DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE, record.getBindingId(), payload);
    }

    /**
     * The value that is sent.
     *
     * @param record the record
     * @return the record as a JSON-compatible map
     */
    Map<String, Object> toPayload(LogQuarantineRecord record) {
        try {
            return json.readValue(codec.encodeMessage(record), MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("quarantine record " + record.getQuarantineId() + " cannot be encoded", e);
        }
    }
}
