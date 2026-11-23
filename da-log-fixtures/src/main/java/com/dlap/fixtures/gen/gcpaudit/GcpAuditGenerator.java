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

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes or checks the generated {@code gcpaudit} cases ({@link GcpAuditCaseBook}).
 *
 * <pre>
 *   GcpAuditGenerator write --corpus da-log-fixtures/src/main/resources/dlap-fixtures
 *   GcpAuditGenerator check --corpus da-log-fixtures/src/main/resources/dlap-fixtures
 * </pre>
 * {@code write} replaces every case directory whose {@code meta.yaml} names this generator and
 * writes the book; {@code check} exits 1 when a committed file differs from the book, a generated
 * case is missing, or a committed generated case is no longer in the book. Hand-written and
 * imported cases of the family are never touched.
 */
public final class GcpAuditGenerator {

    private GcpAuditGenerator() {
    }

    /**
     * Runs the command line.
     *
     * @param args {@code write|check --corpus <dir>}
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs a command.
     *
     * @param args the arguments
     * @param out  standard output
     * @param err  standard error
     * @return the exit code: 0 ok, 1 differences, 2 usage
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3 || !"--corpus".equals(args[1]) || !List.of("write", "check").contains(args[0])) {
            err.println("usage: GcpAuditGenerator write|check --corpus <dlap-fixtures directory>");
            return 2;
        }
        Path corpus = Path.of(args[2]);
        Map<String, String> files = files();
        if ("write".equals(args[0])) {
            for (Path dir : generatedCaseDirs(corpus)) {
                delete(dir);
            }
            files.forEach((rel, content) -> write(corpus.resolve(rel), content));
            out.println("wrote " + files.size() + " files of " + GcpAuditCaseBook.cases().size() + " cases");
            return 0;
        }
        List<String> problems = differences(corpus, files);
        problems.forEach(err::println);
        if (problems.isEmpty()) {
            out.println("ok: " + files.size() + " generated files match the case book");
            return 0;
        }
        return 1;
    }

    /**
     * Every generated file, by path relative to the corpus root.
     *
     * @return the files
     */
    static Map<String, String> files() {
        Map<String, String> out = new LinkedHashMap<>();
        for (GcpAuditCase c : GcpAuditCaseBook.cases()) {
            Map<String, String> f = GcpAuditCaseWriter.render(c);
            for (String k : f.keySet()) {
                if (out.containsKey(k)) {
                    throw new IllegalStateException("two generated cases write " + k);
                }
            }
            out.putAll(f);
        }
        return out;
    }

    /**
     * Compares the committed corpus with the book.
     *
     * @param corpus the corpus root
     * @param files  the generated files
     * @return one line per difference; empty when they agree
     */
    static List<String> differences(Path corpus, Map<String, String> files) {
        List<String> problems = new ArrayList<>();
        Set<Path> expectedDirs = new LinkedHashSet<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path p = corpus.resolve(f.getKey());
            expectedDirs.add(p.getParent().normalize());
            if (!Files.isRegularFile(p)) {
                problems.add("missing: " + f.getKey());
                continue;
            }
            try {
                if (!Files.readString(p, StandardCharsets.UTF_8).equals(f.getValue())) {
                    problems.add("differs from the case book: " + f.getKey());
                }
            } catch (IOException e) {
                problems.add("unreadable: " + f.getKey() + ": " + e.getMessage());
            }
        }
        for (Path dir : generatedCaseDirs(corpus)) {
            if (!expectedDirs.contains(dir.normalize())) {
                problems.add("stale generated case: " + corpus.relativize(dir).toString().replace('\\', '/'));
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile).forEach(p -> {
                    String rel = corpus.relativize(p).toString().replace('\\', '/');
                    if (!files.containsKey(rel)) {
                        problems.add("extra file in a generated case: " + rel);
                    }
                });
            } catch (IOException e) {
                problems.add("unreadable: " + dir + ": " + e.getMessage());
            }
        }
        return problems;
    }

    /** Case directories of the family whose meta.yaml names this generator. */
    static List<Path> generatedCaseDirs(Path corpus) {
        Path family = corpus.resolve(GcpAuditCaseWriter.FAMILY);
        if (!Files.isDirectory(family)) {
            return List.of();
        }
        String marker = "generator: " + GcpAuditCaseWriter.GENERATOR;
        try (Stream<Path> s = Files.walk(family)) {
            return s.filter(p -> p.getFileName().toString().equals("meta.yaml"))
                    .filter(p -> {
                        try {
                            return Files.readAllLines(p, StandardCharsets.UTF_8).stream().anyMatch(l -> l.strip().equals(marker));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .map(Path::getParent)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, String content) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
