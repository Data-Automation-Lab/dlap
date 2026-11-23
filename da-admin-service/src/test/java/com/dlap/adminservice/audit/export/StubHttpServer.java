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
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A loopback HTTP server for the sink tests: records every request and answers with whatever the
 * test's responder returns. Stands in for the cloud REST APIs, which no emulator here implements
 * with Object Lock, bucket lock or blob immutability (critique item 19: the real behaviour is a
 * live check).
 */
public final class StubHttpServer implements AutoCloseable {

    /**
     * A recorded request.
     *
     * @param method  the method
     * @param uri     the request URI (path and query)
     * @param headers lower-case header names, first value each
     * @param body    the body
     */
    public record Request(String method, URI uri, Map<String, String> headers, byte[] body) {

        /** @return the body as UTF-8 text */
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * A response.
     *
     * @param status  the status
     * @param headers extra headers
     * @param body    the body
     */
    public record Response(int status, Map<String, String> headers, byte[] body) {

        /**
         * A response with a text body.
         *
         * @param status the status
         * @param body   the body
         * @return the response
         */
        public static Response of(int status, String body) {
            return new Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * A response with headers and a text body.
         *
         * @param status  the status
         * @param headers the headers
         * @param body    the body
         * @return the response
         */
        public static Response of(int status, Map<String, String> headers, String body) {
            return new Response(status, headers, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Request, Response> responder = r -> Response.of(404, "no route");

    /** Starts the server on a free loopback port. */
    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * Sets how requests are answered.
     *
     * @param value the responder
     */
    public void respond(Function<Request, Response> value) {
        this.responder = value;
    }

    /** @return the base URL, for example {@code http://127.0.0.1:54321} */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** @return the recorded requests, oldest first */
    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI(), headers,
                exchange.getRequestBody().readAllBytes());
        requests.add(request);
        Response response = responder.apply(request);
        response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] body = response.body();
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
