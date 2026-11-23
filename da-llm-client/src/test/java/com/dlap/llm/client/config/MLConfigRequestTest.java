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
package com.dlap.llm.client.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SEC-03: a log line that prints an {@link MLConfigRequest} must never print the provider API key,
 * and the key must still travel in the JSON the request is stored and read as.
 */
class MLConfigRequestTest {

    private static final String KEY = "synthetic-key-for-the-test";

    @Test
    void toStringDoesNotPrintTheApiKey() {
        MLConfigRequest request = MLConfigRequest.builder().providerType("openai").modelName("m").apiKey(KEY).build();

        assertThat(request.toString()).contains("providerType=openai").contains("modelName=m").doesNotContain(KEY);
    }

    @Test
    void theApiKeyStillRoundTripsThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MLConfigRequest request = MLConfigRequest.builder().providerType("openai").apiKey(KEY).build();

        MLConfigRequest read = mapper.readValue(mapper.writeValueAsString(request), MLConfigRequest.class);

        assertThat(read.getApiKey()).isEqualTo(KEY);
    }
}
