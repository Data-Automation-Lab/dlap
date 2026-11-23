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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * ADR-0040 parser coverage: {@code kind: AccessPolicy} round-trips into the
 * shared {@link PolicyDsl} model, unknown kinds are rejected clearly, and the
 * pre-existing {@code kind: Policy} path is unchanged (regression).
 */
class PolicyDslParserAccessPolicyTest {

    private final PolicyDslParser parser = new PolicyDslParser();

    private static final String ACCESS_POLICY = ""
            + "apiVersion: dlap/v1\n"
            + "kind: AccessPolicy\n"
            + "metadata:\n"
            + "  id: access-eu-marketing-aggregate\n"
            + "  name: EU marketing aggregate\n"
            + "  owner: cdo-office@example.com\n"
            + "  enabled: true\n"
            + "spec:\n"
            + "  subjects:\n"
            + "    tiers: [PRACTITIONER]\n"
            + "    roles: [DATA_ANALYST]\n"
            + "  actions: [read, query]\n"
            + "  match:\n"
            + "    labels:\n"
            + "      anyOf: [EU_RESIDENT]\n"
            + "      noneOf: [LEGAL_HOLD]\n"
            + "  condition:\n"
            + "    purpose:\n"
            + "      anyOf: [campaign-analytics]\n"
            + "    consent: true\n"
            + "    region:\n"
            + "      anyOf: [eu-west1]\n"
            + "  effect: allow\n"
            + "  obligations:\n"
            + "    - type: mask\n"
            + "      labels: [PII]\n";

    @Test
    void accessPolicy_roundTripsIntoModel() {
        PolicyDsl dsl = parser.parse(ACCESS_POLICY);

        assertThat(dsl.getKind()).isEqualTo(PolicyDsl.KIND_ACCESS_POLICY);
        assertThat(dsl.getMetadata().getId()).isEqualTo("access-eu-marketing-aggregate");

        PolicyDsl.Spec spec = dsl.getSpec();
        assertThat(spec.getSubjects().getTiers()).containsExactly("PRACTITIONER");
        assertThat(spec.getSubjects().getRoles()).containsExactly("DATA_ANALYST");
        assertThat(spec.getActions()).containsExactly("read", "query");
        assertThat(spec.getEffect()).isEqualTo("allow");
        // The shared match model is reused untouched.
        assertThat(spec.getMatch()).containsKey("labels");
        assertThat(spec.getCondition().getConsent()).isTrue();
        assertThat(spec.getCondition().getPurpose().getAnyOf()).containsExactly("campaign-analytics");
        assertThat(spec.getCondition().getRegion().getAnyOf()).containsExactly("eu-west1");
        assertThat(spec.getObligations()).hasSize(1);
        assertThat(spec.getObligations().get(0).getType()).isEqualTo("mask");
        assertThat(spec.getObligations().get(0).getLabels()).containsExactly("PII");
        // AccessPolicy leaves lifecycle-only fields unset.
        assertThat(spec.getTriggers()).isNull();
        assertThat(spec.getAction()).isNull();
    }

    @Test
    void accessPolicy_roleOnlySubjectsIsValid() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: AccessPolicy\n"
                + "metadata: {id: role-only}\n"
                + "spec:\n"
                + "  subjects: {roles: [AUDITOR]}\n"
                + "  actions: [read]\n"
                + "  effect: deny\n";
        PolicyDsl dsl = parser.parse(yaml);
        assertThat(dsl.getSpec().getEffect()).isEqualTo("deny");
        assertThat(dsl.getSpec().getSubjects().getTiers()).isNull();
    }

    @Test
    void accessPolicy_missingEffectIsRejected() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: AccessPolicy\n"
                + "metadata: {id: no-effect}\n"
                + "spec:\n"
                + "  subjects: {roles: [AUDITOR]}\n"
                + "  actions: [read]\n";
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(PolicyDslParser.PolicyDslParseException.class)
                .hasMessageContaining("effect");
    }

    @Test
    void accessPolicy_noSubjectsIsRejected() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: AccessPolicy\n"
                + "metadata: {id: no-subjects}\n"
                + "spec:\n"
                + "  actions: [read]\n"
                + "  effect: allow\n";
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(PolicyDslParser.PolicyDslParseException.class)
                .hasMessageContaining("subjects");
    }

    @Test
    void accessPolicy_noActionsIsRejected() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: AccessPolicy\n"
                + "metadata: {id: no-actions}\n"
                + "spec:\n"
                + "  subjects: {roles: [AUDITOR]}\n"
                + "  effect: allow\n";
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(PolicyDslParser.PolicyDslParseException.class)
                .hasMessageContaining("actions");
    }

    @Test
    void unknownKindIsRejectedClearly() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: MysteryPolicy\n"
                + "metadata: {id: x}\n"
                + "spec: {}\n";
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(PolicyDslParser.PolicyDslParseException.class)
                .hasMessageContaining("unsupported kind")
                .hasMessageContaining("AccessPolicy");
    }

    @Test
    void kindPolicy_regressionStillParses() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: Policy\n"
                + "metadata: {id: archive-stale, name: t}\n"
                + "spec:\n"
                + "  triggers: [{type: event, lifecycle: created}]\n"
                + "  match: {labels: {anyOf: [PII]}}\n"
                + "  action: {type: archive}\n";
        PolicyDsl dsl = parser.parse(yaml);
        assertThat(dsl.getKind()).isEqualTo(PolicyDsl.KIND_POLICY);
        assertThat(dsl.getSpec().getAction().getType()).isEqualTo("archive");
        assertThat(dsl.getSpec().getTriggers()).hasSize(1);
        // AccessPolicy fields stay unset for a lifecycle policy.
        assertThat(dsl.getSpec().getSubjects()).isNull();
        assertThat(dsl.getSpec().getEffect()).isNull();
    }

    @Test
    void kindPolicy_missingActionStillRejected() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: Policy\n"
                + "metadata: {id: t}\n"
                + "spec:\n"
                + "  triggers: [{type: event, lifecycle: created}]\n";
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(PolicyDslParser.PolicyDslParseException.class)
                .hasMessageContaining("spec.action.type");
    }
}
