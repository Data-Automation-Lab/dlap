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

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pushes generated normalized events (one JSON object per line) to da-logingest's REST channel,
 * {@code POST /api/v1/logingest/events}, from many threads at once. It exists because the fixture
 * replay seeder is single threaded and blocks application start, so it can neither load at scale nor
 * run beside the stream transports. Each answer is counted: {@code 202} is accepted, {@code 202} with
 * status {@code DUPLICATE} is a duplicate, {@code 422} is rejected (quarantined or skipped), anything
 * else is a failure after three attempts.
 */
public final class RestPoster {

    /**
     * What a run did.
     *
     * @param sent       lines read and posted
     * @param accepted   first deliveries
     * @param duplicates answers that said the id was already stored
     * @param rejected   answers of 422
     * @param failed     lines that never got a usable answer
     */
    public record Result(long sent, long accepted, long duplicates, long rejected, long failed) {
    }

    /** Gets bearer tokens with the OAuth password grant and keeps one until it is about to expire. */
    public static final class PasswordGrant implements Supplier<String> {
        private static final Pattern TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern EXPIRES = Pattern.compile("\"expires_in\"\\s*:\\s*(\\d+)");

        private final HttpClient client;
        private final URI tokenUrl;
        private final String form;
        private String token;
        private long validUntilMillis;

        /**
         * Creates the source.
         *
         * @param client   the HTTP client
         * @param tokenUrl the token endpoint
         * @param clientId the client id
         * @param secret   the client secret, from the environment, never an argument
         * @param user     the user
         * @param password the user's password, from the environment, never an argument
         */
        public PasswordGrant(HttpClient client, URI tokenUrl, String clientId, String secret, String user,
                             String password) {
            this.client = client;
            this.tokenUrl = tokenUrl;
            this.form = "grant_type=password&client_id=" + enc(clientId) + "&client_secret=" + enc(secret)
                    + "&username=" + enc(user) + "&password=" + enc(password);
        }

        private static String enc(String v) {
            return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
        }

        @Override
        public synchronized String get() {
            long now = System.currentTimeMillis();
            if (token == null || now >= validUntilMillis) {
                try {
                    HttpResponse<String> r = client.send(HttpRequest.newBuilder(tokenUrl)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).timeout(Duration.ofSeconds(30)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    Matcher t = TOKEN.matcher(r.body());
                    if (r.statusCode() != 200 || !t.find()) {
                        throw new IllegalStateException("token endpoint answered HTTP " + r.statusCode());
                    }
                    token = t.group(1);
                    Matcher e = EXPIRES.matcher(r.body());
                    long seconds = e.find() ? Long.parseLong(e.group(1)) : 60;
                    validUntilMillis = now + Math.max(1, seconds - 30) * 1000L;
                } catch (IOException e) {
                    throw new IllegalStateException("token endpoint unreachable: " + e.getMessage(), e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while getting a token", e);
                }
            }
            return token;
        }

        /** Forgets the token, so the next call gets a new one (after a 401). */
        public synchronized void invalidate() {
            token = null;
        }
    }

    private static final int ATTEMPTS = 3;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI url;
    private final Supplier<String> tokens;
    private final int threads;

    /**
     * Creates the poster.
     *
     * @param url     the events endpoint
     * @param tokens  the bearer token source, or null for an endpoint without authentication
     * @param threads the number of concurrent requests
     */
    public RestPoster(URI url, Supplier<String> tokens, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1");
        }
        this.url = url;
        this.tokens = tokens;
        this.threads = threads;
    }

    /**
     * Posts every line of the files.
     *
     * @param files files of one JSON object per line, read in the order given
     * @return what the run did
     * @throws IOException          when a file cannot be read
     * @throws InterruptedException when interrupted
     */
    public Result post(List<Path> files) throws IOException, InterruptedException {
        AtomicLong sent = new AtomicLong();
        AtomicLong accepted = new AtomicLong();
        AtomicLong duplicates = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Semaphore inFlight = new Semaphore(threads * 4);
        try {
            for (Path file : files) {
                try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        inFlight.acquire();
                        sent.incrementAndGet();
                        final String body = line;
                        pool.submit(() -> {
                            try {
                                switch (send(body)) {
                                    case 0 -> accepted.incrementAndGet();
                                    case 1 -> duplicates.incrementAndGet();
                                    case 2 -> rejected.incrementAndGet();
                                    default -> failed.incrementAndGet();
                                }
                            } finally {
                                inFlight.release();
                            }
                        });
                    }
                }
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(1, TimeUnit.HOURS);
        }
        return new Result(sent.get(), accepted.get(), duplicates.get(), rejected.get(), failed.get());
    }

    /** 0 accepted, 1 duplicate, 2 rejected, 3 failed. */
    private int send(String body) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(url).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(60));
                if (tokens != null) {
                    b.header("Authorization", "Bearer " + tokens.get());
                }
                HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                int code = r.statusCode();
                if (code == 202) {
                    return r.body().contains("\"DUPLICATE\"") ? 1 : 0;
                }
                if (code == 422) {
                    return 2;
                }
                if (code == 401 && tokens instanceof PasswordGrant g) {
                    g.invalidate();
                }
            } catch (IOException | IllegalStateException e) {
                // retried below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 3;
            }
            try {
                Thread.sleep(100L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 3;
            }
        }
        return 3;
    }
}
