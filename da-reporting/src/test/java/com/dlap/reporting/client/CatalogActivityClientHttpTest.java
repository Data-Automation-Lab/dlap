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

package com.dlap.reporting.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.dlap.reporting.client.CatalogServiceClient.ActivityPage;
import com.dlap.reporting.client.CatalogServiceClient.ActivityRow;
import com.sun.net.httpserver.HttpServer;

import feign.FeignException;

/**
 * The real Feign client against a real HTTP server that answers like the catalog: how the filters
 * are written on the wire (repeated parameters, omitted nulls), and how the catalog's page, with
 * the Spring Data properties it adds, is read back. The server is the JDK's, so the test adds no
 * dependency and needs no Docker. The page bodies are the report fixtures.
 */
@SpringBootTest(classes = CatalogActivityClientHttpTest.TestApp.class)
class CatalogActivityClientHttpTest {

    private static HttpServer server;
    private static final AtomicReference<String> RAW_QUERY = new AtomicReference<>();
    private static final AtomicReference<String> PATH = new AtomicReference<>();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> FIXTURE = new AtomicReference<>("catalog-activity-page-0.json");

    @Configuration
    @EnableFeignClients(clients = CatalogServiceClient.class)
    @ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
        Jackson2AutoConfiguration.class})
    static class TestApp {
    }

    @BeforeAll
    static void startCatalog() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            RAW_QUERY.set(exchange.getRequestURI().getRawQuery());
            PATH.set(exchange.getRequestURI().getPath());
            byte[] body;
            try (InputStream in = CatalogActivityClientHttpTest.class.getResourceAsStream("/expiry/" + FIXTURE.get())) {
                body = STATUS.get() == 200 ? in.readAllBytes() : "{\"message\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(STATUS.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopCatalog() {
        server.stop(0);
    }

    @DynamicPropertySource
    static void catalogUrl(DynamicPropertyRegistry registry) throws IOException {
        if (server == null) {
            startCatalog();
        }
        registry.add("dlap.services.da-catalog.url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("spring.http.converters.preferred-json-mapper", () -> "jackson2");
    }

    @Autowired
    private CatalogServiceClient client;

    @BeforeEach
    void reset() {
        RAW_QUERY.set(null);
        PATH.set(null);
        STATUS.set(200);
        FIXTURE.set("catalog-activity-page-0.json");
    }

    private static Map<String, List<String>> query(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            out.computeIfAbsent(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), k -> new ArrayList<>())
                    .add(URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void writesEveryFilterAsARepeatedParameterAndLeavesNullsOut() {
        client.getAssetActivity(3, 200, "assetUri,asc", List.of("GCP", "AWS"), List.of("GCS_BUCKET"), null,
                "2026-04-22T12:00:00.000000001Z", null, "false", List.of("DUE", "ON_HOLD"), null,
                List.of("PARTIAL", "UNKNOWN"), "2026-10-19T12:00:00Z", null);

        assertThat(PATH.get()).isEqualTo("/api/v1/catalog/assets/activity");
        Map<String, List<String>> q = query(RAW_QUERY.get());
        assertThat(q.get("page")).containsExactly("3");
        assertThat(q.get("size")).containsExactly("200");
        assertThat(q.get("sort")).containsExactly("assetUri,asc");
        assertThat(q.get("provider")).containsExactly("GCP", "AWS");
        assertThat(q.get("kind")).containsExactly("GCS_BUCKET");
        assertThat(q.get("lastReadBefore")).containsExactly("2026-04-22T12:00:00.000000001Z");
        assertThat(q.get("lastReadKnown")).containsExactly("false");
        assertThat(q.get("retentionStatus")).containsExactly("DUE", "ON_HOLD");
        assertThat(q.get("readCoverage")).containsExactly("PARTIAL", "UNKNOWN");
        assertThat(q.get("asOf")).containsExactly("2026-10-19T12:00:00Z");
        assertThat(q).doesNotContainKeys("status", "lastReadAfter", "legalHold", "after");
    }

    @Test
    void writesTheKeysetCursorAsAfterWhenOneIsGiven() {
        client.getAssetActivity(0, 200, "assetUri,asc", null, null, null, null, null, null, null, null, null,
                "2026-10-19T12:00:00Z", "gs://bucket with space&sign");

        Map<String, List<String>> q = query(RAW_QUERY.get());
        assertThat(q.get("after")).containsExactly("gs://bucket with space&sign");
        assertThat(q.get("page")).containsExactly("0");
        assertThat(q.get("sort")).containsExactly("assetUri,asc");
    }

    @Test
    void readsTheCatalogsPageIgnoringTheSpringDataProperties() {
        ActivityPage page = client.getAssetActivity(0, 2, "assetUri,asc", null, null, null, null, null, null, null,
                null, null, null, null);

        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.number()).isZero();
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.content()).hasSize(2);
        ActivityRow rds = page.content().get(0);
        assertThat(rds.assetUri()).isEqualTo("arn:aws:rds:eu-west-1:111122223333:db:prod-postgres");
        assertThat(rds.sizeBytes()).isEqualTo(536_870_912_000L);
        assertThat(rds.cloudCreatedAt()).isEqualTo(Instant.parse("2025-01-10T08:00:00Z"));
        assertThat(rds.ageDays()).isEqualTo(647L);
        assertThat(rds.lastAccessedAt()).isNull();
        assertThat(rds.idleDays()).isNull();
        assertThat(rds.readCoverage()).isEqualTo("NONE");
        assertThat(rds.retentionStatus()).isEqualTo("ON_HOLD");
        assertThat(rds.retentionDays()).isEqualTo(730);
        assertThat(rds.legalHold()).isTrue();
        assertThat(rds.labels()).containsExactly("Finance", "Prod");
        assertThat(page.content().get(1).labels()).containsExactly("a;b");
        // The keyset cursor and the last-page flag come from the catalog's page.
        assertThat(page.nextAfter()).isEqualTo("az://examplestore/raw");
        assertThat(page.last()).isFalse();
    }

    @Test
    void aRetentionStatusTheReportDoesNotKnowYetStillReads() {
        FIXTURE.set("catalog-activity-future.json");

        ActivityPage page = client.getAssetActivity(0, 2, "assetUri,asc", null, null, null, null, null, null, null,
                null, null, null, null);

        assertThat(page.content().get(0).retentionStatus()).isEqualTo("QUARANTINED");
        assertThat(page.content().get(0).readCoverage()).isEqualTo("FULL_EXTENDED");
    }

    @Test
    void anErrorStatusReachesTheCallerAsAFeignExceptionWithTheStatus() {
        STATUS.set(503);

        assertThatThrownBy(() -> client.getAssetActivity(0, 2, "assetUri,asc", null, null, null, null, null, null,
                null, null, null, null, null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(503));
    }
}
