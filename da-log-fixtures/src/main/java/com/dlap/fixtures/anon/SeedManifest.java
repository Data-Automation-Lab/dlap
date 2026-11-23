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

package com.dlap.fixtures.anon;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The reproducible record of a family's seed import: which public repositories at which commits, and
 * which records of which files became which case. It lives next to the cases as
 * {@code dlap-fixtures/<family>/seeds.yaml}, so a reviewer can re-run the import against the pinned
 * commits ({@link SeedImporter}) and get the committed bytes back.
 *
 * <p>Schema (every key is checked; unknown keys are errors):
 * <pre>
 * spec: dlap-seed-manifest/1
 * family: cloudtrail
 * sources:
 *   &lt;source id&gt;:
 *     repo: owner/name              # the public repository
 *     commit: "40 hex"              # the pinned commit
 *     commit_date: "yyyy-mm-dd"     # its commit date
 *     license: MIT | Apache-2.0 | BSD-2-Clause
 *     kind: cloudtrail-files | panther-rule | gcp-example | json-document | otel-records
 *     root: dir/inside/the/repo     # file paths below are relative to it
 *     url: https://...              # public location of the data at that commit
 *     retrieved: "yyyy-mm-dd"       # when the pinned commit was read
 *     note: free text               # for NOTICE and reviewers
 * cases:
 *   - name: case-directory-name
 *     source: &lt;source id&gt;
 *     file: path/or/glob/with/one/star
 *     select: { event_ids: [...] } | { indices: [...] }   # omit to take the whole file
 *     gzip: true                    # store input.json.gz
 *     negative: true                # a case that must be quarantined; needs cell_version, outcome, reason, variants
 *     notes: free text
 * </pre>
 */
record SeedManifest(String family, Map<String, Source> sources, List<Case> cases) {

    /** The manifest file name inside a family directory. */
    static final String FILE = "seeds.yaml";

    /** The spec marker the first key must carry. */
    static final String SPEC = "dlap-seed-manifest/1";

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Set<String> LICENSES = Set.of("MIT", "Apache-2.0", "BSD-2-Clause");
    private static final Set<String> KINDS = Set.of(
            "cloudtrail-files", "panther-rule", "gcp-example", "json-document", "otel-records");
    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    /**
     * A public repository at a pinned commit.
     *
     * @param id         the key used by cases
     * @param repo       {@code owner/name}
     * @param commit     the 40 character commit
     * @param commitDate the commit's date
     * @param license    the repository's licence
     * @param kind       the reader that understands its file layout
     * @param root       the directory inside the repository that case files are relative to
     * @param url        where the data can be seen at that commit
     * @param retrieved  when the commit was read
     * @param note       free text
     */
    record Source(String id, String repo, String commit, String commitDate, String license, String kind,
                  String root, String url, String retrieved, String note) {
    }

    /**
     * One case to import.
     *
     * @param name        the case directory name
     * @param source      the source id
     * @param file        the file, relative to the source root; may hold one {@code *}
     * @param indices     positions of the records in the file, or empty
     * @param eventIds    CloudTrail event ids to take, or empty
     * @param gzip        whether to store the input gzip compressed
     * @param negative    whether the case must be quarantined
     * @param cellVersion for a negative case: the version cell it is filed under
     * @param outcome     for a negative case: the expected outcome code
     * @param variants    for a negative case: the variants it exercises
     * @param notes       free text appended to the generated note
     */
    record Case(String name, String source, String file, List<Integer> indices, List<String> eventIds,
                boolean gzip, boolean negative, String cellVersion, String outcome, List<String> variants,
                String notes, boolean distinctEventIds) {
    }

    /**
     * Reads and validates a manifest.
     *
     * @param file the {@code seeds.yaml} path
     * @return the manifest
     * @throws IllegalArgumentException when the file is malformed; the message lists every problem
     */
    static SeedManifest read(Path file) {
        List<String> problems = new ArrayList<>();
        JsonNode root;
        try {
            root = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(file + ": must be a YAML mapping");
        }
        keys(root, Set.of("spec", "family", "sources", "cases"), "manifest", problems);
        if (!SPEC.equals(text(root, "spec", "manifest", problems))) {
            problems.add("manifest: spec must be " + SPEC);
        }
        String family = text(root, "family", "manifest", problems);
        Map<String, Source> sources = new LinkedHashMap<>();
        JsonNode srcNode = root.get("sources");
        if (srcNode == null || !srcNode.isObject() || srcNode.isEmpty()) {
            problems.add("manifest: sources must be a non-empty mapping");
        } else {
            for (Iterator<Map.Entry<String, JsonNode>> it = srcNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                sources.put(e.getKey(), source(e.getKey(), e.getValue(), problems));
            }
        }
        List<Case> cases = new ArrayList<>();
        Set<String> names = new HashSet<>();
        JsonNode caseNodes = root.get("cases");
        if (caseNodes == null || !caseNodes.isArray() || caseNodes.isEmpty()) {
            problems.add("manifest: cases must be a non-empty list");
        } else {
            for (JsonNode c : caseNodes) {
                Case parsed = parseCase(c, sources, problems);
                if (parsed != null && !names.add(parsed.name())) {
                    problems.add("case " + parsed.name() + ": duplicate name");
                }
                if (parsed != null) {
                    cases.add(parsed);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(file + " has " + problems.size() + " problem(s):\n  "
                    + String.join("\n  ", problems));
        }
        return new SeedManifest(family, Map.copyOf(sources), List.copyOf(cases));
    }

    private static Source source(String id, JsonNode n, List<String> problems) {
        String where = "source " + id;
        if (!SEGMENT.matcher(id).matches()) {
            problems.add(where + ": id must match " + SEGMENT.pattern());
        }
        if (!n.isObject()) {
            problems.add(where + ": must be a mapping");
            return null;
        }
        keys(n, Set.of("repo", "commit", "commit_date", "license", "kind", "root", "url", "retrieved", "note"),
                where, problems);
        String commit = text(n, "commit", where, problems);
        if (commit != null && !COMMIT.matcher(commit).matches()) {
            problems.add(where + ": commit must be 40 lowercase hex digits (quote it)");
        }
        String commitDate = text(n, "commit_date", where, problems);
        String retrieved = text(n, "retrieved", where, problems);
        for (String d : new String[] {commitDate, retrieved}) {
            if (d != null && !DATE.matcher(d).matches()) {
                problems.add(where + ": dates must be yyyy-mm-dd, got " + d);
            }
        }
        String license = text(n, "license", where, problems);
        if (license != null && !LICENSES.contains(license)) {
            problems.add(where + ": license must be one of " + new TreeSet<>(LICENSES));
        }
        String kind = text(n, "kind", where, problems);
        if (kind != null && !KINDS.contains(kind)) {
            problems.add(where + ": kind must be one of " + new TreeSet<>(KINDS));
        }
        String url = text(n, "url", where, problems);
        if (url != null && !url.startsWith("https://")) {
            problems.add(where + ": url must be https");
        }
        String repo = text(n, "repo", where, problems);
        if (repo != null && !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            problems.add(where + ": repo must be owner/name");
        }
        return new Source(id, repo, commit, commitDate, license, kind, optText(n, "root"), url, retrieved,
                optText(n, "note"));
    }

    private static Case parseCase(JsonNode n, Map<String, Source> sources, List<String> problems) {
        if (!n.isObject()) {
            problems.add("case: must be a mapping");
            return null;
        }
        String name = text(n, "name", "case", problems);
        String where = "case " + name;
        keys(n, Set.of("name", "source", "file", "select", "gzip", "negative", "cell_version", "outcome",
                "variants", "notes", "distinct_event_ids"), where, problems);
        if (name != null && !SEGMENT.matcher(name).matches()) {
            problems.add(where + ": name must match " + SEGMENT.pattern());
        }
        String source = text(n, "source", where, problems);
        if (source != null && !sources.containsKey(source)) {
            problems.add(where + ": unknown source " + source);
        }
        String file = text(n, "file", where, problems);
        if (file != null && (file.contains("..") || file.startsWith("/") || file.chars().filter(c -> c == '*').count() > 1)) {
            problems.add(where + ": file must be relative, without .., with at most one *");
        }
        List<Integer> indices = new ArrayList<>();
        List<String> eventIds = new ArrayList<>();
        JsonNode select = n.get("select");
        if (select != null) {
            if (!select.isObject()) {
                problems.add(where + ": select must be a mapping");
            } else {
                keys(select, Set.of("indices", "event_ids"), where + " select", problems);
                JsonNode idx = select.get("indices");
                if (idx != null) {
                    idx.forEach(i -> {
                        if (i.isInt() && i.asInt() >= 0) {
                            indices.add(i.asInt());
                        } else {
                            problems.add(where + ": select.indices holds non-negative integers");
                        }
                    });
                }
                JsonNode ids = select.get("event_ids");
                if (ids != null) {
                    ids.forEach(i -> {
                        if (i.isTextual()) {
                            eventIds.add(i.asText());
                        } else {
                            problems.add(where + ": select.event_ids holds strings");
                        }
                    });
                }
                if (!indices.isEmpty() && !eventIds.isEmpty()) {
                    problems.add(where + ": select takes indices or event_ids, not both");
                }
            }
        }
        boolean negative = n.path("negative").asBoolean(false);
        List<String> variants = new ArrayList<>();
        if (n.has("variants")) {
            n.get("variants").forEach(v -> variants.add(v.asText()));
        }
        String cellVersion = optText(n, "cell_version");
        String outcome = optText(n, "outcome");
        if (negative && (cellVersion == null || outcome == null || variants.isEmpty())) {
            problems.add(where + ": a negative case needs cell_version, outcome and variants");
        }
        if (!negative && (cellVersion != null || outcome != null || !variants.isEmpty())) {
            problems.add(where + ": cell_version, outcome and variants are for negative cases only");
        }
        JsonNode distinct = n.get("distinct_event_ids");
        if (distinct != null && !distinct.isBoolean()) {
            problems.add(where + ": distinct_event_ids must be true or false");
        }
        return new Case(name, source, file, List.copyOf(indices), List.copyOf(eventIds), n.path("gzip").asBoolean(false),
                negative, cellVersion, outcome, List.copyOf(variants), optText(n, "notes"),
                distinct != null && distinct.asBoolean(false));
    }

    private static void keys(JsonNode n, Set<String> allowed, String where, List<String> problems) {
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!allowed.contains(k)) {
                problems.add(where + ": unknown key '" + k + "' (allowed: " + new TreeSet<>(allowed) + ")");
            }
        }
    }

    private static String text(JsonNode n, String key, String where, List<String> problems) {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            problems.add(where + ": " + key + " must be a non-blank string (quote dates and hashes)");
            return null;
        }
        return v.asText();
    }

    private static String optText(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }
}
