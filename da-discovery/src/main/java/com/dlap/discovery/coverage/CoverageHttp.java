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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JSON-over-HTTPS transport the coverage collectors share: the JDK HTTP client, no provider
 * SDK. Each provider package adds its own authentication (a bearer token, a SigV4 signature) and
 * maps refusals to {@link MissingPermissionException}.
 *
 * <p>A response of 429 or 5xx is retried at most {@value #MAX_RETRIES} times (Q-829): the wait before
 * retry <em>n</em> is {@link #RETRY_BASE} doubled <em>n - 1</em> times, never more than
 * {@link #RETRY_CAP}; a {@code Retry-After} header (seconds or an HTTP date) replaces that wait and is
 * capped the same way. After the last attempt the response is returned as it is, so the caller maps it
 * to an UNKNOWN verdict for its resource exactly as before. A 403 or any other 4xx is never retried,
 * and neither is a call that failed to complete (a timeout or a connection error).
 */
public class CoverageHttp {

    /** Default request timeout. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** The most retries of one request; with the first attempt that is four calls. */
    public static final int MAX_RETRIES = 3;

    /** The wait before the first retry. */
    public static final Duration RETRY_BASE = Duration.ofSeconds(1);

    /** The longest wait before any retry, whatever {@code Retry-After} asks for. */
    public static final Duration RETRY_CAP = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(CoverageHttp.class);

    /** Waits between attempts; replaced in tests so they need not sleep. */
    @FunctionalInterface
    public interface Pause {

        /**
         * Waits.
         *
         * @param duration how long
         * @throws InterruptedException when the thread is interrupted
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Pause pause;

    /**
     * Creates the transport.
     *
     * @param mapper reads the response bodies
     */
    public CoverageHttp(ObjectMapper mapper) {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
                mapper);
    }

    /**
     * Creates the transport on a given client.
     *
     * @param client the HTTP client
     * @param mapper reads the response bodies
     */
    public CoverageHttp(HttpClient client, ObjectMapper mapper) {
        this(client, mapper, Clock.systemUTC(), d -> Thread.sleep(d.toMillis()));
    }

    /**
     * Creates the transport on a given client, clock and pause.
     *
     * @param client the HTTP client
     * @param mapper reads the response bodies
     * @param clock  turns an HTTP-date {@code Retry-After} into a wait
     * @param pause  waits between attempts
     */
    public CoverageHttp(HttpClient client, ObjectMapper mapper, Clock clock, Pause pause) {
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
        this.pause = pause;
    }

    /**
     * A response: the status and the parsed body.
     *
     * @param status the HTTP status
     * @param body   the body as JSON; an empty object when the body is empty or not JSON
     */
    public record Response(int status, JsonNode body) {

        /** @return whether the status is 2xx */
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * A response as text.
     *
     * @param status the HTTP status
     * @param text   the body text, never {@code null}
     */
    public record RawResponse(int status, String text) {
    }

    /**
     * Sends a request and parses the body as JSON.
     *
     * @param request the request
     * @return the response
     * @throws CoverageTransportException when the call does not complete
     */
    public Response send(HttpRequest request) {
        RawResponse raw = sendRaw(request);
        return new Response(raw.status(), parse(raw.text()));
    }

    /**
     * Sends a request. A 429 or 5xx answer is retried (see the class description); the response that
     * ends the attempts is returned whatever its status.
     *
     * @param request the request
     * @return the status and the body text
     * @throws CoverageTransportException when the call does not complete
     */
    public RawResponse sendRaw(HttpRequest request) {
        int retries = 0;
        while (true) {
            HttpResponse<String> response = exchange(request);
            int status = response.statusCode();
            if (!retryable(status) || retries >= MAX_RETRIES) {
                return new RawResponse(status, response.body() == null ? "" : response.body());
            }
            Duration wait = retryAfter(response).orElse(backoff(retries));
            retries++;
            log.info("Coverage call to {} answered HTTP {}; retry {} of {} in {} ms", safe(request.uri()), status,
                    retries, MAX_RETRIES, wait.toMillis());
            try {
                pause.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CoverageTransportException("interrupted calling " + safe(request.uri()), e);
            }
        }
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CoverageTransportException("call to " + safe(request.uri()) + " failed: "
                    + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoverageTransportException("interrupted calling " + safe(request.uri()), e);
        }
    }

    /** Whether a status is worth another attempt: throttling (429) or a server error (5xx). */
    static boolean retryable(int status) {
        return status == 429 || (status >= 500 && status <= 599);
    }

    /**
     * The wait before retry number {@code retry} (0 for the first retry): the base doubled per retry,
     * at most the cap.
     *
     * @param retry how many retries came before
     * @return the wait
     */
    static Duration backoff(int retry) {
        long millis = RETRY_BASE.toMillis() << Math.min(Math.max(retry, 0), 20);
        return Duration.ofMillis(Math.min(millis, RETRY_CAP.toMillis()));
    }

    /** The {@code Retry-After} of a response, capped, or empty when absent or unreadable. */
    private Optional<Duration> retryAfter(HttpResponse<String> response) {
        Optional<String> header = response.headers().firstValue("Retry-After").map(String::trim);
        if (header.isEmpty() || header.get().isEmpty()) {
            return Optional.empty();
        }
        String value = header.get();
        Duration wait;
        try {
            wait = Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                wait = Duration.between(clock.instant(), at);
            } catch (DateTimeParseException notDate) {
                return Optional.empty();
            }
        }
        if (wait.isNegative()) {
            wait = Duration.ZERO;
        }
        return Optional.of(wait.compareTo(RETRY_CAP) > 0 ? RETRY_CAP : wait);
    }

    /**
     * Parses a body.
     *
     * @param body the text
     * @return the JSON tree, or an empty object
     */
    public JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(body);
            return node == null ? mapper.createObjectNode() : node;
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    /**
     * The JSON mapper.
     *
     * @return the mapper
     */
    public ObjectMapper mapper() {
        return mapper;
    }

    /** The URI without its query, which may carry page tokens. */
    private static String safe(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
    }

    /** A call that did not complete: the collector cannot run, so it publishes nothing. */
    public static class CoverageTransportException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message what failed, without credentials
         * @param cause   the cause
         */
        public CoverageTransportException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * Creates the exception.
         *
         * @param message what failed, without credentials
         */
        public CoverageTransportException(String message) {
            super(message);
        }
    }
}
