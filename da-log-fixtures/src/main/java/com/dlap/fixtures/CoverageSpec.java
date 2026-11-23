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

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The independent coverage specification of one log family: {@code <family>/coverage-spec.yaml}.
 *
 * <p>It is the denominator of the coverage score. It is written from the vendor documentation and
 * the research contract tables, not by the package that builds the parser, so a parser author
 * cannot grade their own coverage (hardening plan, critic item 6). Parser packages do not edit it;
 * {@code scripts/ci-guards/post-test/coverage-spec-lock.sh} fails any commit that changes a spec
 * together with parser code or the cases graded against it.
 *
 * <p>Shape:
 * <pre>
 *   spec: dlap-coverage-spec/1
 *   family: gcpaudit                      must equal the directory name
 *   title, source_kind, written_by, written_on, research[], operation_id_rule, version_rule
 *   envelopes: [{id, title, doc_url, versions: [version ids], status?, reason?, notes?}]
 *   versions:  [{id, title, doc_url, detect, required_fields?, optional_fields?, notes?}]
 *   operations: [{id, doc_url, research?, expected_category?, versions?, envelopes?, status?, reason?, title?, notes?}]
 *   variants:   [{id, title, doc_url, research?, negative?, versions?, envelopes?, status?, reason?, notes?}]
 * </pre>
 * Every (envelope, version) pair listed under an envelope is one scored cell. An operation or a
 * variant applies to every cell unless it narrows itself with {@code versions} or {@code envelopes}.
 * Only entries whose {@code status} is {@code documented} (the default) are scored; {@code unverified}
 * and {@code deferred} entries are listed in the report and wait for a capture or a decision. An
 * envelope that is not documented produces no cells.
 */
public final class CoverageSpec {

    /** File name of the spec inside a family directory. */
    public static final String FILE = "coverage-spec.yaml";

    /** The value of the {@code spec} key this reader understands. */
    public static final String SCHEMA = "dlap-coverage-spec/1";

    /** Event categories of the log-event contract (docs/specs/log-event.md section 4.3), without the prefix. */
    public static final Set<String> CATEGORIES = Set.of(
            "MANAGEMENT", "DATA_READ", "DATA_WRITE", "PERMISSION", "DATA_READ_OBSERVED");

    private static final Set<String> TOP_KEYS = Set.of(
            "spec", "family", "title", "source_kind", "written_by", "written_on", "research",
            "operation_id_rule", "version_rule", "notes", "envelopes", "versions", "operations", "variants");
    private static final Set<String> ENVELOPE_KEYS = Set.of(
            "id", "title", "doc_url", "versions", "status", "reason", "notes");
    private static final Set<String> VERSION_KEYS = Set.of(
            "id", "title", "doc_url", "detect", "required_fields", "optional_fields", "notes");
    private static final Set<String> OPERATION_KEYS = Set.of(
            "id", "title", "doc_url", "research", "expected_category", "versions", "envelopes",
            "status", "reason", "notes");
    private static final Set<String> VARIANT_KEYS = Set.of(
            "id", "title", "doc_url", "research", "negative", "versions", "envelopes",
            "status", "reason", "notes");

    /** Whether a spec entry is scored. */
    public enum Status {
        /** Stated by a vendor page or a published schema; scored. */
        DOCUMENTED("documented"),
        /** Known to exist but not confirmed from an opened page; listed, not scored, until a capture confirms it. */
        UNVERIFIED("unverified"),
        /** Documented, but out of scope by a recorded decision (for example ADR-0023); listed, not scored. */
        DEFERRED("deferred");

        private final String id;

        Status(String id) {
            this.id = id;
        }

        /**
         * Returns the value used in the spec file.
         *
         * @return the lower-case id
         */
        public String id() {
            return id;
        }

        static Optional<Status> fromId(String id) {
            for (Status s : values()) {
                if (s.id.equals(id)) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * One documented transport shape of the family.
     *
     * @param id       the envelope id, as used in case paths
     * @param title    what it is
     * @param docUrl   the vendor page that documents it
     * @param versions the version ids scored in this envelope, in report order
     * @param status   whether its cells are scored
     * @param reason   why it is not scored; null when documented
     */
    public record Envelope(String id, String title, String docUrl, List<String> versions, Status status, String reason) {
    }

    /**
     * One documented format version of the family.
     *
     * @param id             the version id, as used in case paths
     * @param title          what it is
     * @param docUrl         the vendor page that documents it
     * @param detect         how a parser recognises it on a record
     * @param requiredFields fields a record of this version always carries (documentation for parser authors)
     */
    public record Version(String id, String title, String docUrl, String detect, List<String> requiredFields) {
    }

    /**
     * One documented operation or variant.
     *
     * @param id               {@code service:operation} for an operation, a variant id otherwise
     * @param title            a short description; may be null on operations
     * @param docUrl           the vendor page, schema or public sample that documents it
     * @param research         the research report section it was taken from; may be null
     * @param status           whether it is scored
     * @param reason           why it is not scored; null when documented
     * @param expectedCategory the event category the research assigns (operations only); may be null
     * @param negative         true when only a negative case (quarantine or reject) can cover it (variants only)
     * @param versions         the versions it applies to; empty means all
     * @param envelopes        the envelopes it applies to; empty means all
     */
    public record Entry(
            String id,
            String title,
            String docUrl,
            String research,
            Status status,
            String reason,
            String expectedCategory,
            boolean negative,
            List<String> versions,
            List<String> envelopes) {

        /**
         * Tells whether the entry counts toward the score.
         *
         * @return true when its status is documented
         */
        public boolean scored() {
            return status == Status.DOCUMENTED;
        }

        /**
         * Tells whether the entry applies to one (envelope, version) cell.
         *
         * @param envelope the envelope id
         * @param version  the version id
         * @return true when neither restriction excludes the cell
         */
        public boolean appliesTo(String envelope, String version) {
            return (versions.isEmpty() || versions.contains(version))
                    && (envelopes.isEmpty() || envelopes.contains(envelope));
        }
    }

    /**
     * One scored (envelope, version) pair.
     *
     * @param envelope the envelope id
     * @param version  the version id
     */
    public record Cell(String envelope, String version) {
    }

    private final String family;
    private final String title;
    private final String writtenBy;
    private final LocalDate writtenOn;
    private final List<Envelope> envelopes;
    private final List<Version> versions;
    private final List<Entry> operations;
    private final List<Entry> variants;

    private CoverageSpec(
            String family,
            String title,
            String writtenBy,
            LocalDate writtenOn,
            List<Envelope> envelopes,
            List<Version> versions,
            List<Entry> operations,
            List<Entry> variants) {
        this.family = family;
        this.title = title;
        this.writtenBy = writtenBy;
        this.writtenOn = writtenOn;
        this.envelopes = envelopes;
        this.versions = versions;
        this.operations = operations;
        this.variants = variants;
    }

    /**
     * Returns the family the spec belongs to.
     *
     * @return the family directory name
     */
    public String family() {
        return family;
    }

    /**
     * Returns the spec title.
     *
     * @return the title
     */
    public String title() {
        return title;
    }

    /**
     * Returns who wrote the spec (a package id such as SPEC-01).
     *
     * @return the author
     */
    public String writtenBy() {
        return writtenBy;
    }

    /**
     * Returns the date the spec was written or last revised.
     *
     * @return the date
     */
    public LocalDate writtenOn() {
        return writtenOn;
    }

    /**
     * Returns every envelope, documented or not, in file order.
     *
     * @return the envelopes
     */
    public List<Envelope> envelopes() {
        return envelopes;
    }

    /**
     * Returns every version in file order.
     *
     * @return the versions
     */
    public List<Version> versions() {
        return versions;
    }

    /**
     * Returns every operation entry in file order.
     *
     * @return the operations
     */
    public List<Entry> operations() {
        return operations;
    }

    /**
     * Returns every variant entry in file order.
     *
     * @return the variants
     */
    public List<Entry> variants() {
        return variants;
    }

    /**
     * Returns the scored cells: every version listed under every documented envelope, in file order.
     *
     * @return the cells
     */
    public List<Cell> cells() {
        List<Cell> out = new ArrayList<>();
        for (Envelope e : envelopes) {
            if (e.status() != Status.DOCUMENTED) {
                continue;
            }
            for (String v : e.versions()) {
                out.add(new Cell(e.id(), v));
            }
        }
        return out;
    }

    /**
     * Tells whether a cell is scored by this spec.
     *
     * @param envelope the envelope id
     * @param version  the version id
     * @return true when the cell is one of {@link #cells()}
     */
    public boolean hasCell(String envelope, String version) {
        return cells().contains(new Cell(envelope, version));
    }

    /**
     * Returns the operations that apply to one cell, scored or not, in file order.
     *
     * @param envelope the envelope id
     * @param version  the version id
     * @return the applicable operations
     */
    public List<Entry> operations(String envelope, String version) {
        return operations.stream().filter(o -> o.appliesTo(envelope, version)).toList();
    }

    /**
     * Returns the variants that apply to one cell, scored or not, in file order.
     *
     * @param envelope the envelope id
     * @param version  the version id
     * @return the applicable variants
     */
    public List<Entry> variants(String envelope, String version) {
        return variants.stream().filter(v -> v.appliesTo(envelope, version)).toList();
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Reads and validates a spec file. Never throws on bad content: every defect is added to
     * {@code problems}, and null is returned when the spec cannot be used.
     *
     * @param file     the {@code coverage-spec.yaml}
     * @param family   the family directory name
     * @param problems where defects are collected
     * @return the spec, or null when it has problems
     */
    static CoverageSpec read(Path file, String family, List<String> problems) {
        String where = family + "/" + FILE;
        if (!Files.isRegularFile(file)) {
            return null;
        }
        JsonNode node = FixtureCatalog.readYaml(file, where, problems);
        if (node == null) {
            return null;
        }
        if (!node.isObject()) {
            problems.add(where + ": must be a YAML mapping");
            return null;
        }
        int before = problems.size();
        FixtureCatalog.rejectUnknownKeys(node, TOP_KEYS, where, problems);
        String schema = FixtureCatalog.reqString(node, "spec", where, problems);
        if (schema != null && !SCHEMA.equals(schema)) {
            problems.add(where + ": 'spec' must be '" + SCHEMA + "', got '" + schema + "'");
        }
        String declared = FixtureCatalog.reqString(node, "family", where, problems);
        if (declared != null && !declared.equals(family)) {
            problems.add(where + ": family '" + declared + "' does not match the directory name '" + family + "'");
        }
        String title = FixtureCatalog.reqString(node, "title", where, problems);
        FixtureCatalog.reqString(node, "source_kind", where, problems);
        String writtenBy = FixtureCatalog.reqString(node, "written_by", where, problems);
        LocalDate writtenOn = FixtureCatalog.reqDate(node, "written_on", where, problems);
        List<String> research = FixtureCatalog.reqList(node, "research", null, where, problems);
        if (node.has("research") && research.isEmpty()) {
            problems.add(where + ": 'research' must name at least one source the spec was written from");
        }
        FixtureCatalog.reqString(node, "operation_id_rule", where, problems);
        FixtureCatalog.reqString(node, "version_rule", where, problems);
        FixtureCatalog.optString(node, "notes", where, problems);

        List<Version> versions = readVersions(node.get("versions"), where, problems);
        Set<String> versionIds = new LinkedHashSet<>();
        versions.forEach(v -> versionIds.add(v.id()));
        List<Envelope> envelopes = readEnvelopes(node.get("envelopes"), versionIds, where, problems);
        Set<String> envelopeIds = new LinkedHashSet<>();
        envelopes.forEach(e -> envelopeIds.add(e.id()));
        List<Entry> operations = readEntries(node.get("operations"), "operations", true,
                versionIds, envelopeIds, where, problems);
        List<Entry> variants = readEntries(node.get("variants"), "variants", false,
                versionIds, envelopeIds, where, problems);

        if (problems.size() > before) {
            return null;
        }
        CoverageSpec spec = new CoverageSpec(family, title, writtenBy, writtenOn,
                List.copyOf(envelopes), List.copyOf(versions), List.copyOf(operations), List.copyOf(variants));
        Set<String> used = new HashSet<>();
        for (Envelope e : envelopes) {
            used.addAll(e.versions());
        }
        for (String v : versionIds) {
            if (!used.contains(v)) {
                problems.add(where + ": version '" + v + "' is listed by no envelope");
            }
        }
        for (Cell cell : spec.cells()) {
            boolean anyScored = spec.operations(cell.envelope(), cell.version()).stream().anyMatch(Entry::scored);
            if (!anyScored) {
                problems.add(where + ": envelope '" + cell.envelope() + "' version '" + cell.version()
                        + "' has no documented operation; a scored cell needs at least one");
            }
        }
        return problems.size() > before ? null : spec;
    }

    private static List<Version> readVersions(JsonNode nodes, String where, List<String> problems) {
        List<Version> out = new ArrayList<>();
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
            problems.add(where + ": 'versions' must be a non-empty list");
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode n : nodes) {
            if (!n.isObject()) {
                problems.add(where + ": every version must be a mapping");
                continue;
            }
            FixtureCatalog.rejectUnknownKeys(n, VERSION_KEYS, where + " version", problems);
            String id = FixtureCatalog.reqId(n, "id", where + " version", problems);
            String vWhere = where + " version " + (id == null ? "?" : id);
            String title = FixtureCatalog.reqString(n, "title", vWhere, problems);
            String docUrl = reqUrl(n, vWhere, problems);
            String detect = FixtureCatalog.reqString(n, "detect", vWhere, problems);
            List<String> required = n.has("required_fields")
                    ? FixtureCatalog.reqList(n, "required_fields", null, vWhere, problems) : List.of();
            if (n.has("optional_fields")) {
                FixtureCatalog.reqList(n, "optional_fields", null, vWhere, problems);
            }
            FixtureCatalog.optString(n, "notes", vWhere, problems);
            if (id == null) {
                continue;
            }
            if (!seen.add(id)) {
                problems.add(where + ": duplicate version id '" + id + "'");
            }
            out.add(new Version(id, title, docUrl, detect, List.copyOf(required)));
        }
        return out;
    }

    private static List<Envelope> readEnvelopes(JsonNode nodes, Set<String> versionIds, String where, List<String> problems) {
        List<Envelope> out = new ArrayList<>();
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
            problems.add(where + ": 'envelopes' must be a non-empty list");
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode n : nodes) {
            if (!n.isObject()) {
                problems.add(where + ": every envelope must be a mapping");
                continue;
            }
            FixtureCatalog.rejectUnknownKeys(n, ENVELOPE_KEYS, where + " envelope", problems);
            String id = FixtureCatalog.reqId(n, "id", where + " envelope", problems);
            if (FixtureCatalog.NEGATIVE_DIR.equals(id)) {
                problems.add(where + ": '" + FixtureCatalog.NEGATIVE_DIR + "' is reserved and cannot be an envelope id");
                id = null;
            }
            String eWhere = where + " envelope " + (id == null ? "?" : id);
            String title = FixtureCatalog.reqString(n, "title", eWhere, problems);
            String docUrl = reqUrl(n, eWhere, problems);
            List<String> versions = FixtureCatalog.reqList(n, "versions", FixtureCatalog.SEGMENT, eWhere, problems);
            if (n.has("versions") && versions.isEmpty()) {
                problems.add(eWhere + ": 'versions' must list at least one version");
            }
            for (String v : versions) {
                if (!versionIds.contains(v)) {
                    problems.add(eWhere + ": version '" + v + "' is not declared under 'versions'");
                }
            }
            Status status = readStatus(n, eWhere, problems);
            String reason = readReason(n, status, eWhere, problems);
            FixtureCatalog.optString(n, "notes", eWhere, problems);
            if (id == null) {
                continue;
            }
            if (!seen.add(id)) {
                problems.add(where + ": duplicate envelope id '" + id + "'");
            }
            out.add(new Envelope(id, title, docUrl, List.copyOf(versions), status, reason));
        }
        return out;
    }

    private static List<Entry> readEntries(
            JsonNode nodes,
            String key,
            boolean operations,
            Set<String> versionIds,
            Set<String> envelopeIds,
            String where,
            List<String> problems) {
        List<Entry> out = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) {
            problems.add(where + ": '" + key + "' must be a list");
            return out;
        }
        if (operations && nodes.isEmpty()) {
            problems.add(where + ": 'operations' must not be empty");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode n : nodes) {
            if (!n.isObject()) {
                problems.add(where + ": every entry of '" + key + "' must be a mapping");
                continue;
            }
            String kind = operations ? "operation" : "variant";
            FixtureCatalog.rejectUnknownKeys(n, operations ? OPERATION_KEYS : VARIANT_KEYS, where + " " + kind, problems);
            String id = FixtureCatalog.reqString(n, "id", where + " " + kind, problems);
            if (id != null) {
                boolean shaped = operations
                        ? FixtureCatalog.OPERATION.matcher(id).matches()
                        : FixtureCatalog.VARIANT_ID.matcher(id).matches();
                if (!shaped) {
                    problems.add(where + " " + kind + " '" + id + "': id must match "
                            + (operations ? FixtureCatalog.OPERATION : FixtureCatalog.VARIANT_ID).pattern());
                    id = null;
                }
            }
            String eWhere = where + " " + kind + " " + (id == null ? "?" : id);
            String title = operations
                    ? FixtureCatalog.optString(n, "title", eWhere, problems)
                    : FixtureCatalog.reqString(n, "title", eWhere, problems);
            String docUrl = reqUrl(n, eWhere, problems);
            String research = FixtureCatalog.optString(n, "research", eWhere, problems);
            Status status = readStatus(n, eWhere, problems);
            String reason = readReason(n, status, eWhere, problems);
            String category = operations ? FixtureCatalog.optString(n, "expected_category", eWhere, problems) : null;
            if (category != null && !CATEGORIES.contains(category)) {
                problems.add(eWhere + ": expected_category '" + category + "' is not one of " + new TreeSet<>(CATEGORIES));
            }
            boolean negative = !operations && FixtureCatalog.optBoolean(n, "negative", false, eWhere, problems);
            List<String> versions = n.has("versions")
                    ? FixtureCatalog.reqList(n, "versions", FixtureCatalog.SEGMENT, eWhere, problems) : List.of();
            List<String> envelopes = n.has("envelopes")
                    ? FixtureCatalog.reqList(n, "envelopes", FixtureCatalog.SEGMENT, eWhere, problems) : List.of();
            for (String v : versions) {
                if (!versionIds.contains(v)) {
                    problems.add(eWhere + ": version '" + v + "' is not declared under 'versions'");
                }
            }
            for (String e : envelopes) {
                if (!envelopeIds.contains(e)) {
                    problems.add(eWhere + ": envelope '" + e + "' is not declared under 'envelopes'");
                }
            }
            if (n.has("versions") && versions.isEmpty() || n.has("envelopes") && envelopes.isEmpty()) {
                problems.add(eWhere + ": an empty 'versions' or 'envelopes' list applies to nothing; omit the key to mean all");
            }
            FixtureCatalog.optString(n, "notes", eWhere, problems);
            if (id == null) {
                continue;
            }
            if (!seen.add(id)) {
                problems.add(where + ": duplicate " + kind + " id '" + id + "'");
            }
            out.add(new Entry(id, title, docUrl, research, status, reason, category, negative,
                    List.copyOf(versions), List.copyOf(envelopes)));
        }
        return out;
    }

    private static String reqUrl(JsonNode n, String where, List<String> problems) {
        String url = FixtureCatalog.reqString(n, "doc_url", where, problems);
        if (url != null && !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            problems.add(where + ": doc_url must be an https:// URL, got '" + url + "'");
            return null;
        }
        return url;
    }

    private static Status readStatus(JsonNode n, String where, List<String> problems) {
        String s = FixtureCatalog.optString(n, "status", where, problems);
        if (s == null) {
            return Status.DOCUMENTED;
        }
        Optional<Status> status = Status.fromId(s);
        if (status.isEmpty()) {
            problems.add(where + ": status '" + s + "' is not one of documented, unverified, deferred");
            return Status.DOCUMENTED;
        }
        return status.get();
    }

    private static String readReason(JsonNode n, Status status, String where, List<String> problems) {
        String reason = FixtureCatalog.optString(n, "reason", where, problems);
        if (status != Status.DOCUMENTED && reason == null) {
            problems.add(where + ": status '" + status.id() + "' needs a 'reason' (what would confirm it, or the decision that defers it)");
        }
        if (status == Status.DOCUMENTED && reason != null) {
            problems.add(where + ": 'reason' is only for unverified or deferred entries");
        }
        return reason;
    }
}
