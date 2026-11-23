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

package com.dlap.autodelete.providers.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dlap.autodelete.nativerule.BucketVersioning;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.TestEndpoints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.StorageOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Q-713 (RET-08, decision D38): with {@code dlap.autodelete.test-endpoint.gcs} set, the GCS applier talks to that
 * host (fake-gcs-server in a harness) without credentials, and logs a WARN at start-up. Proven against a local HTTP
 * stub of the JSON API's bucket read: the versioning check goes to the stub and reads its answer.
 */
class GcsTestEndpointTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String bucketJson = "{\"kind\":\"storage#bucket\",\"name\":\"dlap-gcp-e2e\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = bucketJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String host() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static NativeRule rule() {
        return new GcsLifecycleRuleGenerator().generate(AssetIdNormalizer.normalize("gs://dlap-gcp-e2e").orElseThrow(),
                UUID.fromString("abcdabcd-0000-4000-8000-0000000000c1"), Instant.parse("2026-11-01T10:15:00Z"));
    }

    /** Q-713: the options of the test endpoint are the emulator's host, a fixed project and no credentials. */
    @Test
    void theTestEndpointOptionsUseTheEmulatorHostWithoutCredentials() {
        StorageOptions options = GcsLifecycleRuleApplier.testEndpointOptions("http://fake-gcs:4443");

        assertThat(options.getHost()).isEqualTo("http://fake-gcs:4443");
        assertThat(options.getCredentials()).isSameAs(NoCredentials.getInstance());
        assertThat(options.getProjectId()).isEqualTo("dlap-test-endpoint");
    }

    /**
     * Q-713: the applier Spring builds from the setting reads the bucket from the test endpoint, sends no token,
     * and logs the start-up WARN.
     */
    @Test
    void theApplierBuiltFromTheSettingReadsTheBucketFromTheTestEndpoint() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger log = (Logger) LoggerFactory.getLogger(GcsLifecycleRuleApplier.class);
        logs.start();
        log.addAppender(logs);
        GcsLifecycleRuleApplier applier;
        try {
            applier = new GcsLifecycleRuleApplier(new TestEndpoints("", true, host(), ""));
        } finally {
            log.detachAppender(logs);
        }
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("GCS_LIFECYCLE").contains("TEST-ONLY").contains(host());
        });

        assertThat(applier.versioning(rule())).isEqualTo(BucketVersioning.UNVERSIONED);
        bucketJson = "{\"kind\":\"storage#bucket\",\"name\":\"dlap-gcp-e2e\",\"versioning\":{\"enabled\":true}}";
        assertThat(applier.versioning(rule())).isEqualTo(BucketVersioning.ENABLED);

        assertThat(requests).isNotEmpty().allSatisfy(r -> {
            assertThat(r).contains("/b/dlap-gcp-e2e");
            assertThat(r).endsWith("auth=null");
        });
    }

    /** Q-713: without the setting there is no warning. */
    @Test
    void noTestEndpointMeansNoWarning() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger log = (Logger) LoggerFactory.getLogger(GcsLifecycleRuleApplier.class);
        logs.start();
        log.addAppender(logs);
        try {
            new GcsLifecycleRuleApplier(TestEndpoints.none());
        } finally {
            log.detachAppender(logs);
        }
        assertThat(logs.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("TEST-ONLY"));
    }
}
