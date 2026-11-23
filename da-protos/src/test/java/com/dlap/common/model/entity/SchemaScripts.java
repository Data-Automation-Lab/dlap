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

package com.dlap.common.model.entity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Test helper: locates and parses the dlap_common SQL scripts under
 * {@code deployment/platform/database/}, the single source of the shared schema (CLAUDE.md
 * section 6).
 */
final class SchemaScripts {

    /** The delta that adds the ADR-0046 columns. */
    static final String ASSET_TIME_DELTA = "2026-10-18_asset_time_size_retention.sql";

    /** The last delta already contained in the baseline test fixture (sdg 7984d1d). */
    static final String BASELINE_LAST_DELTA = "2026-07-05_processing_job_asset_types.sql";

    /** First and last line of the ADR-0046 block, identical in 02 and in the delta. */
    static final String BLOCK_START = "-- Creation and size";
    static final String BLOCK_END_PREFIX = "COMMENT ON COLUMN dlap_assets.legal_hold ";

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private SchemaScripts() {
    }

    /** The repository root, found by walking up from the working directory. */
    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(initScripts(dir).resolve("02-create-common-schema.sql"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("deployment/platform/database not found above "
                + Path.of("").toAbsolutePath());
    }

    static Path initScripts() {
        return initScripts(repoRoot());
    }

    static Path upgrades() {
        return repoRoot().resolve("deployment/platform/database/upgrades");
    }

    private static Path initScripts(Path root) {
        return root.resolve("deployment/platform/database/init-scripts");
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String commonSchema() {
        return read(initScripts().resolve("02-create-common-schema.sql"));
    }

    static String assetTimeDelta() {
        return read(upgrades().resolve(ASSET_TIME_DELTA));
    }

    /** Every upgrade delta newer than the baseline fixture, in apply (file name) order. */
    static List<Path> deltasAfterBaseline() {
        try (Stream<Path> files = Files.list(upgrades())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(p -> p.getFileName().toString().compareTo(BASELINE_LAST_DELTA) > 0)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The script without {@code --} comments, so lists that carry comment lines parse cleanly. */
    static String withoutComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            int dash = line.indexOf("--");
            out.append(dash >= 0 ? line.substring(0, dash) : line).append('\n');
        }
        return out.toString();
    }

    /**
     * The quoted values of the {@code IN (...)} list of a named CHECK constraint, in script order.
     * Fails when the script defines the constraint zero or several times.
     */
    static List<String> checkValues(String sql, String constraintName) {
        Pattern p = Pattern.compile("CONSTRAINT\\s+" + Pattern.quote(constraintName)
                + "\\s+CHECK\\s*\\(\\s*\\w+\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(withoutComments(sql));
        if (!m.find()) {
            throw new AssertionError("constraint " + constraintName + " not found");
        }
        List<String> values = new ArrayList<>();
        Matcher q = QUOTED.matcher(m.group(1));
        while (q.find()) {
            values.add(q.group(1));
        }
        if (m.find()) {
            throw new AssertionError("constraint " + constraintName + " defined more than once");
        }
        return values;
    }

    /** The ADR-0046 block: from {@link #BLOCK_START} to the legal_hold column comment. */
    static String adr0046Block(String sql) {
        int start = sql.indexOf(BLOCK_START);
        int endLine = sql.indexOf(BLOCK_END_PREFIX, start);
        if (start < 0 || endLine < 0) {
            throw new AssertionError("ADR-0046 block markers not found");
        }
        int end = sql.indexOf('\n', endLine);
        return sql.substring(start, end < 0 ? sql.length() : end);
    }
}
