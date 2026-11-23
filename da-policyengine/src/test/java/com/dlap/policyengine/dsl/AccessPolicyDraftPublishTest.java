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

package com.dlap.policyengine.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dlap.policyengine.model.PolicyDraft;

/**
 * ADR-0040 (C2b): a {@code kind: AccessPolicy} draft publishes into a document
 * the PDP loads. {@code PolicyService.convertDraftToPolicy} branches on
 * {@code draft.getKind()} and drives {@link PolicyDslTranslator#toYamlAccessPolicy}
 * from exactly the draft fields exercised here; this test reproduces that
 * branch's mapping and asserts the result parses as an AccessPolicy.
 */
class AccessPolicyDraftPublishTest {

    private final PolicyDslTranslator translator = new PolicyDslTranslator();
    private final PolicyDslParser parser = new PolicyDslParser();

    /** Mirror of the service's AccessPolicy publish branch (convertDraftToPolicy). */
    private String publishDocumentFor(PolicyDraft draft) {
        return translator.toYamlAccessPolicy(
                null,
                draft.getName(),
                draft.getDescription(),
                null,
                true,
                draft.getRulesDefinition(),
                draft.getAccessSubjects(),
                draft.getAccessActions(),
                draft.getAccessCondition(),
                draft.getAccessEffect(),
                draft.getAccessObligations());
    }

    @Test
    void accessPolicyDraftPublishesToPdpLoadableDocument() {
        PolicyDraft draft = new PolicyDraft();
        draft.setName("EU marketing aggregate");
        draft.setDescription("aggregate analytics under consent");
        draft.setKind(PolicyDsl.KIND_ACCESS_POLICY);
        draft.setRulesDefinition(List.of(
                Map.of("field", "labels", "operator", "anyOf", "value", "EU_RESIDENT")));
        draft.setAccessSubjects(Map.of(
                "tiers", List.of("PRACTITIONER"),
                "roles", List.of("DATA_ANALYST")));
        draft.setAccessActions(List.of("read", "query"));
        draft.setAccessCondition(Map.of(
                "purpose", List.of("campaign-analytics"),
                "consent", true));
        draft.setAccessEffect("allow");
        draft.setAccessObligations(List.of(
                Map.of("type", "mask", "labels", List.of("PII"))));

        String doc = publishDocumentFor(draft);

        // The published document must load through the same parser the PDP's
        // refresh() uses; ENABLED + kind:AccessPolicy is what gets cached.
        PolicyDsl dsl = parser.parse(doc);
        assertThat(dsl.getKind()).isEqualTo(PolicyDsl.KIND_ACCESS_POLICY);
        assertThat(dsl.getSpec().getSubjects().getTiers()).containsExactly("PRACTITIONER");
        assertThat(dsl.getSpec().getActions()).containsExactly("read", "query");
        assertThat(dsl.getSpec().getEffect()).isEqualTo("allow");
        assertThat(dsl.getSpec().getObligations().get(0).getType()).isEqualTo("mask");
        assertThat(dsl.getSpec().getCondition().getConsent()).isTrue();
        // metadata.id is slugged from the draft name.
        assertThat(dsl.getMetadata().getId()).isEqualTo("eu-marketing-aggregate");
    }

    @Test
    void draftDefaultsToLifecycleKind() {
        // A freshly-constructed draft is a lifecycle Policy unless set otherwise:
        // the service's branch (KIND_ACCESS_POLICY.equals) takes the lifecycle path.
        assertThat(new PolicyDraft().getKind()).isEqualTo(PolicyDsl.KIND_POLICY);
    }
}
