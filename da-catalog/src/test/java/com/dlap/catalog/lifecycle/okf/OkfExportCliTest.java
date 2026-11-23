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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dlap.catalog.lifecycle.LifecycleStateSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The offline OKF export behind {@code ./gradlew :da-catalog:exportLifecycleStateOkf}: it reads a
 * saved list response, an array or one object, writes the zip, and refuses input that is not
 * Lifecycle State 0.1.
 */
class OkfExportCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void aSavedListResponseBecomesAZipThatPassesTheFrontmatterCheck() throws IOException {
        ObjectNode page = MAPPER.createObjectNode();
        page.put("specVersion", "0.1");
        page.put("asOf", "2026-10-31T12:00:00Z");
        ArrayNode items = page.putArray("items");
        items.add(LifecycleStateSchema.example("valid-azure-container-unknown-read.json"));
        items.add(LifecycleStateSchema.example("valid-bigquery-table-full-coverage-expired.json"));
        items.add(LifecycleStateSchema.example("valid-s3-bucket-partial-coverage-legal-hold.json"));
        page.putArray("unrepresentable");
        Path input = write("page.json", page);
        Path output = dir.resolve("out/okf.zip");

        assertThat(run(input, output)).isEqualTo(OkfExportCli.OK);

        Map<String, String> files = OkfFrontmatterCheck.unzip(Files.readAllBytes(output));
        assertThat(files).hasSize(3);
        files.forEach(OkfFrontmatterCheck::check);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("wrote 3 concept files as of 2026-10-31T12:00:00Z");
    }

    @Test
    void oneObjectIsEnough() throws IOException {
        Path input = write("one.json", LifecycleStateSchema.example("valid-azure-container-unknown-read.json"));
        assertThat(run(input, dir.resolve("one.zip"))).isEqualTo(OkfExportCli.OK);
        assertThat(OkfFrontmatterCheck.unzip(Files.readAllBytes(dir.resolve("one.zip")))).hasSize(1);
    }

    @Test
    void anInvalidObjectIsRefusedAndNothingIsWritten() throws IOException {
        Path input = write("bad.json", LifecycleStateSchema.example("invalid-last-read-at-number.json"));
        assertThat(run(input, dir.resolve("bad.zip"))).isEqualTo(OkfExportCli.INVALID);
        assertThat(dir.resolve("bad.zip")).doesNotExist();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("lastReadAt");
    }

    @Test
    void objectsOfDifferentInstantsAreRefused() throws IOException {
        ArrayNode both = MAPPER.createArrayNode();
        both.add(LifecycleStateSchema.example("valid-azure-container-unknown-read.json"));
        ObjectNode later = (ObjectNode) LifecycleStateSchema.example("valid-s3-bucket-partial-coverage-legal-hold.json");
        later.put("asOf", "2026-11-01T00:00:00Z");
        both.add(later);
        assertThat(run(write("mixed.json", both), dir.resolve("mixed.zip"))).isEqualTo(OkfExportCli.INVALID);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("same asOf");
    }

    @Test
    void aWrongCommandLineIsAUsageError() {
        assertThat(OkfExportCli.run(new String[] {"only-one"}, print(out), print(err))).isEqualTo(OkfExportCli.USAGE);
        assertThat(OkfExportCli.run(new String[] {"", ""}, print(out), print(err))).isEqualTo(OkfExportCli.USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage:");
    }

    @Test
    void anUnreadableInputIsRefused() {
        assertThat(run(dir.resolve("missing.json"), dir.resolve("x.zip"))).isEqualTo(OkfExportCli.INVALID);
    }

    private int run(Path input, Path output) {
        return OkfExportCli.run(new String[] {input.toString(), output.toString()}, print(out), print(err));
    }

    private Path write(String name, Object json) throws IOException {
        Path file = dir.resolve(name);
        MAPPER.writeValue(file.toFile(), json);
        return file;
    }

    private static PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream, true, StandardCharsets.UTF_8);
    }
}
