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

package com.dlap.logingest.parse.gcpaudit;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.fixtures.gen.gcpaudit.GoogleApisDescriptors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The legacy BigQuery {@code AuditData} decoder: its shipped descriptor resource is exactly what the
 * vendored googleapis protos compile to, and an api-proto {@code serviceData {typeUrl, base64}} expands
 * to the same JSON the vendor's own message prints.
 */
class AuditDataDecoderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theShippedDescriptorResourceMatchesTheVendoredProtos() throws IOException {
        String shipped;
        try (InputStream in = AuditDataDecoder.class.getClassLoader().getResourceAsStream(AuditDataDecoder.RESOURCE)) {
            assertThat(in).as(AuditDataDecoder.RESOURCE).isNotNull();
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String regenerated = GoogleApisDescriptors.get().auditDataDescriptorsJson();
        assertThat(JSON.readTree(shipped.replace("\r\n", "\n")))
                .as("regenerate with GoogleApisDescriptors <file> after a googleapis update")
                .isEqualTo(JSON.readTree(regenerated.replace("\r\n", "\n")));
        assertThat(AuditDataDecoder.descriptor().getFullName()).isEqualTo(AuditDataDecoder.MESSAGE);
    }

    @Test
    void opaqueServiceDataExpandsToTheVendorsJson() throws IOException {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(GoogleApisDescriptors.get().message(AuditDataDecoder.MESSAGE));
        JsonFormat.parser().usingTypeRegistry(GoogleApisDescriptors.get().registry()).merge("""
                {"jobCompletedEvent":{"eventName":"query_job_completed","job":{"jobName":{"projectId":"proj-0d1a0001",
                "jobId":"bquxjob_0d1a00v1","location":"US"},"jobStatistics":{"totalBilledBytes":"10485760",
                "referencedTables":[{"projectId":"proj-0d1a0001","datasetId":"sales","tableId":"orders"}]}}}}
                """, b);
        DynamicMessage m = b.build();
        ObjectNode opaque = JSON.createObjectNode();
        opaque.put("typeUrl", GcpAuditFormat.BQ_AUDIT_DATA_TYPE);
        opaque.put("base64", Base64.getEncoder().encodeToString(m.toByteArray()));

        ObjectNode expanded = AuditDataDecoder.expand(opaque).orElseThrow();
        assertThat(expanded.path("jobCompletedEvent").path("job").path("jobStatistics").path("referencedTables")
                .get(0).path("tableId").asText()).isEqualTo("orders");
        assertThat(expanded.path("jobCompletedEvent").path("job").path("jobStatistics").path("totalBilledBytes").asText())
                .isEqualTo("10485760");
    }

    @Test
    void anEmptyPayloadExpandsToAnEmptyMessageAndGarbageDoesNot() {
        ObjectNode empty = JSON.createObjectNode().put("typeUrl", GcpAuditFormat.BQ_AUDIT_DATA_TYPE).put("base64", "");
        assertThat(AuditDataDecoder.expand(empty)).isPresent();
        ObjectNode garbage = JSON.createObjectNode().put("typeUrl", GcpAuditFormat.BQ_AUDIT_DATA_TYPE)
                .put("base64", Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, (byte) 0xff, 0x01}));
        assertThat(AuditDataDecoder.expand(garbage)).isEmpty();
        ObjectNode notBase64 = JSON.createObjectNode().put("typeUrl", GcpAuditFormat.BQ_AUDIT_DATA_TYPE)
                .put("base64", "not base64!");
        assertThat(AuditDataDecoder.expand(notBase64)).isEmpty();
        ObjectNode otherType = JSON.createObjectNode().put("typeUrl", "type.googleapis.com/google.iam.v1.logging.AuditData")
                .put("base64", "");
        assertThat(AuditDataDecoder.expand(otherType)).isEmpty();
    }
}
