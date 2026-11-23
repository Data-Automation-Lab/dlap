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

package com.dlap.discovery.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Q-829: {@link CoverageHttp} retries a 429 or 5xx at most three times with exponential backoff (base
 * 1 s, cap 30 s), honours {@code Retry-After} capped at 30 s, keeps the last response for the caller to
 * map to UNKNOWN, and never retries a 403. The server is a local scripted one and the pause is
 * recorded, not slept, so the waits asserted are the waits the transport asked for.
 */
class CoverageHttpRetryTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    /** One scripted answer. */
    private record Reply(int status, String body, String retryAfter) {
    }

    private HttpServer server;
    private final List<Reply> script = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Duration> pauses = Collections.synchronizedList(new ArrayList<>());
    private CoverageHttp http;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            Reply reply = script.get(Math.min(calls.getAndIncrement(), script.size() - 1));
            if (reply.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        http = new CoverageHttp(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                CoverageFixtures.MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), pauses::add);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x"))
                .timeout(CoverageHttp.TIMEOUT).GET().build();
    }

    private static Reply reply(int status) {
        return new Reply(status, "{\"s\":" + status + "}", null);
    }

    @Test
    void a429WithRetryAfterWaitsThatLongThenSucceeds() {
        script.add(new Reply(429, "{}", "7"));
        script.add(reply(200));

        CoverageHttp.Response response = http.send(request());

        assertThat(response.ok()).isTrue();
        assertThat(response.body().get("s").asInt()).isEqualTo(200);
        assertThat(calls).hasValue(2);
        assertThat(pauses).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void a503ThenSuccessRetriesOnceAfterTheBaseBackoff() {
        script.add(reply(503));
        script.add(reply(200));

        CoverageHttp.RawResponse response = http.sendRaw(request());

        assertThat(response.status()).isEqualTo(200);
        assertThat(calls).hasValue(2);
        assertThat(pauses).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aServerThatKeepsFailingIsCalledFourTimesWithDoublingWaitsAndTheLastResponseIsReturned() {
        script.add(reply(500));

        CoverageHttp.RawResponse response = http.sendRaw(request());

        assertThat(response.status()).as("the caller maps the last response to UNKNOWN").isEqualTo(500);
        assertThat(response.text()).contains("500");
        assertThat(calls).as("1 attempt + 3 retries").hasValue(1 + CoverageHttp.MAX_RETRIES);
        assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void aThrottleThatKeepsAnsweringIsReturnedAfterTheLastAttempt() {
        script.add(new Reply(429, "{}", "1"));

        CoverageHttp.Response response = http.send(request());

        assertThat(response.status()).isEqualTo(429);
        assertThat(response.ok()).isFalse();
        assertThat(calls).hasValue(4);
        assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void a403IsNeverRetried() {
        script.add(reply(403));
        script.add(reply(200));

        assertThat(http.sendRaw(request()).status()).isEqualTo(403);

        assertThat(calls).hasValue(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void otherClientErrorsAndRedirectsAreNotRetried() {
        for (int status : new int[] {400, 401, 404, 409, 302}) {
            calls.set(0);
            script.clear();
            script.add(reply(status));

            assertThat(http.sendRaw(request()).status()).isEqualTo(status);
            assertThat(calls).as("HTTP %d", status).hasValue(1);
        }
        assertThat(pauses).isEmpty();
    }

    @Test
    void retryAfterIsCappedAtThirtySeconds() {
        script.add(new Reply(429, "{}", "120"));
        script.add(new Reply(503, "{}", "31"));
        script.add(new Reply(429, "{}", "99999999999999"));
        script.add(reply(200));

        assertThat(http.sendRaw(request()).status()).isEqualTo(200);

        assertThat(pauses).containsExactly(CoverageHttp.RETRY_CAP, CoverageHttp.RETRY_CAP, CoverageHttp.RETRY_CAP);
        assertThat(CoverageHttp.RETRY_CAP).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void retryAfterAsAnHttpDateIsReadAgainstTheInjectedClock() {
        String in12 = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(NOW.plusSeconds(12),
                ZoneOffset.UTC));
        String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(NOW.minusSeconds(60),
                ZoneOffset.UTC));
        script.add(new Reply(429, "{}", in12));
        script.add(new Reply(429, "{}", past));
        script.add(new Reply(429, "{}", "3600"));
        script.add(reply(200));

        assertThat(http.sendRaw(request()).status()).isEqualTo(200);

        assertThat(pauses).containsExactly(Duration.ofSeconds(12), Duration.ZERO, Duration.ofSeconds(30));
    }

    @Test
    void aMissingOrUnreadableRetryAfterFallsBackToTheBackoff() {
        script.add(new Reply(503, "{}", "soon"));
        script.add(new Reply(503, "{}", ""));
        script.add(new Reply(503, "{}", "-5"));
        script.add(reply(200));

        assertThat(http.sendRaw(request()).status()).isEqualTo(200);

        assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ZERO);
    }

    @Test
    void theBackoffDoublesFromTheBaseAndNeverPassesTheCap() {
        assertThat(CoverageHttp.RETRY_BASE).isEqualTo(Duration.ofSeconds(1));
        assertThat(CoverageHttp.backoff(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(CoverageHttp.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(CoverageHttp.backoff(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(CoverageHttp.backoff(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(CoverageHttp.backoff(5)).as("32 s capped").isEqualTo(Duration.ofSeconds(30));
        assertThat(CoverageHttp.backoff(10_000)).as("no overflow").isEqualTo(Duration.ofSeconds(30));
        assertThat(CoverageHttp.backoff(-1)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void onlyThrottlingAndServerErrorsAreRetryable() {
        assertThat(CoverageHttp.retryable(429)).isTrue();
        assertThat(CoverageHttp.retryable(500)).isTrue();
        assertThat(CoverageHttp.retryable(599)).isTrue();
        assertThat(CoverageHttp.retryable(600)).isFalse();
        assertThat(CoverageHttp.retryable(403)).isFalse();
        assertThat(CoverageHttp.retryable(408)).isFalse();
        assertThat(CoverageHttp.retryable(200)).isFalse();
    }

    @Test
    void aCallThatDoesNotCompleteIsNotRetried() {
        int port = server.getAddress().getPort();
        server.stop(0);
        HttpRequest dead = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/x?token=secret"))
                .timeout(Duration.ofSeconds(5)).GET().build();

        assertThatThrownBy(() -> http.sendRaw(dead)).isInstanceOf(CoverageTransportException.class)
                .hasMessageContaining("failed").hasMessageNotContaining("secret");

        assertThat(pauses).isEmpty();
    }

    @Test
    void anInterruptedWaitEndsTheCallAndKeepsTheInterruptFlag() {
        script.add(reply(503));
        CoverageHttp interrupted = new CoverageHttp(HttpClient.newHttpClient(), CoverageFixtures.MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC), d -> {
                    throw new InterruptedException();
                });

        try {
            assertThatThrownBy(() -> interrupted.sendRaw(request())).isInstanceOf(CoverageTransportException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
