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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * ADR-0040 (C2b): the translator turns the visual builder's structured
 * AccessPolicy input into a canonical {@code kind: AccessPolicy} DSL document.
 * Every output must re-parse through {@link PolicyDslParser} (the PDP's load
 * path), so these tests assert the structured → YAML → parsed round-trip and
 * the specific field mappings the enforcement contract (§4) defines.
 */
class PolicyDslTranslatorAccessPolicyTest {

    private final PolicyDslTranslator translator = new PolicyDslTranslator();
    private final PolicyDslParser parser = new PolicyDslParser();

    @Test
    void structuredAccessInputProducesReParseableCanonicalDsl() {
        List<Map<String, Object>> match = List.of(
                Map.of("field", "labels", "operator", "anyOf", "value", "EU_RESIDENT"),
                Map.of("field", "labels", "operator", "noneOf", "value", "LEGAL_HOLD"));
        Map<String, Object> subjects = Map.of(
                "tiers", List.of("PRACTITIONER"),
                "roles", List.of("DATA_ANALYST"));
        List<String> actions = List.of("read", "query");
        Map<String, Object> condition = Map.of(
                "purpose", List.of("campaign-analytics"),
                "consent", true,
                "region", List.of("eu-west1"));
        List<Map<String, Object>> obligations = List.of(
                Map.of("type", "mask", "labels", List.of("PII")));

        String yaml = translator.toYamlAccessPolicy(
                "access-eu-marketing-aggregate",
                "EU marketing data: aggregate analytics under consent",
                "Permit aggregate analytics under consent",
                "cdo-office@example.com",
                true, match, subjects, actions, condition, "allow", obligations);

        // The PDP load path must accept it - otherwise the policy would never fire.
        PolicyDsl dsl = parser.parse(yaml);
        assertThat(dsl.getApiVersion()).isEqualTo("dlap/v1");
        assertThat(dsl.getKind()).isEqualTo(PolicyDsl.KIND_ACCESS_POLICY);
        assertThat(dsl.getMetadata().getId()).isEqualTo("access-eu-marketing-aggregate");

        PolicyDsl.Spec spec = dsl.getSpec();
        assertThat(spec.getSubjects().getTiers()).containsExactly("PRACTITIONER");
        assertThat(spec.getSubjects().getRoles()).containsExactly("DATA_ANALYST");
        assertThat(spec.getActions()).containsExactly("read", "query");
        assertThat(spec.getEffect()).isEqualTo("allow");

        // The asset selector reuses the shared match builder (labels are the join point).
        Map<String, Object> labels = asMap(spec.getMatch().get("labels"));
        assertThat(labels.get("anyOf")).isEqualTo(List.of("EU_RESIDENT"));
        assertThat(labels.get("noneOf")).isEqualTo(List.of("LEGAL_HOLD"));

        assertThat(spec.getCondition().getPurpose().getAnyOf()).containsExactly("campaign-analytics");
        assertThat(spec.getCondition().getConsent()).isTrue();
        assertThat(spec.getCondition().getRegion().getAnyOf()).containsExactly("eu-west1");

        assertThat(spec.getObligations()).hasSize(1);
        assertThat(spec.getObligations().get(0).getType()).isEqualTo("mask");
        assertThat(spec.getObligations().get(0).getLabels()).containsExactly("PII");

        // Lifecycle-only fields stay unset on an AccessPolicy.
        assertThat(spec.getTriggers()).isNull();
        assertThat(spec.getAction()).isNull();
    }

    @Test
    void denyEffectRoleOnlySubjectsMinimalIsValid() {
        // No condition, no obligations, roles-only subjects - the deny floor
        // (deny-cross-region-export.yaml) shape.
        String yaml = translator.toYamlAccessPolicy(
                null, "Deny EU export", null, null, true,
                List.of(Map.of("field", "labels", "operator", "allOf", "value", "EU_RESIDENT,PII")),
                Map.of("roles", List.of("DATA_ANALYST", "DATA_SCIENTIST", "DATA_ENGINEER")),
                List.of("export"),
                null, "deny", null);

        PolicyDsl dsl = parser.parse(yaml);
        assertThat(dsl.getKind()).isEqualTo(PolicyDsl.KIND_ACCESS_POLICY);
        assertThat(dsl.getSpec().getEffect()).isEqualTo("deny");
        assertThat(dsl.getSpec().getSubjects().getTiers()).isNull();
        assertThat(dsl.getSpec().getSubjects().getRoles())
                .containsExactly("DATA_ANALYST", "DATA_SCIENTIST", "DATA_ENGINEER");
        assertThat(dsl.getSpec().getActions()).containsExactly("export");
        assertThat(asMap(dsl.getSpec().getMatch().get("labels")).get("allOf"))
                .isEqualTo(List.of("EU_RESIDENT", "PII"));
        // Metadata id is slugged from the name when not provided.
        assertThat(dsl.getMetadata().getId()).isEqualTo("deny-eu-export");
        assertThat(dsl.getSpec().getCondition()).isNull();
        assertThat(dsl.getSpec().getObligations()).isNull();
    }

    @Test
    void effectIsNormalisedCaseInsensitively() {
        String yaml = translator.toYamlAccessPolicy(
                "x", "X", null, null, true, null,
                Map.of("roles", List.of("AUDITOR")), List.of("read"),
                null, "ALLOW", null);
        assertThat(parser.parse(yaml).getSpec().getEffect()).isEqualTo("allow");
    }

    @Test
    void missingSubjectsIsRejectedRatherThanInvented() {
        assertThatThrownBy(() -> translator.toYamlAccessPolicy(
                "x", "X", null, null, true, null,
                Map.of(), List.of("read"), null, "allow", null))
                .isInstanceOf(PolicyDslTranslator.PolicyTranslationException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void missingActionsIsRejected() {
        assertThatThrownBy(() -> translator.toYamlAccessPolicy(
                "x", "X", null, null, true, null,
                Map.of("roles", List.of("AUDITOR")), List.of(), null, "allow", null))
                .isInstanceOf(PolicyDslTranslator.PolicyTranslationException.class)
                .hasMessageContaining("action");
    }

    @Test
    void invalidEffectIsRejected() {
        assertThatThrownBy(() -> translator.toYamlAccessPolicy(
                "x", "X", null, null, true, null,
                Map.of("roles", List.of("AUDITOR")), List.of("read"), null, "maybe", null))
                .isInstanceOf(PolicyDslTranslator.PolicyTranslationException.class)
                .hasMessageContaining("effect");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertThat(o).isInstanceOf(Map.class);
        return (Map<String, Object>) o;
    }
}
