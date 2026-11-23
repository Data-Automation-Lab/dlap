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
package com.dlap.discovery.event.service.type;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * SEC-03: the payload of a {@link LogEvent} is typed by a name, never by a class name, and a payload
 * that names something else still reads as the one type the field allows.
 */
@SuppressWarnings({"removal", "deprecation"})
class LogEventTypeIdTest {

    private static final Instant WHEN = Instant.parse("2026-10-20T10:15:30Z");

    @Test
    void theDiscriminatorIsANameNotAClass() throws Exception {
        JsonTypeInfo info = LogEvent.class.getDeclaredField("payload").getAnnotation(JsonTypeInfo.class);

        assertThat(info.use()).isEqualTo(JsonTypeInfo.Id.NAME);
        assertThat(info.property()).isEqualTo("@type");
    }

    @Test
    void aChangeSurvivesTheKafkaRoundTripUnderItsName() {
        ResourceType type = new ResourceType("aws_s3_bucket", "Bucket", new CloudService("s3", "S3", CloudProvider.AWS));
        LogEvent sent = new LogEvent("AWS", "111111111111",
                new ResourceChange("arn:aws:s3:::demo-bucket", type, ChangeType.DELETE, WHEN, "alice"));

        String json = new String(new JsonSerializer<LogEvent>().serialize("t", sent), StandardCharsets.UTF_8);
        LogEvent received = deserializer().deserialize("t", json.getBytes(StandardCharsets.UTF_8));

        assertThat(json).contains("\"@type\":\"ResourceChange\"").doesNotContain("@class");
        assertThat(received.getPayload()).isExactlyInstanceOf(ResourceChange.class);
        assertThat(received.getPayload().getResourceId()).isEqualTo("arn:aws:s3:::demo-bucket");
        assertThat(received.getPayload().getChangeType()).isEqualTo(ChangeType.DELETE);
    }

    @Test
    void thePayloadTheDormantAnalyzersWroteStillReads() {
        // It carried a class name under @class. That key is ignored now; the payload is a ResourceChange.
        String legacy = "{\"eventId\":\"e1\",\"payload\":{\"@class\":\"com.dlap.discovery.common.model.ResourceChange\","
                + "\"resourceId\":\"arn:aws:s3:::demo-bucket\",\"changeType\":\"DELETE\"}}";

        LogEvent received = deserializer().deserialize("t", legacy.getBytes(StandardCharsets.UTF_8));

        assertThat(received.getPayload()).isExactlyInstanceOf(ResourceChange.class);
        assertThat(received.getPayload().getResourceId()).isEqualTo("arn:aws:s3:::demo-bucket");
    }

    @Test
    void aTypeNameThatIsAClassNameNeverBuildsThatClass() {
        for (String hostile : new String[] {"java.lang.ProcessBuilder", "javax.script.ScriptEngineManager"}) {
            String json = "{\"eventId\":\"e1\",\"payload\":{\"@type\":\"" + hostile + "\",\"resourceId\":\"r\"}}";

            LogEvent received = deserializer().deserialize("t", json.getBytes(StandardCharsets.UTF_8));

            assertThat(received.getPayload()).as(hostile).isExactlyInstanceOf(ResourceChange.class);
        }
    }

    private static JsonDeserializer<LogEvent> deserializer() {
        JsonDeserializer<LogEvent> deserializer = new JsonDeserializer<>(LogEvent.class, false);
        deserializer.addTrustedPackages("com.dlap.discovery.event.service.type");
        deserializer.setUseTypeHeaders(false);
        return deserializer;
    }
}
