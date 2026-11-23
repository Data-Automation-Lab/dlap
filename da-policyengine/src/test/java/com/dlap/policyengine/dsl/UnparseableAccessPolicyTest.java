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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.policyengine.dsl.UnparseableAccessPolicy.Classification;
import com.dlap.policyengine.dsl.UnparseableAccessPolicy.Disposition;

/**
 * POL-03 (Q-842): how a stored document the strict parser rejects is read again for the PDP. Only
 * an effect that reads allow is skipped and only a lifecycle kind is ignored; everything else is an
 * invalid deny with the selectors that can be read.
 */
class UnparseableAccessPolicyTest {

    private static final UUID ROW = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    private static String doc(String spec) {
        return "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: p-1}\nspec:\n" + spec;
    }

    @Test
    void aDenyWithBothSelectorsKeepsBoth() {
        Classification c = UnparseableAccessPolicy.classify(ROW,
                doc("  subjects: {roles: [A], tiers: [READER]}\n  match: {labels: {anyOf: [PII]}}\n  effect: deny\n"));

        assertThat(c.disposition()).isEqualTo(Disposition.INVALID_DENY);
        assertThat(c.policyId()).isEqualTo("p-1");
        assertThat(c.scopeText()).isEqualTo("subjects+resources");
        assertThat(c.scope().getKind()).isEqualTo(PolicyDsl.KIND_ACCESS_POLICY);
        assertThat(c.scope().getSpec().getEffect()).isEqualTo(PolicyDsl.EFFECT_DENY);
        assertThat(c.scope().getSpec().getSubjects().getRoles()).containsExactly("A");
        assertThat(c.scope().getSpec().getSubjects().getTiers()).containsExactly("READER");
        assertThat(c.scope().getSpec().getMatch()).isEqualTo(Map.of("labels", Map.of("anyOf", List.of("PII"))));
        assertThat(PolicyDslEvaluator.isDenyAccessPolicy(c.scope())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"allow", "Allow", " ALLOW "})
    void anEffectThatReadsAllowIsSkipped(String effect) {
        Classification c = UnparseableAccessPolicy.classify(ROW, doc("  subjects: {roles: [A]}\n  effect: '" + effect
                + "'\n"));

        assertThat(c.disposition()).isEqualTo(Disposition.SKIPPED_ALLOW);
        assertThat(c.scope()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"  effect: denny\n", "  effect: [deny]\n", "  effect: true\n", ""})
    void anEffectThatCannotBeReadIsAnInvalidDeny(String effectLine) {
        Classification c = UnparseableAccessPolicy.classify(ROW, doc("  subjects: {roles: [A]}\n" + effectLine));

        assertThat(c.disposition()).isEqualTo(Disposition.INVALID_DENY);
        assertThat(c.scopeText()).isEqualTo("subjects");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Policy", "policy", "POLICY"})
    void aLifecycleKindWithoutAnEffectIsNotTheConcernOfThePdp(String kind) {
        Classification c = UnparseableAccessPolicy.classify(ROW,
                "apiVersion: dlap/v1\nkind: " + kind + "\nmetadata: {id: x}\nspec: {match: {cloudProvider: GCP}}\n");

        assertThat(c.disposition()).isEqualTo(Disposition.NOT_ACCESS_POLICY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Policy", "policy", "POLICY"})
    void aDenyWhoseKindReadsPolicyIsKeptAsAnInvalidDeny(String kind) {
        Classification c = UnparseableAccessPolicy.classify(ROW,
                "apiVersion: dlap/v1\nkind: " + kind + "\nmetadata: {id: x}\nspec:\n"
                        + "  subjects: {roles: [A]}\n  match: {labels: {anyOf: [PII]}}\n  effect: deny\n");

        assertThat(c.disposition()).isEqualTo(Disposition.INVALID_DENY);
        assertThat(c.policyId()).isEqualTo("x");
        assertThat(c.scopeText()).isEqualTo("subjects+resources");
    }

    @Test
    void aLifecycleKindWithAnEffectThatReadsAllowIsSkippedNotIgnored() {
        Classification c = UnparseableAccessPolicy.classify(ROW,
                "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: x}\nspec: {effect: allow}\n");

        assertThat(c.disposition()).isEqualTo(Disposition.SKIPPED_ALLOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"effect: ''", "effect: denny", "effect: [deny]", "effect: true", "effect: 7"})
    void aLifecycleKindWithAnEffectThatCannotBeReadIsAnInvalidDeny(String effectLine) {
        Classification c = UnparseableAccessPolicy.classify(ROW,
                "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: x}\nspec: {" + effectLine + "}\n");

        assertThat(c.disposition()).isEqualTo(Disposition.INVALID_DENY);
        assertThat(c.scopeReadable()).isFalse();
    }

    @Test
    void aLifecycleKindWhoseSpecIsNotAMapHasNoEffectAndIsIgnored() {
        Classification c = UnparseableAccessPolicy.classify(ROW,
                "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: x}\nspec: [a, b]\n");

        assertThat(c.disposition()).isEqualTo(Disposition.NOT_ACCESS_POLICY);
    }

    @Test
    void aBlankDocumentIsAnInvalidDenyWithNoReadableScopeNamedByItsRow() {
        for (String blank : new String[] {null, "", "  \n\t"}) {
            Classification c = UnparseableAccessPolicy.classify(ROW, blank);

            assertThat(c.disposition()).isEqualTo(Disposition.INVALID_DENY);
            assertThat(c.scopeReadable()).isFalse();
            assertThat(c.policyId()).isEqualTo(ROW.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{{", "not: [yaml", "just a sentence", "- a\n- list\n", "42"})
    void aDocumentThatIsNotAPolicyMapHasNoReadableScopeAndIsNamedByItsRow(String text) {
        Classification c = UnparseableAccessPolicy.classify(ROW, text);

        assertThat(c.disposition()).isEqualTo(Disposition.INVALID_DENY);
        assertThat(c.scopeReadable()).isFalse();
        assertThat(c.scopeText()).isEqualTo("unreadable");
        assertThat(c.policyId()).isEqualTo(ROW.toString());
    }

    @Test
    void anUnknownKindIsReadAsAPossibleAccessPolicy() {
        Classification c = UnparseableAccessPolicy.classify(ROW,
                "apiVersion: dlap/v1\nkind: AccesPolicy\nmetadata: {id: typo}\nspec:\n  match: {region: eu}\n");

        assertThat(c.disposition()).isEqualTo(Disposition.INVALID_DENY);
        assertThat(c.scopeText()).isEqualTo("resources");
        assertThat(c.policyId()).isEqualTo("typo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{roles: A}", "{roles: [A], tiers: READER}", "{roles: [A, null]}", "{roles: [A, '']}",
            "{roles: [], tiers: []}", "{}", "[A]", "A", "{roles: [{x: 1}]}"})
    void aSubjectSelectorOfAnyOtherShapeIsUnreadableAsAWhole(String subjects) {
        Classification c = UnparseableAccessPolicy.classify(ROW, doc("  subjects: " + subjects + "\n"
                + "  match: {labels: {anyOf: [PII]}}\n"));

        assertThat(c.subjectsReadable()).isFalse();
        assertThat(c.scope().getSpec().getSubjects()).isNull();
        assertThat(c.scopeText()).isEqualTo("resources");
    }

    @Test
    void tiersAloneAreAReadableSubjectSelector() {
        Classification c = UnparseableAccessPolicy.classify(ROW, doc("  subjects: {tiers: [READER]}\n"));

        assertThat(c.subjectsReadable()).isTrue();
        assertThat(c.scope().getSpec().getSubjects().getRoles()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[a]", "labels", "null"})
    void aResourceSelectorThatIsNotANonEmptyMapIsUnreadable(String match) {
        Classification c = UnparseableAccessPolicy.classify(ROW, doc("  subjects: {roles: [A]}\n  match: " + match
                + "\n"));

        assertThat(c.matchReadable()).isFalse();
        assertThat(c.scope().getSpec().getMatch()).isNull();
        assertThat(c.scopeText()).isEqualTo("subjects");
    }

    @Test
    void aSpecThatIsNotAMapLeavesNoReadableScope() {
        Classification c = UnparseableAccessPolicy.classify(ROW, "kind: AccessPolicy\nmetadata: {id: s}\nspec: deny\n");

        assertThat(c.disposition()).isEqualTo(Disposition.INVALID_DENY);
        assertThat(c.scopeReadable()).isFalse();
        assertThat(c.policyId()).isEqualTo("s");
    }

    @Test
    void aBlankOrNonTextMetadataIdFallsBackToTheRowId() {
        assertThat(UnparseableAccessPolicy.classify(ROW, "metadata: {id: ' '}\nspec: {effect: deny}\n").policyId())
                .isEqualTo(ROW.toString());
        assertThat(UnparseableAccessPolicy.classify(ROW, "metadata: {id: [x]}\nspec: {effect: deny}\n").policyId())
                .isEqualTo(ROW.toString());
        assertThat(UnparseableAccessPolicy.classify(null, "").policyId()).isEqualTo("(no row id)");
    }
}
