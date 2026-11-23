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

package com.dlap.mcppep;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AuthZenRequestMapper} produces the exact AuthZEN §2 shape
 * (docs/specs/enforcement-contract.md).
 */
class AuthZenRequestMapperTest {

    private final AuthZenRequestMapper mapper = new AuthZenRequestMapper();

    @Test
    @SuppressWarnings("unchecked")
    void mapsToTheSection2Shape() {
        McpAccessRequest req = new McpAccessRequest(
                "svc-marketing-agent",
                List.of("DATA_ANALYST"),
                "PRACTITIONER",
                "query_table",
                "bq://project/dataset/table",
                "9f6d-asset-uuid",
                "read",
                Map.of("purpose", "campaign-analytics", "consent", true));

        Map<String, Object> out = mapper.toAuthZenRequest(req);

        // subject: type=agent, id, properties{roles, tier}
        Map<String, Object> subject = (Map<String, Object>) out.get("subject");
        assertThat(subject.get("type")).isEqualTo("agent");
        assertThat(subject.get("id")).isEqualTo("svc-marketing-agent");
        Map<String, Object> subjectProps = (Map<String, Object>) subject.get("properties");
        assertThat(subjectProps.get("roles")).isEqualTo(List.of("DATA_ANALYST"));
        assertThat(subjectProps.get("tier")).isEqualTo("PRACTITIONER");

        // action: name
        Map<String, Object> action = (Map<String, Object>) out.get("action");
        assertThat(action.get("name")).isEqualTo("read");

        // resource: type=asset, id, properties{assetUri}
        Map<String, Object> resource = (Map<String, Object>) out.get("resource");
        assertThat(resource.get("type")).isEqualTo("asset");
        assertThat(resource.get("id")).isEqualTo("9f6d-asset-uuid");
        Map<String, Object> resourceProps = (Map<String, Object>) resource.get("properties");
        assertThat(resourceProps.get("assetUri")).isEqualTo("bq://project/dataset/table");

        // context: passed through
        Map<String, Object> context = (Map<String, Object>) out.get("context");
        assertThat(context.get("purpose")).isEqualTo("campaign-analytics");
        assertThat(context.get("consent")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void omitsEmptyOptionalMembers() {
        // No assetId, no roles, no tier, no context.
        McpAccessRequest req = new McpAccessRequest(
                "anon-agent", List.of(), null, "list", "s3://bucket/key", null, "read", Map.of());

        Map<String, Object> out = mapper.toAuthZenRequest(req);

        Map<String, Object> subject = (Map<String, Object>) out.get("subject");
        assertThat(subject).doesNotContainKey("properties");

        Map<String, Object> resource = (Map<String, Object>) out.get("resource");
        assertThat(resource).doesNotContainKey("id");
        Map<String, Object> resourceProps = (Map<String, Object>) resource.get("properties");
        assertThat(resourceProps.get("assetUri")).isEqualTo("s3://bucket/key");

        assertThat(out).doesNotContainKey("context");
    }
}
