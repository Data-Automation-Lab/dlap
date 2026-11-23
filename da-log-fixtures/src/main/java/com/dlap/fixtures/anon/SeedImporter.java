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

import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.fixtures.anon.FamilyRules.Facts;
import com.dlap.fixtures.anon.LeakScanner.Finding;
import com.dlap.fixtures.anon.SeedManifest.Case;
import com.dlap.fixtures.anon.SeedManifest.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Imports seed cases from local checkouts of public repositories into the fixture corpus, and checks
 * that the committed corpus is exactly what the import produces.
 *
 * <pre>
 * import --corpus &lt;dlap-fixtures dir&gt; --source &lt;id&gt;=&lt;checkout&gt; [--source ...] [--family &lt;f&gt;]
 * check  --corpus &lt;dlap-fixtures dir&gt; --source &lt;id&gt;=&lt;checkout&gt; [--source ...] [--family &lt;f&gt;]
 * scan   --root &lt;dir&gt;
 * list   --corpus &lt;dlap-fixtures dir&gt;
 * </pre>
 *
 * <p>{@code import} reads {@code <corpus>/<family>/seeds.yaml}, anonymises every selected record with
 * {@link Anonymiser}, writes {@code input.*}, {@code expected.json} and {@code meta.yaml} per case, and
 * refuses to write a case in which {@link LeakScanner} still finds an identifier. {@code check} makes
 * the same cases in memory and fails when a committed file differs, which proves that the import is
 * deterministic and that nobody edited the data by hand. {@code scan} runs the leak scanner over a
 * directory. {@code list} prints every seed case with its repository, commit, licence and date. The commands
 * exit 1 on a finding, a difference or a corpus problem and 2 on a usage error.
 *
 * <p>The checkouts are plain directories (for example {@code git clone}, then {@code git checkout
 * <commit>}); the tool reads named files only and never executes anything from them.
 */
public final class SeedImporter {

    /** The file name of every case's expected result. */
    private static final String EXPECTED = "expected.json";

    private static final Pattern PLAIN_YAML = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/+=#()-]*");

    private SeedImporter() {
    }

    /** One file the import would write. */
    record Output(Path path, String text, boolean gzip) {
    }

    /**
     * Command line entry point.
     *
     * @param args the arguments, see the class description
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs a command.
     *
     * @param args the arguments, see the class description
     * @param out  where results go
     * @param err  where problems go
     * @return the exit code: 0 success, 1 findings or differences, 2 usage error
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return usage(err);
        }
        String command = args[0];
        Path corpus = null;
        Path root = null;
        Map<String, Path> checkouts = new LinkedHashMap<>();
        List<String> families = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--corpus" -> corpus = Path.of(args[++i]);
                case "--root" -> root = Path.of(args[++i]);
                case "--family" -> families.add(args[++i]);
                case "--source" -> {
                    String[] kv = args[++i].split("=", 2);
                    if (kv.length != 2) {
                        return usage(err);
                    }
                    checkouts.put(kv[0], Path.of(kv[1]));
                }
                default -> {
                    return usage(err);
                }
            }
        }
        try {
            return switch (command) {
                case "scan" -> root == null ? usage(err) : scan(root, out, err);
                case "list" -> corpus == null ? usage(err) : list(corpus, out, err);
                case "import", "check" -> corpus == null ? usage(err)
                        : importOrCheck(command.equals("check"), corpus, checkouts, families, out, err);
                default -> usage(err);
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("seed import failed: " + e.getMessage());
            return 1;
        }
    }

    private static int usage(PrintStream err) {
        err.println("usage: SeedImporter import|check --corpus <dir> --source <id>=<checkout> [--source ...] [--family <f>]");
        err.println("       SeedImporter scan --root <dir>");
        err.println("       SeedImporter list --corpus <dir>");
        return 2;
    }

    /**
     * Prints every seed case with its repository, commit, licence and date, tab separated, one per line.
     * It reads the committed {@code meta.yaml} files only, so it needs no checkout.
     */
    private static int list(Path corpus, PrintStream out, PrintStream err) {
        FixtureCatalog catalog = FixtureCatalog.scan(corpus);
        if (!catalog.problems().isEmpty()) {
            catalog.problems().forEach(p -> err.println("corpus problem: " + p));
            return 1;
        }
        out.println("case\trepo\tcommit\tlicence\tdate\toperations\tvariants");
        for (FixtureCase c : catalog.cases()) {
            FixtureCase.Meta m = c.meta();
            if (m.origin() != FixtureCase.Origin.PUBLIC_DATASET) {
                continue;
            }
            out.println(String.join("\t", c.id(), m.repo(), m.commit().substring(0, 12), m.license(),
                    String.valueOf(m.docDate()), String.valueOf(m.covers().size()), String.valueOf(m.variantIds().size())));
        }
        return 0;
    }

    private static int scan(Path root, PrintStream out, PrintStream err) {
        List<Finding> findings = LeakScanner.scanTree(root);
        if (findings.isEmpty()) {
            out.println("leak scan: no identifiers found under " + root);
            return 0;
        }
        err.println("leak scan: " + findings.size() + " finding(s) under " + root);
        findings.forEach(f -> err.println("  " + f));
        return 1;
    }

    private static int importOrCheck(boolean check, Path corpus, Map<String, Path> checkouts, List<String> families,
                                     PrintStream out, PrintStream err) throws IllegalStateException {
        List<String> names = new ArrayList<>(families);
        if (names.isEmpty()) {
            try (var dirs = Files.list(corpus)) {
                dirs.filter(d -> Files.isRegularFile(d.resolve(SeedManifest.FILE))).map(d -> d.getFileName().toString())
                        .sorted().forEach(names::add);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list " + corpus, e);
            }
        }
        if (names.isEmpty()) {
            err.println("no " + SeedManifest.FILE + " under " + corpus);
            return 1;
        }
        int differences = 0;
        int written = 0;
        for (String family : names) {
            SeedManifest manifest = SeedManifest.read(corpus.resolve(family).resolve(SeedManifest.FILE));
            if (!manifest.family().equals(family)) {
                throw new IllegalArgumentException(family + "/" + SeedManifest.FILE + ": family says " + manifest.family());
            }
            List<Output> outputs = plan(corpus, manifest, checkouts, Anonymiser.standard(), out);
            for (Output o : outputs) {
                if (check) {
                    String why = differs(o);
                    if (why != null) {
                        differences++;
                        err.println("differs: " + corpus.relativize(o.path()).toString().replace('\\', '/') + " (" + why + ")");
                    }
                } else {
                    write(o);
                    written++;
                }
            }
        }
        if (check) {
            if (differences > 0) {
                err.println("check: " + differences + " file(s) differ from a fresh import");
                return 1;
            }
            out.println("check: the committed seed cases are exactly what the import produces");
            return 0;
        }
        out.println("import: wrote " + written + " file(s)");
        return 0;
    }

    // ------------------------------------------------------------------
    // Planning
    // ------------------------------------------------------------------

    /**
     * Makes every file of every case of a manifest, without touching the disk.
     *
     * @param corpus    the corpus root
     * @param manifest  the manifest
     * @param checkouts local checkouts by source id
     * @param anon      the anonymiser
     * @param log       where per-case anonymisation counts go
     * @return the files, case by case
     * @throws IllegalArgumentException when a checkout is missing, a record is incomplete, or a leak remains
     */
    static List<Output> plan(Path corpus, SeedManifest manifest, Map<String, Path> checkouts, Anonymiser anon,
                             PrintStream log) {
        FamilyRules rules = FamilyRules.of(manifest.family());
        List<Output> out = new ArrayList<>();
        for (Case c : manifest.cases()) {
            Source source = manifest.sources().get(c.source());
            Path checkout = checkouts.get(c.source());
            if (checkout == null || !Files.isDirectory(checkout)) {
                throw new IllegalArgumentException("case " + c.name() + ": pass --source " + c.source() + "=<checkout of "
                        + source.repo() + " at " + source.commit() + ">");
            }
            List<JsonNode> raw = SeedSources.read(source, checkout, c);
            JsonNode wrapped = rules.wrap(raw);
            Anonymiser.Result anonymised = anon.anonymise(wrapped);
            List<JsonNode> records = rules.unwrap(anonymised.tree());
            if (c.distinctEventIds()) {
                // The records are the anonymised tree's own nodes, so the input written below changes too.
                rules.makeEventIdsDistinct(records, manifest.family() + "/" + c.name());
            }

            String version;
            List<String> covers = new ArrayList<>();
            Set<String> variants = new LinkedHashSet<>();
            ArrayNode events = JsonNodeFactory.instance.arrayNode();
            if (c.negative()) {
                version = c.cellVersion();
                variants.addAll(c.variants());
            } else {
                Derived d;
                try {
                    d = derive(rules, records, c.gzip());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("case " + c.name() + ": " + e.getMessage(), e);
                }
                version = d.version();
                covers.addAll(d.covers());
                variants.addAll(d.variants());
                events.addAll(d.events());
            }

            Path dir = corpus.resolve(manifest.family());
            if (c.negative()) {
                dir = dir.resolve("negative");
            }
            dir = dir.resolve(rules.envelope()).resolve(version).resolve(c.name());

            String ext = rules.inputExtension();
            String input = inputText(rules, anonymised.tree());
            List<Output> files = new ArrayList<>();
            files.add(new Output(dir.resolve("input." + ext + (c.gzip() ? ".gz" : "")), input, c.gzip()));
            files.add(new Output(dir.resolve(EXPECTED), expectedText(c, events), false));
            files.add(new Output(dir.resolve("meta.yaml"), metaText(source, c, covers, variants), false));

            String id = manifest.family() + "/" + (c.negative() ? "negative/" : "") + rules.envelope() + "/" + version + "/"
                    + c.name();
            for (Output f : files) {
                List<Finding> leaks = leaksOf(id, f);
                if (!leaks.isEmpty()) {
                    throw new IllegalArgumentException("case " + c.name() + " still carries identifiers after anonymising: "
                            + leaks.subList(0, Math.min(5, leaks.size())));
                }
            }
            log.println(id + ": " + raw.size() + " record(s), replaced " + anonymised.replaced());
            out.addAll(files);
        }
        return out;
    }

    /**
     * Everything a positive case states about its records: the version cell, the operations and variants
     * it covers, and the facts a correct parser reports. {@code SeedCorpusTest} recomputes it from the
     * committed input to prove that {@code expected.json} and {@code meta.yaml} were not edited by hand.
     *
     * @param version  the version cell
     * @param covers   the operation ids, in record order
     * @param variants the variant ids, in record order, file variants last
     * @param events   one expected event per record
     */
    record Derived(String version, List<String> covers, Set<String> variants, ArrayNode events) {
    }

    /**
     * Computes the facts of the records of one positive case.
     *
     * @param rules   the family rules
     * @param records the anonymised records, in order
     * @param gzip    whether the input is stored gzip compressed
     * @return the derived facts
     * @throws IllegalArgumentException when a record lacks a field the family needs or the records span versions
     */
    static Derived derive(FamilyRules rules, List<JsonNode> records, boolean gzip) {
        Set<String> versions = new LinkedHashSet<>();
        Set<String> ops = new LinkedHashSet<>();
        Set<String> variants = new LinkedHashSet<>();
        ArrayNode events = JsonNodeFactory.instance.arrayNode();
        for (JsonNode r : records) {
            versions.add(rules.versionOf(r));
            Facts f = rules.facts(r);
            ops.add(f.operation());
            variants.addAll(f.variants());
            events.add(f.event());
        }
        if (versions.size() != 1) {
            throw new IllegalArgumentException("records span versions " + versions + "; one case holds one version");
        }
        variants.addAll(rules.fileVariants(gzip));
        return new Derived(versions.iterator().next(), List.copyOf(ops), variants, events);
    }

    /**
     * Renders the {@code expected.json} text of a positive case.
     *
     * @param events the expected events
     * @return the file text
     */
    static String expectedPositiveText(ArrayNode events) {
        ObjectNode doc = JsonNodeFactory.instance.objectNode();
        doc.put("outcome", "PARSED");
        doc.set("events", events);
        return SeedJson.pretty(doc);
    }

    private static List<Finding> leaksOf(String id, Output f) {
        String name = f.path().getFileName().toString();
        if (name.endsWith(".jsonl") || name.endsWith(".jsonl.gz")) {
            List<Finding> out = new ArrayList<>();
            for (String line : f.text().split("\n")) {
                if (!line.isBlank()) {
                    out.addAll(LeakScanner.scanJson(id, SeedJson.parse(line)));
                }
            }
            return out;
        }
        if (name.endsWith(".json") || name.endsWith(".json.gz")) {
            return LeakScanner.scanJson(id, SeedJson.parse(f.text()));
        }
        return LeakScanner.scanText(id + "/" + name, f.text());
    }

    private static String inputText(FamilyRules rules, JsonNode document) {
        if (!rules.inputExtension().equals("jsonl")) {
            return SeedJson.pretty(document);
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode line : document) {
            sb.append(SeedJson.compact(line)).append('\n');
        }
        return sb.toString();
    }

    private static String expectedText(Case c, ArrayNode events) {
        if (!c.negative()) {
            return expectedPositiveText(events);
        }
        ObjectNode doc = JsonNodeFactory.instance.objectNode();
        String[] parts = c.outcome().split(":", 2);
        doc.put("outcome", parts[0]);
        if (parts.length == 2) {
            doc.put("reason", parts[1]);
        }
        doc.set("events", JsonNodeFactory.instance.arrayNode());
        return SeedJson.pretty(doc);
    }

    private static String metaText(Source s, Case c, List<String> covers, Set<String> variants) {
        StringBuilder m = new StringBuilder();
        m.append("origin: public-dataset\n");
        m.append("repo: ").append(s.repo()).append('\n');
        m.append("commit: \"").append(s.commit()).append("\"\n");
        String base = s.url();
        m.append("source_url: ").append(c.file().contains("*") ? base.replace("/blob/", "/tree/") : base + "/" + c.file())
                .append('\n');
        m.append("doc_date: ").append(s.commitDate()).append('\n');
        m.append("license: ").append(s.license()).append('\n');
        list(m, "covers", covers);
        list(m, "variant_ids", new ArrayList<>(variants));
        m.append("expected_outcome: ").append(c.negative() ? c.outcome() : "PARSED").append('\n');
        m.append("anonymised: true\n");
        m.append("notes: ").append(quote(notes(s, c))).append('\n');
        return m.toString();
    }

    private static String notes(Source s, Case c) {
        StringBuilder n = new StringBuilder("Anonymised excerpt of ").append(s.repo()).append(" at ")
                .append(s.commit(), 0, 12).append(": ").append(c.file());
        if (!c.eventIds().isEmpty()) {
            n.append(", record(s) with eventID ").append(String.join(", ", c.eventIds()));
        } else if (!c.indices().isEmpty()) {
            n.append(", entry index ").append(c.indices());
        } else {
            n.append(", whole file");
        }
        n.append(". Changed from the source: identifiers anonymised by com.dlap.fixtures.anon.Anonymiser, ")
                .append("records wrapped in the provider's delivery envelope, JSON re-indented");
        if (c.distinctEventIds()) {
            n.append(", each record's eventID replaced by a name-based UUID of the case and its record index ")
                    .append("because the sample reuses one eventID for different records (Q-642)");
        }
        n.append(". ");
        if (s.note() != null) {
            n.append(s.note()).append(' ');
        }
        if (c.notes() != null) {
            n.append(c.notes());
        }
        return n.toString().trim();
    }

    private static void list(StringBuilder m, String key, List<String> items) {
        if (items.isEmpty()) {
            m.append(key).append(": []\n");
            return;
        }
        m.append(key).append(":\n");
        for (String i : items) {
            m.append("  - ").append(PLAIN_YAML.matcher(i).matches() ? i : quote(i)).append('\n');
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------
    // Writing and checking
    // ------------------------------------------------------------------

    private static void write(Output o) {
        try {
            Files.createDirectories(o.path().getParent());
            Files.write(o.path(), o.gzip() ? gzip(o.text()) : o.text().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + o.path(), e);
        }
    }

    /** Returns null when the file on disk matches, else a short reason. */
    private static String differs(Output o) {
        if (!Files.isRegularFile(o.path())) {
            return "missing";
        }
        try {
            byte[] disk = Files.readAllBytes(o.path());
            String text = o.gzip() ? gunzip(disk) : new String(disk, StandardCharsets.UTF_8);
            return text.equals(o.text()) ? null : "content differs";
        } catch (IOException e) {
            return "unreadable: " + e.getMessage();
        }
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static String gunzip(byte[] raw) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
