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
 * ADR-0031: the translator is the single seam that turns the visual builder's
 * structured input into the canonical {@code dlap/v1} DSL. Every output must be
 * re-parseable by {@link PolicyDslParser} (the runtime path), so these tests
 * assert the round-trip and the specific clause/action mappings the spec defines.
 */
class PolicyDslTranslatorTest {

    private final PolicyDslTranslator translator = new PolicyDslTranslator();
    private final PolicyDslParser parser = new PolicyDslParser();

    @Test
    void structuredInputProducesReParseableCanonicalDsl() {
        List<Map<String, Object>> conditions = List.of(
                Map.of("field", "cloudProvider", "operator", "equals", "value", "GCP"),
                Map.of("field", "assetType", "operator", "anyOf", "value", "GCS_BUCKET,BIGQUERY_TABLE"),
                Map.of("field", "labels", "operator", "anyOf", "value", "PII"),
                Map.of("field", "labels", "operator", "noneOf", "value", "LEGAL_HOLD"),
                Map.of("field", "lastAccessedAt", "operator", "olderThan", "value", "P90D"),
                Map.of("field", "sizeBytes", "operator", "gte", "value", "1073741824"));
        Map<String, Object> action = Map.of(
                "type", "archive",
                "parameters", Map.of("targetStorageClass", "ARCHIVE"));

        String yaml = translator.toYaml("archive-stale-pii", "Archive stale PII",
                "Move stale PII to archive", "gov@example.com", true, conditions, action, null);

        // The runtime parser must accept it - otherwise the policy would be inert.
        PolicyDsl dsl = parser.parse(yaml);
        assertThat(dsl.getApiVersion()).isEqualTo("dlap/v1");
        assertThat(dsl.getKind()).isEqualTo("Policy");
        assertThat(dsl.getMetadata().getId()).isEqualTo("archive-stale-pii");
        assertThat(dsl.getMetadata().getName()).isEqualTo("Archive stale PII");

        Map<String, Object> match = dsl.getSpec().getMatch();
        assertThat(match.get("cloudProvider")).isEqualTo("GCP");
        assertThat(asMap(match.get("assetType")).get("anyOf"))
                .isEqualTo(List.of("GCS_BUCKET", "BIGQUERY_TABLE"));
        // Two clauses on the same field merge into one map.
        Map<String, Object> labels = asMap(match.get("labels"));
        assertThat(labels.get("anyOf")).isEqualTo(List.of("PII"));
        assertThat(labels.get("noneOf")).isEqualTo(List.of("LEGAL_HOLD"));
        assertThat(asMap(match.get("lastAccessedAt")).get("olderThan")).isEqualTo("P90D");
        // Numeric bound survives the YAML round-trip; assert by value, not box type
        // (the parser may read it back as Integer or Long depending on magnitude).
        assertThat(((Number) asMap(match.get("sizeBytes")).get("gte")).longValue())
                .isEqualTo(1073741824L);

        assertThat(dsl.getSpec().getAction().getType()).isEqualTo("archive");
        assertThat(dsl.getSpec().getAction().getParameters().get("targetStorageClass")).isEqualTo("ARCHIVE");
    }

    @Test
    void defaultsToCreatedAndModifiedEventTriggersWhenNoneGiven() {
        String yaml = translator.toYaml(null, "Any change", null, null, true,
                List.of(), Map.of("type", "alert.email"), null);

        PolicyDsl dsl = parser.parse(yaml);
        assertThat(dsl.getSpec().getTriggers()).hasSize(2);
        assertThat(dsl.getSpec().getTriggers())
                .allMatch(t -> "event".equals(t.getType()))
                .anyMatch(t -> "created".equals(t.getLifecycle()))
                .anyMatch(t -> "modified".equals(t.getLifecycle()));
        // A blank name still yields a valid metadata.id.
        assertThat(dsl.getMetadata().getId()).isEqualTo("any-change");
    }

    @Test
    void keyedActionShapeIsNormalisedToTypeAndParameters() {
        String yaml = translator.toYaml(null, "Tag it", null, null, true,
                List.of(Map.of("field", "assetType", "operator", "equals", "value", "S3_BUCKET")),
                Map.of("archive", Map.of("targetStorageClass", "GLACIER")), null);

        PolicyDsl dsl = parser.parse(yaml);
        assertThat(dsl.getSpec().getAction().getType()).isEqualTo("archive");
        assertThat(dsl.getSpec().getAction().getParameters().get("targetStorageClass")).isEqualTo("GLACIER");
        // Scalar-equality clause serialises as a plain field: value.
        assertThat(dsl.getSpec().getMatch().get("assetType")).isEqualTo("S3_BUCKET");
    }

    @Test
    void sameFieldComparatorBoundsMergeInsteadOfOverwriting() {
        List<Map<String, Object>> conditions = List.of(
                Map.of("field", "sizeBytes", "operator", "gte", "value", "1073741824"),
                Map.of("field", "sizeBytes", "operator", "lte", "value", "10737418240"),
                Map.of("field", "lastAccessedAt", "operator", "olderThan", "value", "P30D"),
                Map.of("field", "lastAccessedAt", "operator", "youngerThan", "value", "P365D"));

        PolicyDsl dsl = parser.parse(translator.toYaml(null, "Range", null, null, true,
                conditions, Map.of("type", "alert.email"), null));

        Map<String, Object> size = asMap(dsl.getSpec().getMatch().get("sizeBytes"));
        assertThat(((Number) size.get("gte")).longValue()).isEqualTo(1073741824L);
        assertThat(((Number) size.get("lte")).longValue()).isEqualTo(10737418240L);
        Map<String, Object> acc = asMap(dsl.getSpec().getMatch().get("lastAccessedAt"));
        assertThat(acc.get("olderThan")).isEqualTo("P30D");
        assertThat(acc.get("youngerThan")).isEqualTo("P365D");
    }

    @Test
    void missingActionIsRejectedRatherThanInvented() {
        assertThatThrownBy(() -> translator.toYaml(null, "No action", null, null, true,
                List.of(Map.of("field", "cloudProvider", "operator", "equals", "value", "AWS")),
                null, null))
                .isInstanceOf(PolicyDslTranslator.PolicyTranslationException.class)
                .hasMessageContaining("action");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertThat(o).isInstanceOf(Map.class);
        return (Map<String, Object>) o;
    }
}
