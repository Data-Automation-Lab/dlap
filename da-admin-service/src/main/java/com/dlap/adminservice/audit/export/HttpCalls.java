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

package com.dlap.adminservice.audit.export;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * The HTTP client the cloud sinks share: one {@link HttpClient} with a connect timeout, a
 * per-request timeout, no redirects (a redirect would carry the credential elsewhere), and
 * error messages that hold the status and a short body excerpt, never a header.
 */
public final class HttpCalls {

    private static final int EXCERPT = 300;

    private final HttpClient client;
    private final Duration timeout;

    /**
     * Creates the client.
     *
     * @param timeout connect and request timeout
     */
    public HttpCalls(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Starts a request with the timeout set.
     *
     * @param uri the target
     * @return the builder
     */
    public HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(timeout);
    }

    /**
     * Sends a request and reads the body as bytes.
     *
     * @param request the request
     * @param what    a short description for error messages, for example {@code S3 PutObject}
     * @return the response
     * @throws CheckpointExportException when the call fails before a status arrives
     */
    public HttpResponse<byte[]> send(HttpRequest request, String what) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new CheckpointExportException(what + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CheckpointExportException(what + " was interrupted", e);
        }
    }

    /**
     * A failure for an unexpected status.
     *
     * @param what     the call
     * @param response the response
     * @return the exception to throw
     */
    public static CheckpointExportException unexpected(String what, HttpResponse<byte[]> response) {
        return new CheckpointExportException(what + " answered HTTP " + response.statusCode() + ": " + excerpt(response.body()));
    }

    /**
     * The start of a response body as text, with line breaks flattened.
     *
     * @param body the body
     * @return at most 300 characters
     */
    public static String excerpt(byte[] body) {
        if (body == null) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        return text.length() > EXCERPT ? text.substring(0, EXCERPT) + "..." : text;
    }

    /**
     * Reads a bearer token from a file, trimmed; the file is read on every call so a rotated
     * token takes effect without a restart.
     *
     * @param file the file
     * @return the token
     * @throws CheckpointExportException when the file is missing or empty
     */
    public static String tokenFromFile(String file) {
        try {
            String token = Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) {
                throw new CheckpointExportException("access token file " + file + " is empty");
            }
            return token;
        } catch (IOException e) {
            throw new CheckpointExportException("access token file " + file + " cannot be read", e);
        }
    }
}
