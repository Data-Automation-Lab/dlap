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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.core.KafkaTemplate;

/**
 * Test doubles for the template {@link DiscoveryLogEventProcessor} forwards with. The router waits
 * for the send future before it acknowledges (Q-823), so a bare mock, whose {@code send} returns
 * {@code null}, no longer stands for a healthy broker.
 */
final class RouterKafkaMocks {

    private RouterKafkaMocks() {
    }

    /**
     * A template whose every send is acknowledged at once.
     *
     * @return the mock
     */
    @SuppressWarnings("unchecked")
    static KafkaTemplate<String, Object> acceptingTemplate() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(template)
                .send(any(String.class), any(), any());
        return template;
    }
}
