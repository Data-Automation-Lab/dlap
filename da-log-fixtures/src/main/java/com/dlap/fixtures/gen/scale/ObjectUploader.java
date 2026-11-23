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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A minimal unsigned, path-style S3 client for a LOCAL emulator: it creates a bucket and puts objects
 * with plain HTTP. It never signs a request and carries no credential, so it cannot authenticate to a
 * real S3 endpoint; the harness points it at the LocalStack container it started.
 */
public final class ObjectUploader {

    private static final int ATTEMPTS = 4;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String endpoint;

    /**
     * Creates the uploader.
     *
     * @param endpoint the emulator's base URL, for example {@code http://localhost:4566}
     */
    public ObjectUploader(String endpoint) {
        if (endpoint == null || !endpoint.matches("https?://[^/\\s]+")) {
            throw new IllegalArgumentException("endpoint must be a base URL such as http://localhost:4566");
        }
        this.endpoint = endpoint;
    }

    /**
     * Creates a bucket; a bucket that already exists is not an error.
     *
     * @param bucket the bucket name
     * @throws IOException when the emulator refuses or cannot be reached after the retries
     */
    public void createBucket(String bucket) throws IOException {
        int code = send(HttpRequest.newBuilder(URI.create(endpoint + "/" + bucket))
                .PUT(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(30)).build(), "create bucket " + bucket);
        if (code != 200 && code != 409) {
            throw new IOException("create bucket " + bucket + " answered HTTP " + code);
        }
    }

    /**
     * Puts one object.
     *
     * @param bucket the bucket
     * @param key    the object key, as it appears in the URL path
     * @param body   the bytes
     * @throws IOException when the emulator refuses or cannot be reached after the retries
     */
    public void put(String bucket, String key, byte[] body) throws IOException {
        int code = send(HttpRequest.newBuilder(URI.create(endpoint + "/" + bucket + "/" + key))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).timeout(Duration.ofSeconds(120)).build(),
                "put " + key);
        if (code != 200) {
            throw new IOException("put " + key + " answered HTTP " + code);
        }
    }

    private int send(HttpRequest request, String what) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(what + " was interrupted", e);
            }
            try {
                Thread.sleep(200L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(what + " was interrupted", e);
            }
        }
        throw new IOException(what + " failed after " + ATTEMPTS + " attempts", last);
    }
}
