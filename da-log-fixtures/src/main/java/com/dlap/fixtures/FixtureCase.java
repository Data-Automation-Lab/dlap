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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * One golden fixture case: a directory holding one or more {@code input.*} files, an
 * {@code expected.json} and a {@code meta.yaml} with its provenance.
 *
 * <p>Layout, relative to the corpus root:
 * <pre>
 *   &lt;family&gt;/&lt;envelope&gt;/&lt;version&gt;/&lt;case&gt;/             positive case
 *   &lt;family&gt;/negative/&lt;envelope&gt;/&lt;version&gt;/&lt;case&gt;/   negative case (must quarantine or reject)
 * </pre>
 * Cases are loaded through {@link FixtureCatalog}. The record is immutable; file content is
 * read on demand, so a large corpus costs memory only for the cases a test touches.
 *
 * @param family    the log family, for example {@code cloudtrail} (the first path segment)
 * @param negative  true for a case under {@code <family>/negative/}
 * @param envelope  the transport or wrapper shape of the input
 * @param version   the documented format version the case is written for
 * @param name      the case directory name
 * @param dir       the case directory
 * @param meta      the parsed {@code meta.yaml}
 * @param selftest  true when the family only exists to test the tooling; real parser walks skip it
 */
public record FixtureCase(
        String family,
        boolean negative,
        String envelope,
        String version,
        String name,
        Path dir,
        Meta meta,
        boolean selftest) {

    /** File name of the provenance metadata inside a case directory. */
    public static final String META_FILE = "meta.yaml";

    /** File name of the expected result inside a case directory. */
    public static final String EXPECTED_FILE = "expected.json";

    /** Prefix of the input file names inside a case directory, for example {@code input.json.gz}. */
    public static final String INPUT_PREFIX = "input.";

    private static final String NEGATIVE_SEGMENT = FixtureCatalog.NEGATIVE_DIR;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Where a case came from. The id is the value written in {@code meta.yaml}.
     */
    public enum Origin {
        /** Hand-written from a vendor's documented schema; no vendor sample is copied. */
        SYNTHETIC_FROM_DOC("synthetic-from-doc"),
        /** Generated from a published protobuf definition. */
        SYNTHETIC_FROM_PROTO("synthetic-from-proto"),
        /** Taken from an openly licensed public dataset, anonymised. */
        PUBLIC_DATASET("public-dataset"),
        /** Captured from a local emulator. */
        EMULATOR_CAPTURED("emulator-captured"),
        /** Captured from the project's own cloud account, redacted. */
        OWN_ACCOUNT_REDACTED("own-account-redacted"),
        /** Captured from a design partner's account, redacted under a written agreement. */
        PARTNER_REDACTED("partner-redacted");

        private final String id;

        Origin(String id) {
            this.id = id;
        }

        /**
         * Returns the value used in {@code meta.yaml}.
         *
         * @return the kebab-case id
         */
        public String id() {
            return id;
        }

        /**
         * Looks an origin up by its {@code meta.yaml} id.
         *
         * @param id the id to look up
         * @return the origin, or empty when the id is unknown
         */
        public static Optional<Origin> fromId(String id) {
            for (Origin o : values()) {
                if (o.id.equals(id)) {
                    return Optional.of(o);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * How a case's shape was confirmed by something other than the people who wrote the parser
     * (critic item 6 of the hardening plan). The id is the value written in {@code meta.yaml}
     * under {@code confirmed_by[].method}.
     */
    public enum ConfirmationMethod {
        /** The input round-trips through the vendor's published protobuf definition without loss. */
        PROTO_ROUND_TRIP("proto-round-trip"),
        /** The vendor's own processing library (for example aws-cloudtrail-processing-library) reads it. */
        PROCESSING_LIBRARY("processing-library"),
        /** An independent project's expected output (OpenTelemetry {@code _expected.yaml}) agrees with it. */
        OTEL_EXPECTED("otel-expected"),
        /** Captured from the project's own cloud account; implied by origin {@code own-account-redacted}. */
        OWN_ACCOUNT_CAPTURE("own-account-capture"),
        /** Captured from a design partner's account; implied by origin {@code partner-redacted}. */
        PARTNER_CAPTURE("partner-capture");

        private final String id;

        ConfirmationMethod(String id) {
            this.id = id;
        }

        /**
         * Returns the value used in {@code meta.yaml}.
         *
         * @return the kebab-case id
         */
        public String id() {
            return id;
        }

        /**
         * Looks a method up by its {@code meta.yaml} id.
         *
         * @param id the id to look up
         * @return the method, or empty when the id is unknown
         */
        public static Optional<ConfirmationMethod> fromId(String id) {
            for (ConfirmationMethod m : values()) {
                if (m.id.equals(id)) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * One {@code confirmed_by} entry of a {@code meta.yaml}.
     *
     * @param method   how the shape was confirmed
     * @param evidence where a reviewer can re-check it: the test class that runs the check, or
     *                 {@code owner/name@<commit>:<path>} of the independent expected output
     */
    public record Confirmation(ConfirmationMethod method, String evidence) {
    }

    /**
     * The evidence class of a case, which decides what the case may count toward in the coverage
     * score ({@link CoverageReport}).
     */
    public enum Evidence {
        /**
         * Independently confirmed: an own-account or partner capture, or a {@code confirmed_by}
         * entry (proto round-trip, processing library, OTel expected output). Counts toward
         * operations and variants, and toward the separate confirmed coverage.
         */
        CONFIRMED,
        /** Hand-written from documentation or taken from a public dataset. Counts toward operations and variants. */
        AUTHORED,
        /** Generator output with no independent confirmation. Counts toward operations, never toward variants. */
        GENERATED,
        /** Captured from an emulator. Counts toward neither: an emulator's record shape is not the vendor's. */
        EMULATOR
    }

    /**
     * The parsed {@code meta.yaml}. {@code docDate}, {@code sourceUrl}, {@code repo}, {@code commit},
     * {@code notes} and {@code generator} are optional in the file, so they may be null here; which of
     * them an origin needs is checked by {@link ProvenanceVerifier}.
     *
     * @param origin          where the case came from
     * @param sourceUrl       the documentation or proto the case was written from
     * @param docDate         the date of that documentation, or the capture date for captured origins
     * @param repo            {@code owner/name} of the repository a public case was taken from
     * @param commit          the full 40-character commit SHA of that repository
     * @param license         one of the allowlisted licences; {@code synthetic} means no third-party licence applies
     * @param covers          documented {@code service:operation} pairs the case exercises
     * @param variantIds      documented variants (IDs from the family's coverage spec) the case exercises
     * @param expectedOutcome the outcome a correct parser reports, for example {@code PARSED}
     * @param anonymised      whether identifiers were anonymised or redacted
     * @param notes           free text for reviewers
     * @param confirmedBy     independent confirmations of the case's shape; empty when none
     * @param generator       the generator that produced the case (a class or script), or null when written by hand
     */
    public record Meta(
            Origin origin,
            String sourceUrl,
            LocalDate docDate,
            String repo,
            String commit,
            String license,
            List<String> covers,
            List<String> variantIds,
            String expectedOutcome,
            Boolean anonymised,
            String notes,
            List<Confirmation> confirmedBy,
            String generator) {
    }

    /**
     * Lists every way this case was independently confirmed: the {@code confirmed_by} entries plus
     * the capture implied by an {@code own-account-redacted} or {@code partner-redacted} origin.
     *
     * @return the methods, without duplicates; empty for an unconfirmed case
     */
    public List<ConfirmationMethod> confirmationMethods() {
        List<ConfirmationMethod> out = new ArrayList<>();
        if (meta.origin() == Origin.OWN_ACCOUNT_REDACTED) {
            out.add(ConfirmationMethod.OWN_ACCOUNT_CAPTURE);
        } else if (meta.origin() == Origin.PARTNER_REDACTED) {
            out.add(ConfirmationMethod.PARTNER_CAPTURE);
        }
        for (Confirmation c : meta.confirmedBy()) {
            if (!out.contains(c.method())) {
                out.add(c.method());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Classifies the case for scoring. Emulator captures stay {@link Evidence#EMULATOR} whatever they
     * claim (the provenance gate rejects a confirmation on them); otherwise any confirmation makes the
     * case {@link Evidence#CONFIRMED}, a generator without one makes it {@link Evidence#GENERATED},
     * and the rest are {@link Evidence#AUTHORED}.
     *
     * @return the evidence class
     */
    public Evidence evidence() {
        if (meta.origin() == Origin.EMULATOR_CAPTURED) {
            return Evidence.EMULATOR;
        }
        if (!confirmationMethods().isEmpty()) {
            return Evidence.CONFIRMED;
        }
        if (meta.generator() != null) {
            return Evidence.GENERATED;
        }
        return Evidence.AUTHORED;
    }

    /**
     * Returns the case id: its path relative to the corpus root, with forward slashes.
     *
     * @return for example {@code selftest/generic-json/1/read-ok}
     */
    public String id() {
        return family + "/" + (negative ? NEGATIVE_SEGMENT + "/" : "") + envelope + "/" + version + "/" + name;
    }

    /**
     * Lists the {@code input.*} files of the case, sorted by name.
     *
     * @return the input files; empty when the case has none
     */
    public List<Path> inputFiles() {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(INPUT_PREFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list inputs of " + id(), e);
        }
    }

    /**
     * Returns the first input file by name, the one most cases have.
     *
     * @return the primary input file
     * @throws IllegalStateException when the case has no input file
     */
    public Path primaryInput() {
        List<Path> all = inputFiles();
        if (all.isEmpty()) {
            throw new IllegalStateException("case " + id() + " has no input.* file");
        }
        return all.get(0);
    }

    /**
     * Reads the primary input exactly as stored (a {@code .gz} file stays compressed).
     *
     * @return the raw bytes
     */
    public byte[] inputBytes() {
        try {
            return Files.readAllBytes(primaryInput());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read input of " + id(), e);
        }
    }

    /**
     * Reads the primary input as UTF-8 text, decompressing it first when its name ends in {@code .gz}.
     *
     * @return the decoded text
     */
    public String inputText() {
        byte[] raw = inputBytes();
        if (!primaryInput().getFileName().toString().endsWith(".gz")) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("input of " + id() + " is not valid gzip", e);
        }
    }

    /**
     * Returns the path of {@code expected.json}; the file may not exist in a broken case.
     *
     * @return the expected-result path
     */
    public Path expectedFile() {
        return dir.resolve(EXPECTED_FILE);
    }

    /**
     * Reads {@code expected.json} as text.
     *
     * @return the file content
     */
    public String expectedJson() {
        try {
            return Files.readString(expectedFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read expected.json of " + id(), e);
        }
    }

    /**
     * Parses {@code expected.json}.
     *
     * @return the JSON tree
     * @throws IllegalStateException when the file is not valid JSON
     */
    public JsonNode expectedTree() {
        try {
            return JSON.readTree(expectedJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("expected.json of " + id() + " is not valid JSON", e);
        }
    }
}
