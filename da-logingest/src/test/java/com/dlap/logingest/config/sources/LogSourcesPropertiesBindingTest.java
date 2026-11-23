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

package com.dlap.logingest.config.sources;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.config.sources.LogSourcesProperties.Binding;
import org.junit.jupiter.api.Test;

/**
 * The bindings file binds as design B2 writes it: nested sources, bindings and streams, the
 * {@code stream:} shorthand, quoted versions, dotted canonical field names kept whole as override
 * keys, location maps and operation-override rows.
 */
class LogSourcesPropertiesBindingTest {

    static final String DESIGN_B2 = """
            dlap:
              logingest:
                config-mode: FILE
                defaults: { on-mismatch: PARSE_WITH_DETECTED, on-unknown-major: QUARANTINE, overlap: PT20M }
                sources:
                  - id: aws-org
                    kind: TEST_AWS
                    bindings:
                      - id: aws-111111111111
                        scope: { account-id: "111111111111", regions: [us-east-1, eu-west-1] }
                        stream:
                          transport: S3_TRAIL_FILES
                          location: { bucket: org-trail, prefix: "AWSLogs/o-abc123/111111111111/CloudTrail/" }
                        format: { envelope: s3-file, version: auto }
                      - id: aws-222222222222
                        scope: { account-id: "222222222222", regions: [us-east-1] }
                        stream: { transport: LOOKUP_EVENTS }
                        format: { envelope: lookup-events, version: "1.08", on-mismatch: QUARANTINE }
                        operation-overrides:
                          - { service: s3.amazonaws.com, operation: PutObject, change-type: UPDATE, category: DATA_WRITE }
                  - id: gcp
                    kind: TEST_GCP
                    bindings:
                      - id: gcp-p2
                        scope: { project-id: my-project-2 }
                        stream: { transport: LOGGING_API }
                        format: { envelope: api-json, version: auto }
                        field-overrides: { principal.display-name: /protoPayload/authenticationInfo/principalSubject }
            """;

    @Test
    void theDesignExampleBinds() {
        LogSourcesProperties p = TestFamilies.bind(DESIGN_B2);

        assertThat(p.getConfigMode()).isEqualTo("FILE");
        assertThat(p.getDefaults().getOverlap()).isEqualTo("PT20M");
        assertThat(p.getDefaults().getOnUnknownMajor()).isEqualTo("QUARANTINE");
        assertThat(p.getSources()).hasSize(2);
        Binding pinned = p.getSources().get(0).getBindings().get(1);
        assertThat(pinned.getScope().getAccountId()).isEqualTo("222222222222");
        assertThat(pinned.getScope().getRegions()).containsExactly("us-east-1");
        assertThat(pinned.getFormat().getVersion()).isEqualTo("1.08");
        assertThat(pinned.getFormat().getOnMismatch()).isEqualTo("QUARANTINE");
        assertThat(pinned.getStream().getTransport()).isEqualTo("LOOKUP_EVENTS");
        assertThat(pinned.getOperationOverrides()).singleElement()
                .satisfies(o -> assertThat(o.getChangeType()).isEqualTo("UPDATE"));
        Binding trail = p.getSources().get(0).getBindings().get(0);
        assertThat(trail.getStream().getLocation()).containsEntry("bucket", "org-trail")
                .containsEntry("prefix", "AWSLogs/o-abc123/111111111111/CloudTrail/");
        Binding gcp = p.getSources().get(1).getBindings().get(0);
        assertThat(gcp.getFieldOverrides())
                .containsEntry("principal.display-name", "/protoPayload/authenticationInfo/principalSubject");
    }

    @Test
    void theDesignExampleValidatesAndResolves() {
        LogSourceConfigValidator.Result r = TestFamilies.validate(DESIGN_B2);

        assertThat(r.report().errors()).isEmpty();
        assertThat(r.bindings()).extracting(EffectiveBinding::id)
                .containsExactly("aws-111111111111", "aws-222222222222", "gcp-p2");
        EffectiveStream lookup = r.bindings().get(1).streams().get(0);
        assertThat(lookup.streamId()).isEqualTo("lookup-events");
        assertThat(lookup.parse().pinnedVersion()).contains("1.08");
        assertThat(lookup.parse().onMismatch().name()).isEqualTo("QUARANTINE");
        assertThat(lookup.overlap()).hasMinutes(20);
        assertThat(lookup.parse().operationOverrides()).hasSize(1);
        EffectiveStream trail = r.bindings().get(0).streams().get(0);
        assertThat(trail.parse().pinnedVersion()).isEmpty();
        assertThat(trail.parse().onMismatch().name()).isEqualTo("PARSE_WITH_DETECTED");
        assertThat(trail.parse().assetIdHints().account()).isEqualTo("111111111111");
        assertThat(r.bindings().get(2).streams().get(0).parse().fieldOverrides()).hasSize(1);
    }
}
