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

package com.dlap.logingest.parse.core.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalField;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.PersistRaw;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.logingest.parse.core.VersionScheme;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The mapping model the validator builds, its messages, and YAML-level refusals of the loader. */
class MappingSpecValidatorTest {

    private static MappingSpec selftest() {
        return MappingSpecLoader.parse(MappingSamples.text(MappingSamples.SELFTEST), MappingSamples.SELFTEST, "selftest");
    }

    private static MappingSpec demo() {
        return MappingSpecLoader.parse(MappingSamples.text(MappingSamples.CONTRACT_DEMO), MappingSamples.CONTRACT_DEMO,
                "contract-demo");
    }

    @Test
    void theSampleBuildsTheExpectedModel() {
        MappingSpec s = selftest();
        assertThat(s.family()).isEqualTo("selftest");
        assertThat(s.sourceKind()).isEqualTo("DLAP_SELFTEST");
        assertThat(s.sourceProvider()).isEqualTo(SourceProvider.AWS);
        assertThat(s.settings().overlap()).isEqualTo(Duration.ofMinutes(10));
        MappingSpec.Envelope e = s.envelope("generic-json").orElseThrow();
        assertThat(e.framing()).containsExactlyInAnyOrder(Framing.RECORD, Framing.JSON_LINES);
        assertThat(e.detection().scheme()).isEqualTo(VersionScheme.MAJOR_MINOR);
        assertThat(e.ranges()).containsExactly(VersionRange.majorMinor("1.0", "1.0"));
        assertThat(e.nativeKey().namespaced()).isTrue();
        assertThat(e.fields().get(CanonicalField.SERVICE).constant()).isEqualTo("demo");
        assertThat(e.required()).containsExactlyInAnyOrder(CanonicalField.PRINCIPAL_ID, CanonicalField.TARGET_ID);
        assertThat(e.rules()).hasSize(3);
    }

    @Test
    void familySettingsBecomeBindingDefaults() {
        BindingConfig b = selftest().defaultBinding("b-1");
        assertThat(b.onMismatch()).isEqualTo(OnMismatch.PARSE_WITH_DETECTED);
        assertThat(b.maxRecordBytes()).isEqualTo(65536);
        assertThat(b.sourceKind()).isEqualTo("DLAP_SELFTEST");
        BindingConfig d = demo().defaultBinding("b-2");
        assertThat(d.persistRaw()).isEqualTo(new PersistRaw(PersistRaw.Policy.SAMPLED, 10));
        assertThat(d.maxRecordBytes()).isEqualTo(BindingConfig.DEFAULT_MAX_RECORD_BYTES);
    }

    @Test
    void versionDeltasAccumulateUpToTheAskedVersion() {
        MappingSpec.Envelope e = demo().envelope("generic-json").orElseThrow();
        assertThat(e.ranges()).containsExactly(VersionRange.majorMinor("1.0", "1.2"));
        assertThat(e.effective("1.0").orElseThrow().knownPaths()).doesNotContain(FieldPath.of("/checks"));
        assertThat(e.effective("1.1").orElseThrow().knownPaths()).doesNotContain(FieldPath.of("/checks"));
        assertThat(e.effective("1.2").orElseThrow().knownPaths()).contains(FieldPath.of("/checks"));
        assertThat(e.effective("1.3")).as("a newer minor is not an effective version").isEmpty();
        assertThat(e.effective("2.0")).isEmpty();
        assertThat(e.principalType("assumed-role")).contains(PrincipalType.ROLE);
        assertThat(e.principalType("ASSUMEDROLE")).contains(PrincipalType.ROLE);
        assertThat(e.principalType("Unknown")).isEmpty();
        assertThat(e.effective("1.0").orElseThrow().declaredPaths())
                .contains(FieldPath.of("/who/arn"), FieldPath.of("/who/name"), FieldPath.of("/receivedAt"),
                        FieldPath.of("/checks/*/granted"), FieldPath.of("/kind"), FieldPath.of("/v"), FieldPath.of("/id"));
        assertThat(demo().extensions().get("note").asText()).startsWith("free-form");
    }

    @Test
    void severalMajorsGiveOneRangeEach() {
        JsonNode t = MappingSamples.tree(MappingSamples.SELFTEST);
        ((com.fasterxml.jackson.databind.node.ArrayNode) t.get("envelopes").get(0).get("versions"))
                .add(MappingSamples.json("{\"id\":\"2.0\"}")).add(MappingSamples.json("{\"id\":\"2.3\"}"));
        MappingSpec s = MappingSpecValidator.validate(t, "x").spec();
        assertThat(s.envelopes().get(0).ranges())
                .containsExactly(VersionRange.majorMinor("1.0", "1.0"), VersionRange.majorMinor("2.0", "2.3"));
    }

    @Test
    void problemsNameTheFileAndThePathAndAllAreListed() {
        JsonNode t = MappingSamples.tree(MappingSamples.SELFTEST);
        com.fasterxml.jackson.databind.node.ObjectNode env =
                (com.fasterxml.jackson.databind.node.ObjectNode) t.get("envelopes").get(0);
        ((com.fasterxml.jackson.databind.node.ObjectNode) env.get("fields")).put("actor", "/actor");
        env.put("id", "Bad_Id");
        MappingSpecValidator.Result r = MappingSpecValidator.validate(t, "selftest/mapping.yaml");
        assertThat(r.ok()).isFalse();
        assertThat(r.spec()).isNull();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p)
                .startsWith("selftest/mapping.yaml: envelopes[0].fields.actor: is not a canonical field"));
        assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("envelopes[0].id: must be lower kebab case"));
    }

    @Test
    void theFamilyMustMatchItsDirectory() {
        MappingSpecValidator.Result r = MappingSpecValidator.validate(MappingSamples.tree(MappingSamples.SELFTEST),
                "x/mapping.yaml", "other-family");
        assertThat(r.problems()).singleElement().asString().contains("does not match its directory");
    }

    @Test
    void yamlTheValidatorCannotTrustIsRefusedByTheLoader() {
        String sample = MappingSamples.text(MappingSamples.SELFTEST);
        assertThatThrownBy(() -> MappingSpecLoader.readYaml("a: 1\na: 2\n", "dup.yaml"))
                .isInstanceOf(MappingSpecException.class).hasMessageContaining("dup.yaml");
        assertThatThrownBy(() -> MappingSpecLoader.readYaml("when: 2026-01-01\n", "date.yaml"))
                .isInstanceOf(MappingSpecException.class).hasMessageContaining("quote dates");
        assertThatThrownBy(() -> MappingSpecLoader.readYaml("yes: 1\n", "bool.yaml"))
                .isInstanceOf(MappingSpecException.class).hasMessageContaining("keys must be strings");
        assertThatThrownBy(() -> MappingSpecLoader.readYaml("a: 1\n---\nb: 2\n", "multi.yaml"))
                .isInstanceOf(MappingSpecException.class);
        assertThatThrownBy(() -> MappingSpecLoader.readYaml("!!javax.script.ScriptEngineManager [x]\n", "tag.yaml"))
                .isInstanceOf(MappingSpecException.class);
        assertThatThrownBy(() -> MappingSpecLoader.parse(sample.replace("id: \"1.0\"", "id: 1.10"), "num.yaml", "selftest"))
                .isInstanceOf(MappingSpecException.class).hasMessageContaining("quote");
    }

    @Test
    void anEnvelopeNeedsAtLeastOneVersionInTheModelToo() {
        assertThatThrownBy(() -> new MappingSpec.Envelope("e", null, null, null,
                new MappingSpec.Detection(VersionScheme.MAJOR_MINOR, FieldPath.of("/v"), List.of()),
                new MappingSpec.NativeKey(false, List.of(new MappingSpec.KeyPart(FieldPath.of("/id"), null))),
                null, null, null, null, null, null, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
