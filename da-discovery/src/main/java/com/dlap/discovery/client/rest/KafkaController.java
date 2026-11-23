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

package com.dlap.discovery.client.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.discovery.common.service.DiscoveryKafkaProducer;

@RestController
@RequestMapping("/api/kafka")
public class KafkaController {

    private final DiscoveryKafkaProducer kafkaProducer;

    public KafkaController(DiscoveryKafkaProducer kafkaProducer1) {
        this.kafkaProducer = kafkaProducer1;
    }

    /** The body of {@code POST /api/kafka/publish}. */
    public static class KafkaMessageRequest {

        /** Longest topic name Kafka accepts. */
        public static final int MAX_TOPIC_LENGTH = 249;

        /** Largest message the endpoint relays (characters). */
        public static final int MAX_MESSAGE_LENGTH = 100_000;

        @NotBlank
        @Size(max = MAX_TOPIC_LENGTH)
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "must contain only letters, digits, '.', '_' and '-'")
        private String topic;

        @NotNull
        @Size(max = MAX_MESSAGE_LENGTH)
        private String message;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PostMapping("/publish")
    public ResponseEntity<String> publish(@Valid @RequestBody KafkaMessageRequest request) {
        kafkaProducer.sendMessage(request.getTopic(), request.getMessage());
        return ResponseEntity.ok("Message sent to topic: " + request.getTopic());
    }
}
