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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local HTTP server for the coverage transport tests: answers each {@code METHOD path?query}
 * with a configured status and body (404 otherwise) and records every request.
 */
public final class LocalJsonServer implements AutoCloseable {

    /**
     * A recorded request.
     *
     * @param method        the method
     * @param pathAndQuery  the raw path and query
     * @param authorization the Authorization header, or {@code null}
     * @param body          the body text
     */
    public record Request(String method, String pathAndQuery, String authorization, String body) {
    }

    private record Answer(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, Answer> answers = new LinkedHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    /**
     * Starts the server on a free port of 127.0.0.1.
     *
     * @throws IOException when the port cannot be bound
     */
    public LocalJsonServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * The base URL.
     *
     * @return {@code http://127.0.0.1:<port>}
     */
    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Configures an answer.
     *
     * @param methodAndPath for example {@code GET /v3/projects/p}
     * @param status        the status
     * @param body          the body
     * @return this server
     */
    public LocalJsonServer answer(String methodAndPath, int status, String body) {
        answers.put(methodAndPath, new Answer(status, body));
        return this;
    }

    /**
     * The recorded requests.
     *
     * @return a copy
     */
    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String target = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        requests.add(new Request(exchange.getRequestMethod(), target,
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Answer a = answers.getOrDefault(exchange.getRequestMethod() + " " + target, new Answer(404, "{}"));
        byte[] out = a.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(a.status(), out.length == 0 ? -1 : out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
