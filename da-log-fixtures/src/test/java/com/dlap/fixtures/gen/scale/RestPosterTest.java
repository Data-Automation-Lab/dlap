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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E2E-02: the REST poster counts every answer of the events endpoint, retries what failed, and gets a
 * new token after a 401. The server is an in-process stand-in for da-logingest and its token endpoint.
 */
class RestPosterTest {

    /** The events endpoint and the token endpoint on one port. */
    private static final class FakeService implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger tokensIssued = new AtomicInteger();
        final AtomicInteger posts = new AtomicInteger();
        final int minToken;
        final boolean alwaysFail;

        FakeService(int minToken, boolean alwaysFail) throws IOException {
            this.minToken = minToken;
            this.alwaysFail = alwaysFail;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", ex -> {
                ex.getRequestBody().readAllBytes();
                int n = tokensIssued.incrementAndGet();
                reply(ex, 200, "{\"access_token\":\"tok-" + n + "\",\"expires_in\":300}");
            });
            server.createContext("/events", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                posts.incrementAndGet();
                if (alwaysFail) {
                    reply(ex, 500, "{}");
                    return;
                }
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                if (minToken > 0) {
                    int n = auth != null && auth.startsWith("Bearer tok-") ? Integer.parseInt(auth.substring(11)) : 0;
                    if (n < minToken) {
                        reply(ex, 401, "{}");
                        return;
                    }
                }
                if (body.contains("\"requestId\":\"dup")) {
                    reply(ex, 202, "{\"status\":\"DUPLICATE\"}");
                } else if (body.contains("\"requestId\":\"bad")) {
                    reply(ex, 422, "{\"status\":\"QUARANTINED\"}");
                } else {
                    reply(ex, 202, "{\"status\":\"PUBLISHED\"}");
                }
            });
            server.start();
        }

        private static void reply(HttpExchange ex, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static List<Path> lines(Path dir, String... ids) throws IOException {
        List<Path> files = new ArrayList<>();
        for (int f = 0; f < 2; f++) {
            Path file = dir.resolve("gcp-scale-0000" + (f + 1) + ".jsonl");
            StringBuilder sb = new StringBuilder();
            for (int i = f; i < ids.length; i += 2) {
                sb.append("{\"requestId\":\"").append(ids[i]).append("\"}\n");
            }
            Files.writeString(file, sb.toString());
            files.add(file);
        }
        return files;
    }

    @Test
    void everyAnswerIsCounted(@TempDir Path dir) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ids.add(i % 20 == 0 ? "dup" + i : i % 25 == 0 ? "bad" + i : "ok" + i);
        }
        try (FakeService s = new FakeService(0, false)) {
            RestPoster.Result r = new RestPoster(URI.create(s.base() + "/events"), null, 6)
                    .post(lines(dir, ids.toArray(new String[0])));

            assertThat(r.sent()).isEqualTo(100);
            assertThat(r.duplicates()).isEqualTo(5);
            assertThat(r.rejected()).isEqualTo(3);
            assertThat(r.accepted()).isEqualTo(92);
            assertThat(r.failed()).isZero();
            assertThat(s.posts.get()).isEqualTo(100);
        }
    }

    @Test
    void aRejectedTokenIsReplacedAndTheLineIsSent(@TempDir Path dir) throws Exception {
        try (FakeService s = new FakeService(2, false)) {
            HttpClient client = HttpClient.newHttpClient();
            RestPoster.PasswordGrant grant = new RestPoster.PasswordGrant(client, URI.create(s.base() + "/token"),
                    "client", "secret", "user", "pass");
            RestPoster.Result r = new RestPoster(URI.create(s.base() + "/events"), grant, 4)
                    .post(lines(dir, "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8"));

            assertThat(r.accepted()).isEqualTo(8);
            assertThat(r.failed()).isZero();
            assertThat(s.tokensIssued.get()).as("the first token was refused, so more than one was issued")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void aServerThatKeepsFailingIsCountedAsFailedAfterThreeAttempts(@TempDir Path dir) throws Exception {
        try (FakeService s = new FakeService(0, true)) {
            RestPoster.Result r = new RestPoster(URI.create(s.base() + "/events"), null, 2).post(lines(dir, "x1", "x2"));

            assertThat(r.failed()).isEqualTo(2);
            assertThat(r.accepted()).isZero();
            assertThat(s.posts.get()).isEqualTo(6);
        }
    }

    @Test
    void mainPostsTheLinesFilesOfAPrefixAndExitsNonZeroOnFailures(@TempDir Path dir) throws Exception {
        List<Path> written = lines(dir, "m1", "m2", "m3");
        assertThat(written).hasSize(2);
        try (FakeService ok = new FakeService(0, false)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = ScaleMain.run(new String[] {"post", "--dir", dir.toString(), "--prefix", "gcp", "--url",
                ok.base() + "/events", "--threads", "2"}, new PrintStream(out, true), new PrintStream(out, true));
            assertThat(code).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("sent=3").contains("accepted=3").contains("failed=0");
        }
        try (FakeService bad = new FakeService(0, true)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = ScaleMain.run(new String[] {"post", "--dir", dir.toString(), "--prefix", "gcp", "--url",
                bad.base() + "/events", "--threads", "2"}, new PrintStream(out, true), new PrintStream(out, true));
            assertThat(code).isEqualTo(1);
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("failed=3");
        }
    }

    @Test
    void mainRefusesATokenUrlWithoutTheSecretsInTheEnvironmentAndAnEmptyDirectory(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int empty = ScaleMain.run(new String[] {"post", "--dir", dir.toString(), "--prefix", "gcp", "--url",
            "http://127.0.0.1:1/events"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true));
        assertThat(empty).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("no gcp-scale-*.jsonl");
        lines(dir, "z1");
        if (System.getenv("DLAP_PERF_CLIENT_SECRET") == null || System.getenv("DLAP_PERF_PASSWORD") == null) {
            ByteArrayOutputStream err2 = new ByteArrayOutputStream();
            int code = ScaleMain.run(new String[] {"post", "--dir", dir.toString(), "--prefix", "gcp", "--url",
                "http://127.0.0.1:1/events", "--token-url", "http://127.0.0.1:1/token", "--client-id", "c", "--user", "u"},
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(err2, true));
            assertThat(code).isEqualTo(2);
            assertThat(err2.toString(StandardCharsets.UTF_8)).contains("DLAP_PERF_CLIENT_SECRET");
        }
    }

    @Test
    void replayInLinesFormatWritesOneEventPerLine(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = ScaleMain.run(new String[] {"replay", "--provider", "azure", "--seed", "3", "--count", "30", "--end",
            "2026-11-01T12:00:00Z", "--span-hours", "2", "--per-file", "20", "--out-dir", dir.toString(),
            "--format", "lines"}, new PrintStream(out, true), new PrintStream(out, true));
        assertThat(code).isZero();
        List<String> first = Files.readAllLines(dir.resolve("azure-scale-00001.jsonl"));
        List<String> second = Files.readAllLines(dir.resolve("azure-scale-00002.jsonl"));
        assertThat(first).hasSize(20);
        assertThat(second).hasSize(10);
        assertThat(first).allMatch(l -> l.startsWith("{\"sourceProvider\":\"AZURE\"") && l.endsWith("}"));
        int bad = ScaleMain.run(new String[] {"replay", "--provider", "azure", "--seed", "3", "--count", "3", "--end",
            "2026-11-01T12:00:00Z", "--span-hours", "2", "--per-file", "2", "--out-dir", dir.toString(), "--format", "xml"},
                new PrintStream(out, true), new PrintStream(out, true));
        assertThat(bad).isEqualTo(2);
    }
}
