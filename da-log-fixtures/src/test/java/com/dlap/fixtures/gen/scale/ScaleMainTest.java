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

package com.dlap.fixtures.gen.scale;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E2E-02: the command line writes and uploads what the generators produce, reports it as
 * {@code key=value} lines, refuses bad input with exit code 2, and places the files of a public
 * CloudTrail archive under trail keys. The upload tests run against an in-process HTTP server that
 * stands in for the emulator, so no Docker is needed.
 */
class ScaleMainTest {

    private static final String[] TRAIL = {"trail", "--seed", "42", "--count", "2500", "--end",
        "2026-11-01T12:00:00Z", "--span-hours", "12", "--account", "123456789012", "--region", "us-east-1",
        "--per-file", "1000"};

    private record Result(int code, Map<String, String> out, String err) {
    }

    private static Result run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = ScaleMain.run(args, new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));
        Map<String, String> kv = new java.util.LinkedHashMap<>();
        for (String line : o.toString(StandardCharsets.UTF_8).split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                kv.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return new Result(code, kv, e.toString(StandardCharsets.UTF_8));
    }

    private static String[] concat(String[] base, String... more) {
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** A stand-in for an S3 emulator: records every bucket creation and object PUT. */
    private static final class FakeS3 implements AutoCloseable {
        final Map<String, Integer> objects = new ConcurrentHashMap<>();
        final List<String> buckets = new java.util.concurrent.CopyOnWriteArrayList<>();
        final HttpServer server;

        FakeS3() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath().substring(1);
                byte[] body = ex.getRequestBody().readAllBytes();
                if (path.indexOf('/') < 0) {
                    buckets.add(path);
                } else {
                    objects.put(path, body.length);
                }
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void trailWritesFilesAndTheIdListMatchesTheRecords(@TempDir Path dir) throws Exception {
        Path ids = dir.resolve("ids.txt");
        Result r = run(concat(TRAIL, "--out-dir", dir.resolve("out").toString(), "--ids-out", ids.toString()));

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).containsEntry("records", "2500").containsEntry("files", "3")
                .containsEntry("eventsTo", "2026-11-01T12:00:00Z");
        assertThat(files(dir.resolve("out"))).hasSize(3).allMatch(p -> p.getFileName().toString().endsWith(".json.gz"));
        List<String> lines = Files.readAllLines(ids);
        assertThat(lines).hasSize(2500).doesNotHaveDuplicates();
        assertThat(lines.get(0)).isEqualTo(new CloudTrailScale(
                new ScaleWindow(42, 2500, java.time.Instant.parse("2026-11-01T12:00:00Z"),
                        java.time.Duration.ofHours(12)), "123456789012", "us-east-1").eventId(0));
    }

    @Test
    void trailUploadsEveryFileAfterCreatingTheBucket() throws Exception {
        try (FakeS3 s3 = new FakeS3()) {
            Result r = run(concat(TRAIL, "--endpoint", s3.endpoint(), "--bucket", "dlap-perf-trail", "--threads", "2"));

            assertThat(r.code()).as(r.err()).isZero();
            assertThat(s3.buckets).containsExactly("dlap-perf-trail");
            assertThat(s3.objects).hasSize(3);
            assertThat(s3.objects.keySet()).allMatch(k -> k.startsWith("dlap-perf-trail/AWSLogs/123456789012/CloudTrail/"));
            assertThat(r.out()).containsEntry("files", "3");
        }
    }

    @Test
    void replayWritesJsonArraysNamedForTheSeederAndMatchingIds(@TempDir Path dir) throws Exception {
        Path ids = dir.resolve("ids.txt");
        Result r = run("replay", "--provider", "gcp", "--seed", "5", "--count", "250", "--end", "2026-11-01T12:00:00Z",
                "--span-hours", "6", "--per-file", "100", "--out-dir", dir.resolve("r").toString(),
                "--ids-out", ids.toString());

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).containsEntry("records", "250").containsEntry("files", "3");
        List<Path> written = files(dir.resolve("r"));
        assertThat(written).extracting(p -> p.getFileName().toString())
                .containsExactly("gcp-scale-00001.json", "gcp-scale-00002.json", "gcp-scale-00003.json");
        ObjectMapper json = new ObjectMapper();
        int events = 0;
        for (Path p : written) {
            JsonNode array = json.readTree(Files.readString(p));
            assertThat(array.isArray()).isTrue();
            events += array.size();
        }
        assertThat(events).isEqualTo(250);
        assertThat(Files.readAllLines(ids)).hasSize(250).doesNotHaveDuplicates();
    }

    @Test
    void usageErrorsExitWithTwoAndSayWhy() {
        assertThat(run().code()).isEqualTo(2);
        assertThat(run("bogus").code()).isEqualTo(2);
        Result noEnd = run("trail", "--seed", "1", "--count", "5");
        assertThat(noEnd.code()).isEqualTo(2);
        assertThat(noEnd.err()).contains("--end");
        Result badEnd = run(concat(new String[] {"trail", "--seed", "1", "--count", "5", "--span-hours", "1",
            "--account", "123456789012", "--region", "us-east-1", "--per-file", "5"}, "--end", "yesterday", "--out-dir", "x"));
        assertThat(badEnd.err()).contains("ISO instant");
        Result both = run(concat(TRAIL, "--out-dir", "x", "--endpoint", "http://127.0.0.1:1"));
        assertThat(both.code()).isEqualTo(2);
        assertThat(both.err()).contains("exactly one");
        Result provider = run("replay", "--provider", "oci", "--seed", "1", "--count", "5", "--end",
                "2026-11-01T12:00:00Z", "--span-hours", "1", "--per-file", "5", "--out-dir", "x");
        assertThat(provider.code()).isEqualTo(2);
        assertThat(run("trail", "--seed").code()).isEqualTo(2);
    }

    @Test
    void anUnreachableEmulatorIsAnErrorNotAHang() {
        Result r = run(concat(TRAIL, "--endpoint", "http://127.0.0.1:1", "--bucket", "b"));
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("failed");
    }

    @Test
    void flawsPlacesCloudTrailNamedFilesAndCountsTheRest(@TempDir Path dir) throws Exception {
        Path extracted = Files.createDirectories(dir.resolve("flaws").resolve("logs"));
        Files.write(extracted.resolve("111122223333_CloudTrail_us-east-1_20170228T2025Z_Cp7SKvsPxJqIF2YH.json.gz"), new byte[] {1});
        Files.write(extracted.resolve("111122223333_CloudTrail_us-west-2_20180101T0000Z_AbC123.json.gz"), new byte[] {2, 3});
        Files.write(extracted.resolve("README.txt"), new byte[] {4});
        try (FakeS3 s3 = new FakeS3()) {
            Result r = run("flaws", "--tar-dir", dir.resolve("flaws").toString(), "--endpoint", s3.endpoint(),
                    "--bucket", "dlap-perf-flaws");

            assertThat(r.code()).as(r.err()).isZero();
            assertThat(r.out()).containsEntry("files", "2").containsEntry("unmapped", "1")
                    .containsEntry("accounts", "111122223333").containsEntry("regions", "us-east-1,us-west-2")
                    .containsEntry("deliveredFirst", "2017-02-28T20:25:00Z")
                    .containsEntry("deliveredLast", "2018-01-01T00:00:00Z");
            assertThat(s3.objects).containsKey("dlap-perf-flaws/AWSLogs/111122223333/CloudTrail/us-east-1/2017/02/28/"
                    + "111122223333_CloudTrail_us-east-1_20170228T2025Z_Cp7SKvsPxJqIF2YH.json.gz");
        }
    }

    @Test
    void flawsKeysRejectNamesThatAreNotCloudTrailNames() {
        assertThat(FlawsKeys.place("whatever.json.gz")).isEmpty();
        assertThat(FlawsKeys.place("111122223333_CloudTrail_us-east-1_20170228T2025Z_X.json")).isEmpty();
        Optional<FlawsKeys.Placed> ok = FlawsKeys.place("111122223333_CloudTrail_eu-west-1_20190315T0910Z_Zz9.json.gz");
        assertThat(ok).isPresent();
        assertThat(ok.get().key()).isEqualTo("AWSLogs/111122223333/CloudTrail/eu-west-1/2019/03/15/"
                + "111122223333_CloudTrail_eu-west-1_20190315T0910Z_Zz9.json.gz");
    }
}
