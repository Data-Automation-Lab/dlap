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

package com.dlap.catalog.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The emitter's 0.1 enumerations are exactly those of the published schema, so an asset the emitter
 * calls representable never fails the schema on its platform, and none it could describe is refused.
 */
class LifecycleStateEmitterEnumsTest {

    @Test
    void theProvidersAndKindsAreTheSchemas() {
        JsonNode platform = LifecycleStateSchema.read(read()).get("properties").get("platform").get("properties");

        assertThat(LifecycleStateEmitter.PROVIDERS).isEqualTo(values(platform.get("provider").get("enum")));
        assertThat(LifecycleStateEmitter.KINDS).isEqualTo(values(platform.get("kind").get("enum")));
    }

    @Test
    void theChannelPatternIsTheSchemas() {
        JsonNode channel = LifecycleStateSchema.read(read()).get("$defs").get("provenanceEntry").get("properties")
                .get("channel").get("anyOf").get(1);

        assertThat(LifecycleStateEmitter.CHANNEL.pattern()).isEqualTo(channel.get("pattern").asText());
        assertThat(LifecycleEvidenceStore.EVENT_ID.pattern()).isEqualTo(LifecycleStateSchema.read(read())
                .get("$defs").get("eventId").get("pattern").asText());
    }

    private static Set<String> values(JsonNode array) {
        Set<String> out = new HashSet<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static String read() {
        try {
            return java.nio.file.Files.readString(LifecycleStateSchema.repoRoot().resolve(LifecycleStateSchema.SCHEMA));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
