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

import com.dlap.fixtures.FixtureCase.Confirmation;
import com.dlap.fixtures.FixtureCase.ConfirmationMethod;
import com.dlap.fixtures.FixtureCase.Meta;
import com.dlap.fixtures.FixtureCase.Origin;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The loaded fixture corpus: every {@link FixtureCase} plus, per family, the {@code catalog.yaml}
 * that lists the documented operations and variants the corpus is scored against.
 *
 * <p>Two ways in:
 * <ul>
 *   <li>{@link #fromClasspath()} for tests, which finds the corpus inside this module's jar or
 *       build output and fails fast on any malformed file;</li>
 *   <li>{@link #scan(Path)} for tooling, which never throws on bad content and collects
 *       {@link #problems()} instead, so a gate can list every defect in one run.</li>
 * </ul>
 *
 * <p>Corpus layout (see {@code dlap-fixtures/README.md}):
 * <pre>
 *   dlap-fixtures/&lt;family&gt;/catalog.yaml
 *   dlap-fixtures/&lt;family&gt;/coverage-spec.yaml   independent denominator, see {@link CoverageSpec}
 *   dlap-fixtures/&lt;family&gt;/NOTICE.md
 *   dlap-fixtures/&lt;family&gt;/&lt;envelope&gt;/&lt;version&gt;/&lt;case&gt;/{input.*, expected.json, meta.yaml}
 *   dlap-fixtures/&lt;family&gt;/negative/&lt;envelope&gt;/&lt;version&gt;/&lt;case&gt;/{...}
 * </pre>
 * A family directory that holds only a {@code coverage-spec.yaml} (the spec is written before the
 * parser package adds cases) needs neither a {@code catalog.yaml} nor a {@code NOTICE.md} yet.
 */
public final class FixtureCatalog {

    /** Name of the corpus directory inside the module resources. */
    public static final String RESOURCE_ROOT = "dlap-fixtures";

    /** File that marks the corpus root on the classpath; present even in jars without directory entries. */
    public static final String ROOT_MARKER = RESOURCE_ROOT + "/README.md";

    /** Per-family list of documented operations and variants. */
    public static final String CATALOG_FILE = "catalog.yaml";

    /** Per-family attribution file; aggregated into the jar at build time. */
    public static final String NOTICE_FILE = "NOTICE.md";

    /** Reserved path segment that holds a family's negative cases. */
    public static final String NEGATIVE_DIR = "negative";

    /**
     * Name of the tooling self-test family. It is the only family whose {@code catalog.yaml} may say
     * {@code selftest: true}, and the only family whose supported rows may be gated against its own
     * catalog. The exemption is tied to this reserved name, not to the flag, because parser and
     * fixture packages write {@code catalog.yaml} and must not be able to exempt their own family.
     */
    public static final String SELFTEST_FAMILY = "selftest";

    /** Allowed shape of a family, envelope, version or case directory name. */
    static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** A documented operation: {@code service:operation}, for example {@code s3:GetObject}. */
    static final Pattern OPERATION = Pattern.compile("[^\\s:]+:\\S+");

    /** A documented variant id. */
    static final Pattern VARIANT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]*");

    /** An expected outcome, for example {@code PARSED} or {@code QUARANTINED:UNKNOWN_MAJOR_VERSION}. */
    static final Pattern OUTCOME = Pattern.compile("[A-Z][A-Z0-9_]*(:[A-Z][A-Z0-9_]*)*");

    private static final Set<String> META_KEYS = Set.of(
            "origin", "source_url", "doc_date", "repo", "commit", "license",
            "covers", "variant_ids", "expected_outcome", "anonymised", "notes",
            "confirmed_by", "generator");

    private static final Set<String> CONFIRMATION_KEYS = Set.of("method", "evidence");

    private static final Set<String> CATALOG_KEYS = Set.of("family", "description", "selftest", "envelopes");
    private static final Set<String> ENVELOPE_KEYS = Set.of("id", "versions");
    private static final Set<String> VERSION_KEYS = Set.of("id", "supported", "operations", "variants");

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private final Path root;
    private final SortedSet<String> familyNames;
    private final SortedMap<String, Family> families;
    private final SortedMap<String, CoverageSpec> specs;
    private final List<FixtureCase> cases;
    private final List<String> problems;

    private FixtureCatalog(
            Path root,
            SortedSet<String> familyNames,
            SortedMap<String, Family> families,
            SortedMap<String, CoverageSpec> specs,
            List<FixtureCase> cases,
            List<String> problems) {
        this.root = root;
        this.familyNames = familyNames;
        this.families = families;
        this.specs = specs;
        this.cases = cases;
        this.problems = problems;
    }

    /**
     * What a family documents. Scores are computed against these lists.
     *
     * @param name        the family directory name
     * @param description what the family covers
     * @param selftest    true when the family only exists to test the tooling; only
     *                    {@link #SELFTEST_FAMILY} may set it, any other family fails to load
     * @param envelopes   the documented envelopes
     */
    public record Family(String name, String description, boolean selftest, List<Envelope> envelopes) {

        /**
         * Finds one documented (envelope, version) of this family.
         *
         * @param envelope the envelope id
         * @param version  the version id
         * @return the version entry, or empty when the catalog does not declare it
         */
        public Optional<Version> version(String envelope, String version) {
            return envelopes.stream()
                    .filter(e -> e.id().equals(envelope))
                    .flatMap(e -> e.versions().stream())
                    .filter(v -> v.id().equals(version))
                    .findFirst();
        }
    }

    /**
     * One documented envelope of a family.
     *
     * @param id       the envelope id, as used in the case path
     * @param versions the documented versions
     */
    public record Envelope(String id, List<Version> versions) {
    }

    /**
     * One documented version of an envelope: the denominator of its coverage score.
     *
     * @param id         the version id, as used in the case path (a quoted string in YAML)
     * @param supported  true once a parser claims this version; supported entries are gated on score
     * @param operations the documented {@code service:operation} pairs
     * @param variants   the documented variant ids
     */
    public record Version(String id, boolean supported, List<String> operations, List<String> variants) {
    }

    /**
     * Tells whether a family is the tooling self-test family, by its reserved name only.
     *
     * @param family the family directory name
     * @return true only for {@link #SELFTEST_FAMILY}
     */
    public static boolean isToolingFamily(String family) {
        return SELFTEST_FAMILY.equals(family);
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Loads the corpus that ships on the classpath, failing on any malformed content.
     *
     * @return the corpus
     * @throws IllegalStateException when no corpus is found, more than one is found, or it has problems
     */
    public static FixtureCatalog fromClasspath() {
        return fromClasspath(FixtureCatalog.class.getClassLoader());
    }

    /**
     * Loads the corpus that is visible to a class loader, failing on any malformed content.
     *
     * @param loader the class loader to search
     * @return the corpus
     * @throws IllegalStateException when no corpus is found, more than one is found, or it has problems
     */
    public static FixtureCatalog fromClasspath(ClassLoader loader) {
        List<URL> markers;
        try {
            markers = Collections.list(loader.getResources(ROOT_MARKER));
        } catch (IOException e) {
            throw new IllegalStateException("cannot search the classpath for " + ROOT_MARKER, e);
        }
        if (markers.isEmpty()) {
            throw new IllegalStateException("no " + ROOT_MARKER + " on the classpath; is :da-log-fixtures a test dependency?");
        }
        if (markers.size() > 1) {
            throw new IllegalStateException("more than one fixture corpus on the classpath: " + markers);
        }
        return load(rootOf(markers.get(0)));
    }

    /**
     * Loads a corpus from a directory, failing on any malformed content.
     *
     * @param root the corpus root, the directory that holds the family directories
     * @return the corpus
     * @throws IllegalStateException when the corpus has problems; the message lists them all
     */
    public static FixtureCatalog load(Path root) {
        FixtureCatalog catalog = scan(root);
        if (!catalog.problems.isEmpty()) {
            throw new IllegalStateException("fixture corpus " + root + " has " + catalog.problems.size()
                    + " problem(s):\n  " + String.join("\n  ", catalog.problems));
        }
        return catalog;
    }

    /**
     * Scans a corpus directory and never throws on bad content: every defect becomes an entry of
     * {@link #problems()}, and cases or catalogs that cannot be read are left out.
     *
     * @param root the corpus root
     * @return the corpus with its problems
     */
    public static FixtureCatalog scan(Path root) {
        List<String> problems = new ArrayList<>();
        SortedSet<String> familyNames = new TreeSet<>();
        SortedMap<String, Family> families = new TreeMap<>();
        SortedMap<String, CoverageSpec> specs = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            problems.add(root + ": fixture root is not a directory");
            return new FixtureCatalog(root, familyNames, families, specs, List.of(), problems);
        }
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .forEach(familyNames::add);
        } catch (IOException e) {
            problems.add(root + ": cannot list the corpus root: " + e.getMessage());
        }
        Set<String> withoutCatalog = new TreeSet<>();
        for (String name : familyNames) {
            if (!SEGMENT.matcher(name).matches()) {
                problems.add(name + ": family directory name must match " + SEGMENT.pattern());
                continue;
            }
            Path specFile = root.resolve(name).resolve(CoverageSpec.FILE);
            boolean hasSpecFile = Files.isRegularFile(specFile);
            if (hasSpecFile) {
                CoverageSpec spec = CoverageSpec.read(specFile, name, problems);
                if (spec != null) {
                    specs.put(name, spec);
                }
            }
            if (!Files.isRegularFile(root.resolve(name).resolve(CATALOG_FILE))) {
                if (hasSpecFile) {
                    withoutCatalog.add(name);
                } else {
                    problems.add(missingCatalog(name));
                }
                continue;
            }
            Family family = readFamily(root, name, problems);
            if (family != null) {
                families.put(name, family);
            }
        }
        List<FixtureCase> cases = readCases(root, families, problems);
        for (String name : withoutCatalog) {
            // A spec may come first; cases may not come without the parser package's catalog.yaml.
            if (cases.stream().anyMatch(c -> c.family().equals(name))) {
                problems.add(missingCatalog(name));
            }
        }
        return new FixtureCatalog(root, familyNames, families, specs, List.copyOf(cases), List.copyOf(problems));
    }

    private static String missingCatalog(String family) {
        return family + "/" + CATALOG_FILE + ": missing; every family with cases needs a catalog.yaml listing "
                + "the envelopes and versions its parser claims";
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /**
     * Returns the corpus root this catalog was read from.
     *
     * @return the root directory
     */
    public Path root() {
        return root;
    }

    /**
     * Returns every case that could be read, sorted by id.
     *
     * @return all cases, including negative and selftest ones
     */
    public List<FixtureCase> cases() {
        return cases;
    }

    /**
     * Returns the cases of one family.
     *
     * @param family the family name
     * @return its cases, sorted by id
     */
    public List<FixtureCase> cases(String family) {
        return cases.stream().filter(c -> c.family().equals(family)).toList();
    }

    /**
     * Returns the name of every family directory, including those whose catalog could not be read.
     *
     * @return the family names, sorted
     */
    public SortedSet<String> familyNames() {
        return Collections.unmodifiableSortedSet(familyNames);
    }

    /**
     * Returns a family whose {@code catalog.yaml} was read.
     *
     * @param name the family name
     * @return the family, or empty when it has no readable catalog
     */
    public Optional<Family> family(String name) {
        return Optional.ofNullable(families.get(name));
    }

    /**
     * Returns every family whose {@code catalog.yaml} was read.
     *
     * @return the families, sorted by name
     */
    public List<Family> families() {
        return List.copyOf(families.values());
    }

    /**
     * Returns the independent coverage spec of a family.
     *
     * @param name the family name
     * @return the spec, or empty when the family has no readable {@code coverage-spec.yaml}
     */
    public Optional<CoverageSpec> spec(String name) {
        return Optional.ofNullable(specs.get(name));
    }

    /**
     * Returns every readable coverage spec.
     *
     * @return the specs, sorted by family name
     */
    public List<CoverageSpec> specs() {
        return List.copyOf(specs.values());
    }

    /**
     * Returns the defects found while scanning.
     *
     * @return the problems; empty for a well-formed corpus
     */
    public List<String> problems() {
        return problems;
    }

    // ------------------------------------------------------------------
    // Classpath support
    // ------------------------------------------------------------------

    private static Path rootOf(URL marker) {
        try {
            URI uri = marker.toURI();
            if ("jar".equals(uri.getScheme())) {
                try {
                    FileSystems.newFileSystem(uri, Map.of());
                } catch (FileSystemAlreadyExistsException alreadyOpen) {
                    // another load of the same jar opened it; reuse it
                }
            }
            return Paths.get(uri).getParent();
        } catch (URISyntaxException | IOException e) {
            throw new IllegalStateException("cannot open the fixture corpus at " + marker, e);
        }
    }

    // ------------------------------------------------------------------
    // catalog.yaml
    // ------------------------------------------------------------------

    private static Family readFamily(Path root, String name, List<String> problems) {
        Path file = root.resolve(name).resolve(CATALOG_FILE);
        String where = name + "/" + CATALOG_FILE;
        JsonNode node = readYaml(file, where, problems);
        if (node == null) {
            return null;
        }
        if (!node.isObject()) {
            problems.add(where + ": must be a YAML mapping");
            return null;
        }
        int before = problems.size();
        rejectUnknownKeys(node, CATALOG_KEYS, where, problems);
        String declared = optString(node, "family", where, problems);
        if (declared != null && !declared.equals(name)) {
            problems.add(where + ": family '" + declared + "' does not match the directory name '" + name + "'");
        }
        String description = optString(node, "description", where, problems);
        boolean selftest = optBoolean(node, "selftest", false, where, problems);
        if (selftest && !isToolingFamily(name)) {
            problems.add(where + ": 'selftest: true' is reserved for the tooling family '" + SELFTEST_FAMILY
                    + "'; a log family cannot exempt itself from its independent " + CoverageSpec.FILE);
        } else if (!selftest && isToolingFamily(name)) {
            problems.add(where + ": the tooling family '" + SELFTEST_FAMILY + "' must say 'selftest: true'");
        }
        List<Envelope> envelopes = new ArrayList<>();
        JsonNode envNodes = node.get("envelopes");
        if (envNodes == null || !envNodes.isArray() || envNodes.isEmpty()) {
            problems.add(where + ": 'envelopes' must be a non-empty list");
        } else {
            Set<String> seen = new HashSet<>();
            for (JsonNode envNode : envNodes) {
                Envelope env = readEnvelope(envNode, where, problems);
                if (env != null) {
                    if (!seen.add(env.id())) {
                        problems.add(where + ": duplicate envelope id '" + env.id() + "'");
                    }
                    envelopes.add(env);
                }
            }
        }
        if (problems.size() > before) {
            return null;
        }
        return new Family(name, description == null ? "" : description, selftest, List.copyOf(envelopes));
    }

    private static Envelope readEnvelope(JsonNode node, String where, List<String> problems) {
        if (!node.isObject()) {
            problems.add(where + ": every envelope must be a mapping with 'id' and 'versions'");
            return null;
        }
        rejectUnknownKeys(node, ENVELOPE_KEYS, where, problems);
        String id = reqId(node, "id", where + " envelope", problems);
        if (id != null && NEGATIVE_DIR.equals(id)) {
            problems.add(where + ": '" + NEGATIVE_DIR + "' is reserved and cannot be an envelope id");
            id = null;
        }
        String envWhere = where + " envelope " + (id == null ? "?" : id);
        JsonNode versionNodes = node.get("versions");
        if (versionNodes == null || !versionNodes.isArray() || versionNodes.isEmpty()) {
            problems.add(envWhere + ": 'versions' must be a non-empty list");
            return null;
        }
        List<Version> versions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode versionNode : versionNodes) {
            Version v = readVersion(versionNode, envWhere, problems);
            if (v != null) {
                if (!seen.add(v.id())) {
                    problems.add(envWhere + ": duplicate version id '" + v.id() + "'");
                }
                versions.add(v);
            }
        }
        return id == null ? null : new Envelope(id, List.copyOf(versions));
    }

    private static Version readVersion(JsonNode node, String where, List<String> problems) {
        if (!node.isObject()) {
            problems.add(where + ": every version must be a mapping");
            return null;
        }
        rejectUnknownKeys(node, VERSION_KEYS, where, problems);
        int before = problems.size();
        String id = reqId(node, "id", where + " version", problems);
        String vWhere = where + " version " + (id == null ? "?" : id);
        boolean supported = optBoolean(node, "supported", false, vWhere, problems);
        List<String> operations = reqList(node, "operations", OPERATION, vWhere, problems);
        List<String> variants = reqList(node, "variants", VARIANT_ID, vWhere, problems);
        if (problems.size() > before || id == null) {
            return null;
        }
        return new Version(id, supported, List.copyOf(operations), List.copyOf(variants));
    }

    // ------------------------------------------------------------------
    // Cases
    // ------------------------------------------------------------------

    private static List<FixtureCase> readCases(Path root, Map<String, Family> families, List<String> problems) {
        List<Path> dirs = caseDirs(root, problems);
        Set<Path> caseSet = new HashSet<>(dirs);
        List<FixtureCase> out = new ArrayList<>();
        for (Path dir : dirs) {
            List<String> segments = new ArrayList<>();
            for (Path p : root.relativize(dir)) {
                segments.add(p.toString());
            }
            String where = String.join("/", segments);
            boolean nested = false;
            for (Path parent = dir.getParent(); parent != null && !parent.equals(root); parent = parent.getParent()) {
                if (caseSet.contains(parent)) {
                    problems.add(where + ": case directory is nested inside another case directory");
                    nested = true;
                    break;
                }
            }
            if (nested) {
                continue;
            }
            boolean negative = segments.size() > 1 && NEGATIVE_DIR.equals(segments.get(1));
            int wanted = negative ? 5 : 4;
            if (segments.size() != wanted) {
                problems.add(where + ": a case must sit at <family>/<envelope>/<version>/<case> or "
                        + "<family>/negative/<envelope>/<version>/<case>");
                continue;
            }
            if (!segments.stream().allMatch(s -> SEGMENT.matcher(s).matches())) {
                problems.add(where + ": every path segment must match " + SEGMENT.pattern());
                continue;
            }
            int at = negative ? 2 : 1;
            String family = segments.get(0);
            String envelope = segments.get(at);
            String version = segments.get(at + 1);
            String name = segments.get(at + 2);
            Path metaFile = dir.resolve(FixtureCase.META_FILE);
            if (!Files.isRegularFile(metaFile)) {
                problems.add(where + ": case has no " + FixtureCase.META_FILE + "; every case needs provenance metadata");
                continue;
            }
            JsonNode node = readYaml(metaFile, where + "/" + FixtureCase.META_FILE, problems);
            if (node == null) {
                continue;
            }
            Meta meta = parseMeta(node, where + "/" + FixtureCase.META_FILE, problems);
            if (meta == null) {
                continue;
            }
            Family fam = families.get(family);
            out.add(new FixtureCase(family, negative, envelope, version, name, dir, meta, fam != null && fam.selftest()));
        }
        out.sort(Comparator.comparing(FixtureCase::id));
        return out;
    }

    /** A directory is a case directory when it directly holds meta.yaml, expected.json or an input.* file. */
    private static List<Path> caseDirs(Path root, List<String> problems) {
        List<Path> dirs = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(root)) {
                        if (dir.getFileName().toString().startsWith(".")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (isCaseDir(dir)) {
                            dirs.add(dir);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            problems.add(root + ": cannot walk the corpus: " + e.getMessage());
        }
        dirs.sort(Comparator.comparing(Path::toString));
        return dirs;
    }

    private static boolean isCaseDir(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).anyMatch(n ->
                    n.equals(FixtureCase.META_FILE)
                            || n.equals(FixtureCase.EXPECTED_FILE)
                            || n.startsWith(FixtureCase.INPUT_PREFIX));
        }
    }

    private static Meta parseMeta(JsonNode node, String where, List<String> problems) {
        if (!node.isObject()) {
            problems.add(where + ": must be a YAML mapping");
            return null;
        }
        int before = problems.size();
        rejectUnknownKeys(node, META_KEYS, where, problems);
        String originId = reqString(node, "origin", where, problems);
        Origin origin = null;
        if (originId != null) {
            origin = Origin.fromId(originId).orElse(null);
            if (origin == null) {
                problems.add(where + ": origin '" + originId + "' is not one of "
                        + Arrays.stream(Origin.values()).map(Origin::id).toList());
            }
        }
        String sourceUrl = optString(node, "source_url", where, problems);
        LocalDate docDate = optDate(node, "doc_date", where, problems);
        String repo = optString(node, "repo", where, problems);
        String commit = optString(node, "commit", where, problems);
        String license = reqString(node, "license", where, problems);
        List<String> covers = reqList(node, "covers", OPERATION, where, problems);
        List<String> variantIds = reqList(node, "variant_ids", VARIANT_ID, where, problems);
        String outcome = reqString(node, "expected_outcome", where, problems);
        if (outcome != null && !OUTCOME.matcher(outcome).matches()) {
            problems.add(where + ": expected_outcome '" + outcome + "' must look like PARSED or "
                    + "QUARANTINED:UNKNOWN_MAJOR_VERSION");
        }
        Boolean anonymised = reqBoolean(node, "anonymised", where, problems);
        String notes = optString(node, "notes", where, problems);
        List<Confirmation> confirmedBy = parseConfirmations(node.get("confirmed_by"), where, problems);
        String generator = optString(node, "generator", where, problems);
        if (problems.size() > before) {
            return null;
        }
        return new Meta(origin, sourceUrl, docDate, repo, commit, license,
                List.copyOf(covers), List.copyOf(variantIds), outcome, anonymised, notes,
                List.copyOf(confirmedBy), generator);
    }

    /** Reads the optional {@code confirmed_by} list: mappings with a known {@code method} and an {@code evidence} string. */
    private static List<Confirmation> parseConfirmations(JsonNode v, String where, List<String> problems) {
        List<Confirmation> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isArray()) {
            problems.add(where + ": 'confirmed_by' must be a list of {method, evidence} mappings");
            return out;
        }
        for (JsonNode item : v) {
            if (!item.isObject()) {
                problems.add(where + ": every 'confirmed_by' entry must be a mapping with 'method' and 'evidence'");
                continue;
            }
            rejectUnknownKeys(item, CONFIRMATION_KEYS, where + " confirmed_by", problems);
            String methodId = reqString(item, "method", where + " confirmed_by", problems);
            String evidence = reqString(item, "evidence", where + " confirmed_by", problems);
            ConfirmationMethod method = null;
            if (methodId != null) {
                method = ConfirmationMethod.fromId(methodId).orElse(null);
                if (method == null) {
                    problems.add(where + ": confirmed_by method '" + methodId + "' is not one of "
                            + Arrays.stream(ConfirmationMethod.values()).map(ConfirmationMethod::id).toList());
                }
            }
            if (method != null && evidence != null) {
                out.add(new Confirmation(method, evidence));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // YAML helpers, shared with CoverageSpec. Each adds a line to problems instead of throwing.
    // ------------------------------------------------------------------

    /** Reads a YAML file as a tree; null (and a problem) when it is empty or does not parse. */
    static JsonNode readYaml(Path file, String where, List<String> problems) {
        try (InputStream in = Files.newInputStream(file)) {
            JsonNode node = YAML.readTree(in);
            if (node == null || node.isMissingNode() || node.isNull()) {
                problems.add(where + ": file is empty");
                return null;
            }
            return node;
        } catch (IOException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            problems.add(where + ": cannot parse YAML: " + message.lines().findFirst().orElse(message));
            return null;
        }
    }

    /** Adds a problem for every key of the mapping that is not allowed. */
    static void rejectUnknownKeys(JsonNode node, Set<String> allowed, String where, List<String> problems) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!allowed.contains(key)) {
                problems.add(where + ": unknown key '" + key + "' (allowed: " + new TreeSet<>(allowed) + ")");
            }
        }
    }

    /** Returns a required non-blank string value, or null with a problem. */
    static String reqString(JsonNode node, String key, String where, List<String> problems) {
        JsonNode v = node.get(key);
        if (v == null) {
            problems.add(where + ": missing required key '" + key + "'");
            return null;
        }
        return text(v, key, where, problems);
    }

    /** Returns an optional non-blank string value; null when absent. */
    static String optString(JsonNode node, String key, String where, List<String> problems) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        return text(v, key, where, problems);
    }

    /** Returns the text of a scalar that must be a non-blank string. */
    static String text(JsonNode v, String key, String where, List<String> problems) {
        if (!v.isTextual()) {
            problems.add(where + ": '" + key + "' must be a string (quote it in YAML so numbers and dates stay text)");
            return null;
        }
        if (v.asText().isBlank()) {
            problems.add(where + ": '" + key + "' must not be blank");
            return null;
        }
        return v.asText();
    }

    /** Returns a required string that must be a valid path segment. */
    static String reqId(JsonNode node, String key, String where, List<String> problems) {
        String id = reqString(node, key, where, problems);
        if (id != null && !SEGMENT.matcher(id).matches()) {
            problems.add(where + ": '" + key + "' value '" + id + "' must match " + SEGMENT.pattern());
            return null;
        }
        return id;
    }

    /** Returns a required boolean, or null with a problem. */
    static Boolean reqBoolean(JsonNode node, String key, String where, List<String> problems) {
        JsonNode v = node.get(key);
        if (v == null) {
            problems.add(where + ": missing required key '" + key + "'");
            return null;
        }
        if (!v.isBoolean()) {
            problems.add(where + ": '" + key + "' must be true or false");
            return null;
        }
        return v.asBoolean();
    }

    /** Returns an optional boolean, or the fallback when absent or malformed. */
    static boolean optBoolean(JsonNode node, String key, boolean fallback, String where, List<String> problems) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isBoolean()) {
            problems.add(where + ": '" + key + "' must be true or false");
            return fallback;
        }
        return v.asBoolean();
    }

    /** Returns a required ISO date, or null with a problem. */
    static LocalDate reqDate(JsonNode node, String key, String where, List<String> problems) {
        if (node.get(key) == null) {
            problems.add(where + ": missing required key '" + key + "'");
            return null;
        }
        return optDate(node, key, where, problems);
    }

    /** Returns an optional ISO date; null when absent. */
    static LocalDate optDate(JsonNode node, String key, String where, List<String> problems) {
        String s = optString(node, key, where, problems);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            problems.add(where + ": '" + key + "' must be an ISO date such as 2026-10-18, got '" + s + "'");
            return null;
        }
    }

    /**
     * Returns a required list of distinct strings. Every entry must match {@code shape}, or only be a
     * non-blank string when {@code shape} is null.
     */
    static List<String> reqList(JsonNode node, String key, Pattern shape, String where, List<String> problems) {
        JsonNode v = node.get(key);
        if (v == null) {
            problems.add(where + ": missing required key '" + key + "' (use [] for none)");
            return List.of();
        }
        if (!v.isArray()) {
            problems.add(where + ": '" + key + "' must be a list (use [] for none)");
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (JsonNode item : v) {
            boolean ok = item.isTextual() && !item.asText().isBlank()
                    && (shape == null || shape.matcher(item.asText()).matches());
            if (!ok) {
                problems.add(where + ": '" + key + "' entry " + item + " must be a "
                        + (shape == null ? "non-blank string" : "string matching " + shape.pattern()));
                continue;
            }
            if (!out.add(item.asText())) {
                duplicates.add(item.asText());
            }
        }
        if (!duplicates.isEmpty()) {
            problems.add(where + ": '" + key + "' repeats " + duplicates);
        }
        return new ArrayList<>(out);
    }
}
