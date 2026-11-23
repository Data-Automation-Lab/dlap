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

package com.dlap.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds throwaway corpora in a temp directory so each test controls exactly what is wrong. */
final class TestCorpus {

    static final String VALID_CATALOG = """
            family: fam
            envelopes:
              - id: env
                versions:
                  - id: "1"
                    supported: true
                    operations: [svc:Read, svc:Write]
                    variants: [v-denied, v-bad-gzip]
            """;

    /** The independent spec of family "fam": the same cell, operations and variants as VALID_CATALOG. */
    static final String VALID_SPEC = """
            spec: dlap-coverage-spec/1
            family: fam
            title: Test family
            source_kind: TEST_KIND
            written_by: TEST
            written_on: "2026-10-19"
            research: [a test]
            operation_id_rule: "svc:<operation>"
            version_rule: fixed
            envelopes:
              - id: env
                title: Test envelope
                doc_url: https://example.org/env
                versions: ["1"]
            versions:
              - id: "1"
                title: Version one
                doc_url: https://example.org/v1
                detect: always
            operations:
              - id: svc:Read
                doc_url: https://example.org/read
              - id: svc:Write
                doc_url: https://example.org/write
            variants:
              - id: v-denied
                title: A denied call
                doc_url: https://example.org/denied
              - id: v-bad-gzip
                title: A corrupt gzip input
                negative: true
                doc_url: https://example.org/gzip
            """;

    static final String SYNTHETIC_NOTICE = "All cases in this family are synthetic.\n";

    private final Path root;

    TestCorpus(Path root) {
        this.root = root;
    }

    Path root() {
        return root;
    }

    /**
     * A corpus with one family "fam" (envelope "env", version "1") that passes every rule: an
     * independent coverage spec, a catalog that claims the cell, and three authored cases.
     */
    static TestCorpus valid(Path root) {
        TestCorpus c = new TestCorpus(root);
        c.write("fam/coverage-spec.yaml", VALID_SPEC);
        c.write("fam/catalog.yaml", VALID_CATALOG);
        c.write("fam/NOTICE.md", SYNTHETIC_NOTICE);
        c.caseAt("fam/env/1/read", Meta.synthetic().covers("svc:Read"));
        c.caseAt("fam/env/1/write", Meta.synthetic().covers("svc:Write").variants("v-denied"));
        c.caseAt("fam/negative/env/1/bad-gzip", Meta.synthetic().covers().variants("v-bad-gzip")
                .outcome("QUARANTINED:INVALID_ENCODING"));
        return c;
    }

    TestCorpus write(String relative, String text) {
        Path file = root.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    TestCorpus delete(String relative) {
        try {
            Files.delete(root.resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /** Writes a full case: input.json, expected.json and the given meta. */
    TestCorpus caseAt(String relativeDir, Meta meta) {
        write(relativeDir + "/input.json", "{\"k\":1}\n");
        write(relativeDir + "/expected.json", "{\"outcome\":\"PARSED\"}\n");
        write(relativeDir + "/meta.yaml", meta.render());
        return this;
    }

    /** An ordered meta.yaml that tests edit key by key. */
    static final class Meta {
        private final Map<String, String> lines = new LinkedHashMap<>();

        static Meta synthetic() {
            Meta m = new Meta();
            m.lines.put("origin", "synthetic-from-doc");
            m.lines.put("source_url", "https://example.org/spec");
            m.lines.put("doc_date", "2026-10-18");
            m.lines.put("license", "synthetic");
            m.lines.put("covers", "[svc:Read]");
            m.lines.put("variant_ids", "[]");
            m.lines.put("expected_outcome", "PARSED");
            m.lines.put("anonymised", "false");
            return m;
        }

        Meta set(String key, String yamlValue) {
            lines.put(key, yamlValue);
            return this;
        }

        Meta without(String key) {
            lines.remove(key);
            return this;
        }

        Meta covers(String... ops) {
            return set("covers", "[" + String.join(", ", ops) + "]");
        }

        Meta variants(String... ids) {
            return set("variant_ids", "[" + String.join(", ", ids) + "]");
        }

        Meta outcome(String value) {
            return set("expected_outcome", value);
        }

        /** Turns this into an emulator capture with a valid citation. */
        Meta emulator() {
            return set("origin", "emulator-captured").set("license", "synthetic");
        }

        /** Turns this into a redacted capture from the project's own account. */
        Meta ownAccount() {
            return set("origin", "own-account-redacted").without("source_url").set("anonymised", "true");
        }

        /** Marks this case as produced by a generator. */
        Meta generator(String generator) {
            return set("generator", generator);
        }

        /** Adds one confirmed_by entry (flow-style YAML). */
        Meta confirmedBy(String method, String evidence) {
            String entry = "{method: " + method + ", evidence: \"" + evidence + "\"}";
            String existing = lines.get("confirmed_by");
            String list = existing == null ? "[" + entry + "]"
                    : existing.substring(0, existing.length() - 1) + ", " + entry + "]";
            return set("confirmed_by", list);
        }

        /** Turns this into a public-dataset meta with a valid citation. */
        Meta publicDataset(String repo, String license) {
            return set("origin", "public-dataset")
                    .without("source_url")
                    .without("doc_date")
                    .set("repo", repo)
                    .set("commit", "\"0123456789abcdef0123456789abcdef01234567\"")
                    .set("license", license)
                    .set("anonymised", "true");
        }

        String render() {
            StringBuilder out = new StringBuilder();
            lines.forEach((k, v) -> out.append(k).append(": ").append(v).append('\n'));
            return out.toString();
        }
    }
}
