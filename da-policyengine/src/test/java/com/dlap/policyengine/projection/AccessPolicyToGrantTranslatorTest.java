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

package com.dlap.policyengine.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dlap.policyengine.dsl.PolicyDsl;
import com.dlap.policyengine.dsl.PolicyDslParser;

/**
 * Unit coverage of the pure {@link AccessPolicyToGrantTranslator} core
 * (ADR-0040 push-down mode): allow→grant, deny→revoke, subjects→principals,
 * actions→privileges, multi-subject/multi-action fan-out.
 */
class AccessPolicyToGrantTranslatorTest {

    private final PolicyDslParser parser = new PolicyDslParser();
    private final AccessPolicyToGrantTranslator translator = new AccessPolicyToGrantTranslator();

    private static final String ALLOW_POLICY = ""
            + "apiVersion: dlap/v1\n"
            + "kind: AccessPolicy\n"
            + "metadata: {id: access-allow, name: allow-read}\n"
            + "spec:\n"
            + "  subjects: {tiers: [PRACTITIONER], roles: [DATA_ANALYST]}\n"
            + "  actions: [read, query]\n"
            + "  match: {labels: {anyOf: [EU_RESIDENT]}}\n"
            + "  effect: allow\n";

    private static final String DENY_POLICY = ""
            + "apiVersion: dlap/v1\n"
            + "kind: AccessPolicy\n"
            + "metadata: {id: access-deny}\n"
            + "spec:\n"
            + "  subjects: {roles: [DATA_ANALYST, DATA_SCIENTIST]}\n"
            + "  actions: [export]\n"
            + "  match: {labels: {allOf: [EU_RESIDENT, PII]}}\n"
            + "  effect: deny\n";

    @Test
    void allowPolicy_producesGrantIntents() {
        PolicyDsl dsl = parser.parse(ALLOW_POLICY);
        CompiledAccessPolicy compiled = translator.translate(dsl, List.of("gs://eu/customers"));

        assertThat(compiled.policyId()).isEqualTo("access-allow");
        assertThat(compiled.effect()).isEqualTo("allow");
        assertThat(compiled.intents()).allMatch(GrantIntent::grant);
    }

    @Test
    void denyPolicy_producesRevokeIntents() {
        PolicyDsl dsl = parser.parse(DENY_POLICY);
        CompiledAccessPolicy compiled = translator.translate(dsl, List.of("gs://eu/customers"));

        assertThat(compiled.effect()).isEqualTo("deny");
        assertThat(compiled.intents()).isNotEmpty();
        assertThat(compiled.intents()).noneMatch(GrantIntent::grant);
    }

    @Test
    void subjects_mapRolesVerbatimAndTiersPrefixed() {
        PolicyDsl dsl = parser.parse(ALLOW_POLICY);
        CompiledAccessPolicy compiled = translator.translate(dsl, List.of("gs://eu/customers"));

        assertThat(compiled.intents()).extracting(GrantIntent::principal)
                .contains("DATA_ANALYST", "tier:PRACTITIONER");
    }

    @Test
    void actions_mapToPrivileges_readAndQueryBothSelect() {
        PolicyDsl dsl = parser.parse(ALLOW_POLICY);
        CompiledAccessPolicy compiled = translator.translate(dsl, List.of("gs://eu/customers"));

        // read + query both -> SELECT, so the distinct privilege set is just SELECT.
        assertThat(compiled.intents()).extracting(GrantIntent::privilege)
                .containsOnly("SELECT");
    }

    @Test
    void multipleSubjectsAndActions_fanOutAsCartesianProduct() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: AccessPolicy\n"
                + "metadata: {id: access-multi}\n"
                + "spec:\n"
                + "  subjects: {roles: [DATA_ANALYST, DATA_ENGINEER]}\n"
                + "  actions: [read, write]\n"           // -> SELECT, MODIFY (distinct)
                + "  match: {labels: {anyOf: [PII]}}\n"
                + "  effect: allow\n";
        PolicyDsl dsl = parser.parse(yaml);

        // 2 principals x 2 privileges x 2 securables = 8 intents.
        CompiledAccessPolicy compiled = translator.translate(dsl, List.of("s3://a", "s3://b"));
        assertThat(compiled.intents()).hasSize(8);
        assertThat(compiled.intents()).extracting(GrantIntent::privilege)
                .contains("SELECT", "MODIFY");
        assertThat(compiled.intents()).extracting(GrantIntent::securable)
                .contains("s3://a", "s3://b");
    }

    @Test
    void unknownAction_defaultsToSelect() {
        String yaml = ""
                + "apiVersion: dlap/v1\n"
                + "kind: AccessPolicy\n"
                + "metadata: {id: access-unknown-action}\n"
                + "spec:\n"
                + "  subjects: {roles: [AUDITOR]}\n"
                + "  actions: [teleport]\n"
                + "  match: {labels: {anyOf: [PII]}}\n"
                + "  effect: allow\n";
        PolicyDsl dsl = parser.parse(yaml);
        CompiledAccessPolicy compiled = translator.translate(dsl, List.of("s3://a"));

        assertThat(compiled.intents()).extracting(GrantIntent::privilege)
                .containsOnly(AccessPolicyToGrantTranslator.DEFAULT_PRIVILEGE);
    }

    @Test
    void noSecurables_yieldsEmptyCompilation() {
        PolicyDsl dsl = parser.parse(ALLOW_POLICY);
        CompiledAccessPolicy compiled = translator.translate(dsl, List.of());

        assertThat(compiled.isEmpty()).isTrue();
        assertThat(compiled.policyId()).isEqualTo("access-allow");
    }

    @Test
    void nonAccessPolicy_isRejected() {
        String lifecycle = ""
                + "apiVersion: dlap/v1\n"
                + "kind: Policy\n"
                + "metadata: {id: t}\n"
                + "spec:\n"
                + "  triggers: [{type: event, lifecycle: created}]\n"
                + "  match: {labels: {anyOf: [PII]}}\n"
                + "  action: {type: archive}\n";
        PolicyDsl dsl = parser.parse(lifecycle);
        assertThatThrownBy(() -> translator.translate(dsl, List.of("s3://a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AccessPolicy");
    }
}
