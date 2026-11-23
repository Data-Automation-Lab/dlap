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

package com.dlap.policyengine.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The default {@link LoggingPolicyProjectionSink} is observe-only: it records the
 * intents it would project and never mutates external state.
 */
class LoggingPolicyProjectionSinkTest {

    private final LoggingPolicyProjectionSink sink = new LoggingPolicyProjectionSink();

    @Test
    void project_recordsTheIntentsUnchanged() {
        CompiledAccessPolicy compiled = new CompiledAccessPolicy(
                "p1", "n1", "allow",
                List.of(GrantIntent.grant("DATA_ANALYST", "SELECT", "s3://a")),
                "labels:{anyOf=[PII]}");

        sink.project(compiled);

        assertThat(sink.recordedIntents()).hasSize(1);
        assertThat(sink.recordedIntents().get(0).grant()).isTrue();
        assertThat(sink.recordedIntents().get(0).principal()).isEqualTo("DATA_ANALYST");
        assertThat(sink.isEnabled()).isFalse();
    }

    @Test
    void revoke_recordsInvertedIntents() {
        CompiledAccessPolicy compiled = new CompiledAccessPolicy(
                "p1", "n1", "allow",
                List.of(GrantIntent.grant("DATA_ANALYST", "SELECT", "s3://a")),
                "labels:{anyOf=[PII]}");

        sink.revoke(compiled);

        // A revoke of a grant-intent is logged/recorded as a revoke (grant=false).
        assertThat(sink.recordedIntents()).hasSize(1);
        assertThat(sink.recordedIntents().get(0).grant()).isFalse();
    }

    @Test
    void emptyCompilation_recordsNothing() {
        CompiledAccessPolicy compiled = new CompiledAccessPolicy(
                "p1", "n1", "allow", List.of(), "match:*");

        sink.project(compiled);

        assertThat(sink.recordedIntents()).isEmpty();
    }
}
