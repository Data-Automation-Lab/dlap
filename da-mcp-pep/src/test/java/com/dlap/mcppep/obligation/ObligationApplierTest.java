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

package com.dlap.mcppep.obligation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.mcppep.Obligation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ObligationApplier}: mask, redact_rows, log_only,
 * combined application, the fail-closed rule for unknown types, and the
 * no-op cases (empty obligations, labels matching no field).
 */
class ObligationApplierTest {

    private final ObligationApplier applier = new ObligationApplier();

    /** Schema: name (no labels), email (PII), country (EU_RESIDENT). */
    private static LabeledDataset sampleDataset() {
        List<LabeledField> schema = List.of(
                new LabeledField("name", List.of()),
                new LabeledField("email", List.of("PII")),
                new LabeledField("country", List.of("EU_RESIDENT")));
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Alice", "alice@example.com", "DE"));
        rows.add(row("Bob", "bob@example.com", "US"));
        return LabeledDataset.of(schema, rows);
    }

    private static Map<String, Object> row(Object name, Object email, Object country) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("email", email);
        m.put("country", country);
        return m;
    }

    @Test
    void maskReplacesOnlyLabeledFields() {
        ApplicationResult r = applier.apply(
                List.of(new Obligation("mask", List.of("PII"))), sampleDataset());

        assertThat(r.satisfied()).isTrue();
        assertThat(r.appliedTypes()).containsExactly("mask");
        List<Map<String, Object>> rows = r.result().rows();
        // email (PII) masked; name and country untouched.
        assertThat(rows.get(0)).containsEntry("email", "***");
        assertThat(rows.get(0)).containsEntry("name", "Alice");
        assertThat(rows.get(0)).containsEntry("country", "DE");
        assertThat(rows.get(1)).containsEntry("email", "***");
        assertThat(rows.get(1)).containsEntry("name", "Bob");
    }

    @Test
    void maskPreservesNullValues() {
        List<LabeledField> schema = List.of(new LabeledField("email", List.of("PII")));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> r0 = new LinkedHashMap<>();
        r0.put("email", null);
        rows.add(r0);
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("email", "x@y.com");
        rows.add(r1);

        ApplicationResult r = applier.apply(
                List.of(new Obligation("mask", List.of("PII"))), LabeledDataset.of(schema, rows));

        assertThat(r.satisfied()).isTrue();
        assertThat(r.result().rows().get(0).get("email")).isNull();
        assertThat(r.result().rows().get(1).get("email")).isEqualTo("***");
    }

    @Test
    void maskUsesConfigurableToken() {
        ObligationApplier custom = new ObligationApplier("[REDACTED]");
        ApplicationResult r = custom.apply(
                List.of(new Obligation("mask", List.of("PII"))), sampleDataset());

        assertThat(r.result().rows().get(0).get("email")).isEqualTo("[REDACTED]");
    }

    @Test
    void redactRowsDropsRowsWithMatchingNonNullValue() {
        // Redact rows carrying EU_RESIDENT-labelled data (country non-null).
        List<LabeledField> schema = List.of(
                new LabeledField("name", List.of()),
                new LabeledField("country", List.of("EU_RESIDENT")));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> eu = new LinkedHashMap<>();
        eu.put("name", "Alice");
        eu.put("country", "DE");
        rows.add(eu);
        Map<String, Object> nullCountry = new LinkedHashMap<>();
        nullCountry.put("name", "Bob");
        nullCountry.put("country", null); // null does NOT match the predicate → kept
        rows.add(nullCountry);

        ApplicationResult r = applier.apply(
                List.of(new Obligation("redact_rows", List.of("EU_RESIDENT"))),
                LabeledDataset.of(schema, rows));

        assertThat(r.satisfied()).isTrue();
        assertThat(r.appliedTypes()).containsExactly("redact_rows");
        assertThat(r.result().rows()).hasSize(1);
        assertThat(r.result().rows().get(0)).containsEntry("name", "Bob");
    }

    @Test
    void logOnlyIsNoOpButRecorded() {
        LabeledDataset in = sampleDataset();
        ApplicationResult r = applier.apply(
                List.of(new Obligation("log_only", List.of("PII"))), in);

        assertThat(r.satisfied()).isTrue();
        assertThat(r.appliedTypes()).containsExactly("log_only");
        // Data unchanged.
        assertThat(r.result().rows()).isEqualTo(in.rows());
    }

    @Test
    void unknownTypeFailsClosedNamingTheType() {
        ApplicationResult r = applier.apply(
                List.of(new Obligation("tokenize", List.of("PII"))), sampleDataset());

        assertThat(r.satisfied()).isFalse();
        assertThat(r.unsatisfiedType()).isEqualTo("tokenize");
        assertThat(r.result()).isNull();
        assertThat(r.appliedTypes()).isEmpty();
    }

    @Test
    void anyUnknownTypeFailsClosedForTheWholeSet() {
        // A recognized mask alongside an unknown type → fail-closed overall,
        // and no partial transformation is returned.
        ApplicationResult r = applier.apply(
                List.of(
                        new Obligation("mask", List.of("PII")),
                        new Obligation("shuffle", List.of("PII"))),
                sampleDataset());

        assertThat(r.satisfied()).isFalse();
        assertThat(r.unsatisfiedType()).isEqualTo("shuffle");
        assertThat(r.result()).isNull();
    }

    @Test
    void combinedMaskAndRedactAppliedTogether() {
        // Schema: name (no labels), email (PII → mask), ssn (SENSITIVE → redact
        // any row carrying a non-null ssn). Alice has an ssn (dropped); Bob's is
        // null (kept, with email masked).
        List<LabeledField> schema = List.of(
                new LabeledField("name", List.of()),
                new LabeledField("email", List.of("PII")),
                new LabeledField("ssn", List.of("SENSITIVE")));
        List<Map<String, Object>> in = new ArrayList<>();
        Map<String, Object> alice = new LinkedHashMap<>();
        alice.put("name", "Alice");
        alice.put("email", "alice@example.com");
        alice.put("ssn", "123-45-6789");
        in.add(alice);
        Map<String, Object> bob = new LinkedHashMap<>();
        bob.put("name", "Bob");
        bob.put("email", "bob@example.com");
        bob.put("ssn", null);
        in.add(bob);

        ApplicationResult r = applier.apply(
                List.of(
                        new Obligation("mask", List.of("PII")),
                        new Obligation("redact_rows", List.of("SENSITIVE"))),
                LabeledDataset.of(schema, in));

        assertThat(r.satisfied()).isTrue();
        assertThat(r.appliedTypes()).containsExactly("mask", "redact_rows");
        // Alice (non-null ssn) dropped; Bob (null ssn) kept with email masked.
        List<Map<String, Object>> rows = r.result().rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("name", "Bob");
        assertThat(rows.get(0)).containsEntry("email", "***");
        assertThat(rows.get(0)).containsEntry("ssn", null);
    }

    @Test
    void emptyObligationsLeaveDatasetUnchanged() {
        LabeledDataset in = sampleDataset();
        ApplicationResult r = applier.apply(List.of(), in);

        assertThat(r.satisfied()).isTrue();
        assertThat(r.appliedTypes()).isEmpty();
        assertThat(r.result().rows()).isEqualTo(in.rows());
        assertThat(r.result().schema()).isEqualTo(in.schema());
    }

    @Test
    void nullObligationsTreatedAsEmpty() {
        LabeledDataset in = sampleDataset();
        ApplicationResult r = applier.apply(null, in);

        assertThat(r.satisfied()).isTrue();
        assertThat(r.appliedTypes()).isEmpty();
        assertThat(r.result().rows()).isEqualTo(in.rows());
    }

    @Test
    void labelsMatchingNoFieldAreNoOpAndSatisfied() {
        LabeledDataset in = sampleDataset();
        ApplicationResult r = applier.apply(
                List.of(new Obligation("mask", List.of("HEALTH"))), in);

        assertThat(r.satisfied()).isTrue();
        assertThat(r.appliedTypes()).containsExactly("mask");
        // No field carries HEALTH → nothing masked.
        assertThat(r.result().rows()).isEqualTo(in.rows());
    }
}
