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

package com.dlap.logingest.providers.gcp.bigquery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One goccy/bigquery-emulator container per test JVM (MIT; pinned by digest, so a rebuilt tag cannot
 * change a test). The emulator speaks the BigQuery REST API with a GoogleSQL engine. It has no
 * {@code INFORMATION_SCHEMA.JOBS}, so tests use it for ordinary tables only. Requires Docker; without
 * it the container fails to start and the test errors, which counts as unverified (plan rule R11).
 */
public final class BigQueryEmulator {

    /** The emulator's default project. */
    public static final String PROJECT = "proj-fx-demo";

    private static final String IMAGE = "ghcr.io/goccy/bigquery-emulator@sha256:"
            + "f4e428d265a93dc5ce36c294e1c584c7c9b384117d47ab8ddbb63d8d50b7f393";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static GenericContainer<?> container;

    private BigQueryEmulator() {
    }

    /** Starts the container once per JVM and creates the {@code scan} dataset. */
    public static synchronized void start() throws Exception {
        if (container != null && container.isRunning()) {
            return;
        }
        container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withCommand("--project=" + PROJECT, "--log-level=error")
                .withExposedPorts(9050)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(3));
        container.start();
        post("/datasets", JSON.readTree("{\"datasetReference\":{\"projectId\":\"" + PROJECT + "\",\"datasetId\":\"scan\"}}"));
    }

    /**
     * The REST root for the project.
     *
     * @return the URL up to and including the project
     */
    public static String restRoot() {
        return host() + "/bigquery/v2/projects/" + PROJECT;
    }

    /**
     * The emulator's HTTP host.
     *
     * @return {@code http://host:port}
     */
    public static String host() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(9050);
    }

    /**
     * Posts JSON to a path under the project, retrying while the emulator comes up.
     *
     * @param path the path under the project
     * @param body the JSON body
     * @return the JSON answer
     * @throws Exception on a non-2xx answer or an unreachable emulator
     */
    public static JsonNode post(String path, JsonNode body) throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                response = HTTP.send(HttpRequest.newBuilder(URI.create(restRoot() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                        HttpResponse.BodyHandlers.ofString());
                break;
            } catch (IOException e) {
                Thread.sleep(500);
            }
        }
        assertThat(response).as("the emulator answered %s", path).isNotNull();
        assertThat(response.statusCode()).as("%s -> %s", path, response.body()).isBetween(200, 299);
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }
}
