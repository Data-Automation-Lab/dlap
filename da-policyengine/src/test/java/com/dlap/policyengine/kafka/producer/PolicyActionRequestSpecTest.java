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

package com.dlap.policyengine.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.policyengine.dsl.PolicyActionRequests;
import com.dlap.policyengine.dsl.PolicyDsl;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-01: the {@code PolicyActionRequest} wire example in {@code docs/specs/policy-dsl.md}
 * ("Wire format") lists exactly the fields, in order, that an event fire writes, so the
 * documented shape cannot drift from the code again (Q-025: the section was once lost).
 */
class PolicyActionRequestSpecTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theSpecWireExampleHasExactlyTheFieldsAnEventFireWrites() throws IOException {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
        PolicyActionProducer producer = new PolicyActionProducer(kafka, new SimpleMeterRegistry(), JSON, true);
        ReflectionTestUtils.setField(producer, "policyActionEventTopic", "dlap.policies.actions");
        PolicyDsl dsl = new PolicyDslParser().parse("apiVersion: dlap/v1\nkind: Policy\n"
                + "metadata: {id: archive-stale-pii, name: Archive stale PII data}\nspec:\n"
                + "  triggers: [{type: event, lifecycle: accessed}]\n  match: {cloudProvider: GCP}\n"
                + "  action: {type: archive}\n");

        producer.send(PolicyActionRequests.forEvent(dsl, UUID.randomUUID(), Map.of("assetId", "a-1"), "accessed",
                "GCP_CLOUD_AUDIT|abc123", Instant.parse("2026-10-20T02:00:00Z")));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("dlap.policies.actions"), anyString(), payload.capture());
        Map<String, Object> written = JSON.readValue((String) payload.getValue(),
                new TypeReference<Map<String, Object>>() { });
        Map<String, Object> documented = JSON.readValue(specWireExample(),
                new TypeReference<Map<String, Object>>() { });

        assertThat(documented.keySet()).containsExactlyElementsOf(written.keySet());
        assertThat(documented).containsEntry("schemaVersion", "v1").containsEntry("triggerType", "event");
    }

    private static String specWireExample() throws IOException {
        Path spec = locate("docs/specs/policy-dsl.md");
        String text = Files.readString(spec, StandardCharsets.UTF_8);
        int section = text.indexOf("## Wire format");
        assertThat(section).as("policy-dsl.md has a Wire format section").isPositive();
        Matcher m = Pattern.compile("```json\\R(.*?)\\R```", Pattern.DOTALL).matcher(text);
        if (!m.find(section)) {
            fail("no json block after the Wire format heading of " + spec);
        }
        return m.group(1);
    }

    private static Path locate(String relative) {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return fail(relative + " not found in " + start + " or any parent directory");
    }
}
