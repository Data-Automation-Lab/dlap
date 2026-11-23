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

package com.dlap.fixtures.gen.gcpaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Keeps the generated {@code gcpaudit} cases honest: the committed files are exactly what
 * {@link GcpAuditCaseBook} writes (nobody edited an input or an expected result by hand), every
 * generated case says so in its {@code meta.yaml}, and every case sits in the version cell its
 * records belong to.
 */
class GcpAuditGeneratedCasesTest {

    private static final Path SOURCES = Path.of("src", "main", "resources", "dlap-fixtures").toAbsolutePath();

    /** Records filed outside their own version on purpose: the variant is about the mix. */
    private static final Set<String> MIXED = Set.of("mixed-with-v2", "binding-pinned-v1-rejects-v2");

    @Test
    void theCommittedCasesAreExactlyWhatTheBookWrites() {
        assumeTrue(Files.isDirectory(SOURCES), "run from the da-log-fixtures module directory");
        List<String> problems = GcpAuditGenerator.differences(SOURCES, GcpAuditGenerator.files());
        assertThat(problems).as("run GcpAuditGenerator write --corpus " + SOURCES).isEmpty();
    }

    @Test
    void theCheckCommandAgrees() {
        assumeTrue(Files.isDirectory(SOURCES), "run from the da-log-fixtures module directory");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = GcpAuditGenerator.run(new String[] {"check", "--corpus", SOURCES.toString()},
                new PrintStream(out), new PrintStream(err));
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
    }

    @Test
    void everyGeneratedCaseNamesTheGeneratorAndItsConfirmation() {
        Map<String, String> files = GcpAuditGenerator.files();
        List<FixtureCase> generated = FixtureCatalog.fromClasspath().cases(GcpAuditCaseWriter.FAMILY).stream()
                .filter(c -> GcpAuditCaseWriter.GENERATOR.equals(c.meta().generator())).toList();
        assertThat(generated).hasSize((int) files.keySet().stream().filter(k -> k.endsWith("/meta.yaml")).count());
        assertThat(generated).allSatisfy(c -> {
            assertThat(c.evidence()).isEqualTo(FixtureCase.Evidence.CONFIRMED);
            assertThat(c.meta().license()).isEqualTo("synthetic");
            assertThat(c.meta().origin()).isEqualTo(FixtureCase.Origin.SYNTHETIC_FROM_PROTO);
        });
    }

    @Test
    void everyRecordSitsInTheCellOfItsVersion() {
        for (GcpAuditCase c : GcpAuditCaseBook.cases()) {
            for (List<Entry> file : c.files()) {
                for (Entry e : file) {
                    JsonNode j = e.json();
                    String where = c.version() + "/" + c.name() + " " + j.path("insertId").asText();
                    String detected = detect(j);
                    if (MIXED.contains(c.name()) && !detected.equals(expectedSchema(c.version()))) {
                        continue;
                    }
                    assertThat(detected).as(where).isEqualTo(expectedSchema(c.version()));
                    if (c.version().startsWith("auditlog")) {
                        boolean typed = false;
                        for (JsonNode a : j.path("protoPayload").path("authorizationInfo")) {
                            typed |= a.has("permissionType");
                        }
                        assertThat(typed).as(where + " carries permissionType")
                                .isEqualTo(c.version().equals("auditlog-2025"));
                    }
                }
            }
        }
    }

    private static String expectedSchema(String cell) {
        return cell.startsWith("auditlog") ? "auditlog" : cell;
    }

    /** The structural version rule of the coverage spec. */
    private static String detect(JsonNode entry) {
        JsonNode pp = entry.path("protoPayload");
        if (pp.path("metadata").path("@type").asText().endsWith("google.cloud.audit.BigQueryAuditMetadata")) {
            return "bq-auditmetadata";
        }
        if (pp.path("serviceData").path("@type").asText().endsWith("google.cloud.bigquery.logging.v1.AuditData")) {
            return "bq-auditdata-legacy";
        }
        return "auditlog";
    }
}
