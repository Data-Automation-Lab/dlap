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
import static org.mockito.Mockito.mock;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.PrincipalType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PARSE-02 (Q-596): the Lifecycle State emitter reads a provider's native rule from a {@code SYSTEM}
 * principal. The delete events da-logingest's parsers emit for S3 lifecycle (CloudTrail and the
 * server access log) and BigQuery table expiration, as the shared da-log-fixtures cases pin them, are
 * therefore {@code executedBy native-rule}; a person's delete in the same log is not. PARSE-03 (D36,
 * Q-654): another AWS service's delete is {@code SYSTEM} too but lacks the lifecycle signature, so it is
 * not the native rule.
 */
class ProviderLifecycleExecutedByTest {

    private static final Path CORPUS = Path.of("..", "da-log-fixtures", "src", "main", "resources", "dlap-fixtures");
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LifecycleEvidenceRecorder recorder =
            new LifecycleEvidenceRecorder(mock(LifecycleEvidenceStore.class), new LifecycleStateProperties());

    private static List<DlapLogEvent> deletes(String caseDir) throws IOException {
        JsonNode expected = JSON.readTree(Files.readString(CORPUS.resolve(caseDir).resolve("expected.json")));
        List<DlapLogEvent> out = new ArrayList<>();
        for (JsonNode e : expected.path("events")) {
            DlapLogEvent event = CODEC.decode(JSON.writeValueAsString(e));
            if (event.getChangeType() == ChangeType.DELETE) {
                out.add(event);
            }
        }
        return out;
    }

    @Test
    void theProvidersLifecycleDeletesAreExecutedByTheNativeRule() throws IOException {
        List<DlapLogEvent> all = new ArrayList<>();
        all.addAll(deletes("cloudtrail/s3-file/1.11/s3-lifecycle-expiration-and-transition"));
        all.addAll(deletes("s3accesslog/cwl-json/V_1_0/lifecycle-expiration"));
        all.addAll(deletes("gcpaudit/json/bq-auditmetadata/table-expired"));
        all.addAll(deletes("gcpaudit/api-proto/bq-auditmetadata/table-expired"));

        // PARSE-03 (Q-651): the access log's delete marker is an UPDATE now, so it is not a disposition.
        assertThat(all).extracting(DlapLogEvent::getOperation)
                .containsExactly("DeleteObject", "S3.EXPIRE.OBJECT", "InternalTableExpired", "InternalTableExpired");
        assertThat(all).allSatisfy(e -> assertThat(recorder.executedBy(e))
                .as(e.getOperation()).isEqualTo(LifecycleEvidenceRecorder.NATIVE_RULE));
    }

    @Test
    void anotherAwsServicesDeleteIsNotTheNativeRule() throws IOException {
        // Q-654: SYSTEM like every AWSService record, but without the S3 lifecycle signature.
        DlapLogEvent service = deletes("cloudtrail/s3-file/1.11/s3-aws-service-delete-not-lifecycle").get(0);

        assertThat(service.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(recorder.executedBy(service)).isNotEqualTo(LifecycleEvidenceRecorder.NATIVE_RULE)
                .isEqualTo(LifecycleEvidenceRecorder.OTHER);
        LifecycleStateProperties engine = new LifecycleStateProperties();
        engine.setEnginePrincipals(List.of(service.getPrincipal().getId()));
        assertThat(new LifecycleEvidenceRecorder(mock(LifecycleEvidenceStore.class), engine).executedBy(service))
                .as("a configured engine principal is the engine").isEqualTo(LifecycleEvidenceRecorder.ENGINE);
    }

    @Test
    void aPersonsDeleteIsNotTheNativeRule() throws IOException {
        DlapLogEvent person = deletes("s3accesslog/cwl-json/V_1_0/object-and-bucket-requests").get(0);

        assertThat(recorder.executedBy(person)).isEqualTo(LifecycleEvidenceRecorder.OTHER);
    }
}
