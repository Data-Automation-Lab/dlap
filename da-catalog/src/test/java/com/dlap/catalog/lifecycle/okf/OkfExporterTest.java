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

package com.dlap.catalog.lifecycle.okf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.catalog.lifecycle.LifecycleState;
import com.dlap.catalog.lifecycle.LifecycleStateJson;
import com.dlap.catalog.lifecycle.LifecycleStateSchema;

/**
 * The OKF v0.1 mapping (docs/specs/lifecycle-state.md section 8.2): the exporter writes exactly the
 * file the specification shows, every concept file passes the frontmatter check, and an export is
 * the same bytes every time.
 */
class OkfExporterTest {

    private static final String AZURE = "valid-azure-container-unknown-read.json";
    private static final String BIGQUERY = "valid-bigquery-table-full-coverage-expired.json";
    private static final String S3 = "valid-s3-bucket-partial-coverage-legal-hold.json";

    @Test
    void theAzureExampleIsByteIdenticalToTheFileSection82Shows() {
        String spec = LifecycleStateSchema.spec();
        int start = spec.indexOf("````markdown\n") + "````markdown\n".length();
        int end = spec.indexOf("````", start);
        String expected = spec.substring(start, end);
        String expectedPath = spec.substring(spec.indexOf("Example, `lifecycle-state/") + "Example, `".length(),
                spec.indexOf(".md`", spec.indexOf("Example, `lifecycle-state/")) + ".md".length());

        LifecycleState azure = state(AZURE);

        assertThat(OkfExporter.document(azure)).isEqualTo(expected);
        assertThat(OkfExporter.path(azure)).isEqualTo(expectedPath)
                .isEqualTo("lifecycle-state/6fa1a9936837aaaa5c9f0fc1318140a4dbf75bc8ffb97ce244311bf349f32028.md");
    }

    @ParameterizedTest
    @ValueSource(strings = {AZURE, BIGQUERY, S3})
    void everyPublishedExamplePassesTheFrontmatterCheck(String file) {
        LifecycleState state = state(file);
        OkfFrontmatterCheck.check(OkfExporter.path(state), OkfExporter.document(state));
    }

    @Test
    void tagsFollowTheMappingOrderWithLegalHoldOnlyWhenHeld() {
        assertThat(OkfExporter.tags(state(S3))).containsExactly("lifecycle-state", "aws", "s3-bucket",
                "read-coverage-partial", "retention-on-hold", "legal-hold", "disposition-unknown");
        assertThat(OkfExporter.tags(state(BIGQUERY))).containsExactly("lifecycle-state", "gcp", "bigquery-table",
                "read-coverage-full", "retention-due", "disposition-delete");
    }

    @Test
    void theTableShowsKnownValuesAndEscapesPipes() {
        String bq = OkfExporter.document(state(BIGQUERY));
        assertThat(bq).contains("| Read coverage | FULL since 2026-03-02T06:41:00Z: ")
                .contains("| Size | 52428800000 bytes at 2026-09-29T06:00:00Z |")
                .contains("| Retention | DUE, FIXED_DATE 2026-09-30T00:00:00Z, expires 2026-09-30T00:00:00Z, source PROVIDER |")
                .contains("| Disposition | delete at 2026-09-30T00:03:51.208Z by native-rule |");

        LifecycleState odd = withAsset(state(AZURE), "gs://odd|bucket/\"quoted\"\\path");
        String document = OkfExporter.document(odd);
        assertThat(document).contains("| Read coverage | unknown: permission")
                .contains("title: \"gs://odd|bucket/\\\"quoted\\\"\\\\path\"");
        OkfFrontmatterCheck.check(OkfExporter.path(odd), document);
    }

    @Test
    void yamlQuotingEscapesQuotesBackslashesAndControlCharacters() {
        assertThat(OkfExporter.quoted("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
        assertThat(OkfExporter.quoted("x\ny\tz\u0001")).isEqualTo("\"x\\ny\\tz\\x01\"");
    }

    @Test
    void anExportIsTheSameBytesEveryTimeAndHoldsOneFilePerAsset() throws IOException {
        List<LifecycleState> states = List.of(state(AZURE), state(BIGQUERY), state(S3));
        Instant asOf = states.get(0).asOf();

        byte[] first = zip(states, asOf);
        byte[] second = zip(states, asOf);

        assertThat(first).isEqualTo(second);
        Map<String, String> files = OkfFrontmatterCheck.unzip(first);
        assertThat(files).hasSize(3);
        files.forEach(OkfFrontmatterCheck::check);
    }

    @Test
    void twoObjectsOfOneAssetAreRefused() {
        LifecycleState azure = state(AZURE);
        assertThatThrownBy(() -> zip(List.of(azure, azure), azure.asOf()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static LifecycleState state(String file) {
        return LifecycleStateJson.parse(LifecycleStateSchema.example(file));
    }

    static byte[] zip(List<LifecycleState> states, Instant asOf) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OkfExporter.writeZip(states, asOf, out);
        return out.toByteArray();
    }

    private static LifecycleState withAsset(LifecycleState s, String asset) {
        return new LifecycleState(s.specVersion(), asset, s.platform(), s.asOf(), s.createdAt(), s.lastReadAt(),
                s.lastWrittenAt(), s.readCoverage(), s.readCoverageReasons(), s.readCoverageSince(), s.sizeBytes(),
                s.sizeObservedAt(), s.retention(), s.legalHold(), s.disposition(), s.provenance());
    }
}
