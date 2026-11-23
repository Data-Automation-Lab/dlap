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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.dlap.catalog.lifecycle.LifecycleState;
import com.dlap.catalog.lifecycle.LifecycleStateJson;
import com.dlap.common.logevent.LogEventIds;

/**
 * The Open Knowledge Format v0.1 mapping of Lifecycle State (docs/specs/lifecycle-state.md section
 * 8.2): one concept file per asset, {@code lifecycle-state/<sha256 of asset>.md}, with exactly the
 * frontmatter keys {@code type}, {@code title}, {@code description}, {@code resource}, {@code tags}
 * and {@code timestamp} in that order, then a heading, one fixed sentence, a two-column table for
 * people and the object itself as a fenced {@code json} block, which is the record.
 *
 * <p>Output is deterministic: the same objects give the same files, LF line ends, UTF-8. In a zip,
 * every entry carries {@code asOf} as its time, so the same export gives the same archive.
 */
public final class OkfExporter {

    /** Directory of the concept files inside an export. */
    public static final String DIRECTORY = "lifecycle-state/";

    /** The fixed sentence under the heading. */
    public static final String SENTENCE = "A value of unknown means the engine has no evidence. It never means never.";

    /** The OKF {@code type} of a concept file. */
    public static final String TYPE = "lifecycle-state";

    private static final String UNKNOWN = "unknown";

    private OkfExporter() {
    }

    /**
     * The path of an object's concept file inside an export.
     *
     * @param state the object
     * @return {@code lifecycle-state/<lowercase hex SHA-256 of the UTF-8 asset URI>.md}
     */
    public static String path(LifecycleState state) {
        return DIRECTORY + LogEventIds.sha256Hex(state.asset()) + ".md";
    }

    /**
     * The concept file of one object.
     *
     * @param state the object
     * @return the Markdown text, ending with a line break
     */
    public static String document(LifecycleState state) {
        StringBuilder md = new StringBuilder();
        String provider = state.platform().provider();
        String kind = state.platform().kind();
        md.append("---\n");
        md.append("type: ").append(TYPE).append('\n');
        md.append("title: ").append(quoted(state.asset())).append('\n');
        md.append("description: ").append(quoted("Lifecycle State of " + provider + " " + kind + " " + state.asset()
                + " as of " + state.asOf() + ".")).append('\n');
        md.append("resource: ").append(quoted(state.asset())).append('\n');
        md.append("tags:\n");
        for (String tag : tags(state)) {
            md.append("  - ").append(tag).append('\n');
        }
        md.append("timestamp: ").append(quoted(state.asOf().toString())).append('\n');
        md.append("---\n\n");
        md.append("# ").append(state.asset()).append("\n\n");
        md.append(SENTENCE).append("\n\n");
        md.append("| Field | Value |\n");
        md.append("|---|---|\n");
        row(md, "Platform", provider + " " + kind);
        row(md, "As of", state.asOf().toString());
        row(md, "Created", time(state.createdAt()));
        row(md, "Last read", time(state.lastReadAt()));
        row(md, "Last written", time(state.lastWrittenAt()));
        row(md, "Read coverage", coverage(state));
        row(md, "Size", state.sizeBytes() == null ? UNKNOWN
                : state.sizeBytes() + " bytes at " + time(state.sizeObservedAt()));
        row(md, "Retention", retention(state.retention()));
        row(md, "Legal hold", Boolean.toString(state.legalHold()));
        LifecycleState.Disposition d = state.disposition();
        row(md, "Disposition", d == null ? UNKNOWN
                : d.action() + " at " + d.executedAt() + " by " + d.executedBy());
        md.append("\n```json\n");
        md.append(LifecycleStateJson.pretty(state)).append('\n');
        md.append("```\n");
        return md.toString();
    }

    /**
     * The tags of an object, in the order of the mapping: {@code lifecycle-state}, the provider, the
     * kind, the read coverage, the retention status, {@code legal-hold} when held, the disposition.
     *
     * @param state the object
     * @return the tags
     */
    public static List<String> tags(LifecycleState state) {
        List<String> tags = new ArrayList<>();
        tags.add(TYPE);
        tags.add(state.platform().provider().toLowerCase(Locale.ROOT));
        tags.add(slug(state.platform().kind()));
        tags.add("read-coverage-" + (state.readCoverage() == null ? UNKNOWN : slug(state.readCoverage())));
        tags.add("retention-" + slug(state.retention().status()));
        if (state.legalHold()) {
            tags.add("legal-hold");
        }
        tags.add("disposition-" + (state.disposition() == null ? UNKNOWN : state.disposition().action()));
        return tags;
    }

    /**
     * Writes an export: one concept file per object, in the order given. Two objects of the same
     * asset would collide on one path; the second is rejected.
     *
     * @param states the objects, all of the same {@code asOf}
     * @param asOf   the time of every zip entry
     * @param out    the destination; finished as a zip, left open for the caller to close
     * @return the number of files written
     * @throws IOException              when writing fails
     * @throws IllegalArgumentException when two objects name the same asset
     */
    public static int writeZip(Iterable<LifecycleState> states, Instant asOf, OutputStream out) throws IOException {
        try (ZipWriter zip = open(asOf, out)) {
            for (LifecycleState state : states) {
                zip.add(state);
            }
            return zip.count();
        }
    }

    /**
     * Opens an incremental export, for callers that page through the catalog.
     *
     * @param asOf the time of every zip entry
     * @param out  the destination, left open
     * @return the writer; closing it finishes the archive
     */
    public static ZipWriter open(Instant asOf, OutputStream out) {
        return new ZipWriter(asOf, out);
    }

    /** An export being written. Not thread safe. */
    public static final class ZipWriter implements AutoCloseable {

        private final ZipOutputStream zip;
        private final LocalDateTime entryTime;
        private final Set<String> paths = new HashSet<>();

        private ZipWriter(Instant asOf, OutputStream out) {
            this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
            this.entryTime = LocalDateTime.ofInstant(asOf, ZoneOffset.UTC);
        }

        /**
         * Adds the concept file of one object.
         *
         * @param state the object
         * @throws IOException              when writing fails
         * @throws IllegalArgumentException when the asset was already added
         */
        public void add(LifecycleState state) throws IOException {
            String path = path(state);
            if (!paths.add(path)) {
                throw new IllegalArgumentException("two objects describe the same asset");
            }
            ZipEntry entry = new ZipEntry(path);
            entry.setTimeLocal(entryTime);
            zip.putNextEntry(entry);
            zip.write(document(state).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        /**
         * The number of files added.
         *
         * @return the count
         */
        public int count() {
            return paths.size();
        }

        /**
         * Finishes the archive and flushes it. The destination stream stays open: its owner closes
         * it (a file in the CLI, the response in the API).
         *
         * @throws IOException when writing fails
         */
        @Override
        public void close() throws IOException {
            zip.finish();
            zip.flush();
        }
    }

    private static String coverage(LifecycleState state) {
        String level = state.readCoverage();
        String text;
        if (level == null) {
            text = UNKNOWN;
        } else if ("FULL".equals(level)) {
            text = "FULL since " + time(state.readCoverageSince());
        } else {
            text = level;
        }
        if (!state.readCoverageReasons().isEmpty()) {
            text = text + ": " + String.join("; ", state.readCoverageReasons());
        }
        return text;
    }

    private static String retention(LifecycleState.Retention r) {
        StringBuilder text = new StringBuilder(r.status());
        if (r.basis() != null) {
            if ("FIXED_DATE".equals(r.basis())) {
                text.append(", FIXED_DATE ").append(time(r.fixedDate()));
            } else {
                text.append(", ").append(r.basis()).append(' ').append(r.days()).append(" days");
            }
        }
        if (r.expiresAt() != null) {
            text.append(", expires ").append(r.expiresAt());
        }
        if (r.source() != null) {
            text.append(", source ").append(r.source());
        }
        return text.toString();
    }

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(value.replace("|", "\\|")).append(" |\n");
    }

    private static String time(Instant value) {
        return value == null ? UNKNOWN : value.toString();
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * A YAML double-quoted scalar: backslash, double quote and control characters escaped, every
     * other character as is.
     *
     * @param value the text
     * @return the quoted scalar
     */
    static String quoted(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
