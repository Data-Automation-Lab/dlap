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

package com.dlap.reporting.report.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dlap.reporting.client.CatalogServiceClient.ActivityPage;
import com.dlap.reporting.client.CatalogServiceClient.ActivityRow;
import com.dlap.reporting.client.ResilientCatalogClient;
import com.dlap.reporting.client.ResilientCatalogClient.ActivityRequest;
import com.dlap.reporting.common.exception.GlobalExceptionHandler;
import com.dlap.reporting.config.SecurityConfiguration;
import com.dlap.reporting.export.ExportManifestRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import feign.FeignException;
import feign.Request;
import feign.Response;

/**
 * The report on a real Tomcat: what MockMvc cannot show. The export is sent chunked (no length is
 * known up front), the {@code .csv} path is served by its own mapping, a large export matches its
 * manifest, and an export that fails after it started ends with the marker, with no JSON error
 * appended and no manifest. Security is the service's real configuration with the JWT decoder
 * replaced by a stub; the catalog is a stub that serves generated pages.
 */
@SpringBootTest(
        classes = ExpiryReportServerTest.ServerConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test",
            "spring.http.converters.preferred-json-mapper=jackson2",
            "spring.main.banner-mode=off"
        })
class ExpiryReportServerTest {

    private static final int PAGE_SIZE = 200;
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    @Configuration
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
        Jackson2AutoConfiguration.class, SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class})
    @Import({SecurityConfiguration.class, ExpiryReportController.class, GlobalExceptionHandler.class})
    static class ServerConfig {
        @Bean
        ExportManifestRegistry exportManifestRegistry() {
            return new ExportManifestRegistry(ExpiryFixtures.CLOCK, Duration.ofHours(1), 20);
        }

        @Bean
        ExpiryReportService expiryReportService(ResilientCatalogClient catalog, ExportManifestRegistry registry) {
            return new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, null, "live", PAGE_SIZE, 100_000,
                    UUID::randomUUID);
        }
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private ResilientCatalogClient catalog;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void authenticate() {
        Jwt jwt = Jwt.withTokenValue("good").header("alg", "none").subject("user-1")
                .claim("realm_access", Map.of("roles", List.of("VIEWER")))
                .issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(600)).build();
        given(jwtDecoder.decode("good")).willReturn(jwt);
    }

    private HttpResponse<byte[]> get(String path, boolean token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60));
        if (token) {
            request.header("Authorization", "Bearer good");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static FeignException feign(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://da-catalog/x", Map.of(), null,
                StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("getAssetActivity",
                Response.builder().status(status).reason("x").request(request).headers(Map.of()).build());
    }

    /** {@code total} synthetic rows, each a copy of the fixture's GCS bucket with its own id and URI. */
    private static List<ActivityRow> generatedRows(int total) {
        ActivityRow template = ExpiryFixtures.all().content().get(3);
        List<ActivityRow> rows = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = JSON.convertValue(template, Map.class);
            asMap.put("assetId", new UUID(0, i + 1).toString());
            asMap.put("assetUri", String.format("gs://bucket-%06d", i));
            asMap.put("assetName", String.format("bucket-%06d", i));
            rows.add(JSON.convertValue(asMap, ActivityRow.class));
        }
        return rows;
    }

    /** The keyset page after {@code after}: the generated rows are in assetUri order. */
    private static ActivityPage keysetPage(List<ActivityRow> rows, String after) {
        int pages = (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int from = 0;
        if (after != null) {
            while (from < rows.size() && !rows.get(from).assetUri().equals(after)) {
                from++;
            }
            from++;
        }
        from = Math.min(from, rows.size());
        int to = Math.min(from + PAGE_SIZE, rows.size());
        boolean more = to < rows.size();
        return new ActivityPage(rows.subList(from, to), rows.size(), pages, 0, PAGE_SIZE,
                more ? rows.get(to - 1).assetUri() : null, !more);
    }

    private void catalogServes(List<ActivityRow> rows) {
        given(catalog.getAssetActivity(any())).willAnswer(inv ->
                keysetPage(rows, ((ActivityRequest) inv.getArgument(0)).after()));
    }

    @Test
    void theGoldenCsvIsSentChunkedUnderItsOwnMappingAndTheManifestVerifiesIt() throws Exception {
        given(catalog.getAssetActivity(any())).willAnswer(inv ->
                ExpiryFixtures.keyset((ActivityRequest) inv.getArgument(0)));

        HttpResponse<byte[]> export = get("/api/v1/reporting/reports/expiry.csv?asOf=2026-10-19T12:00:00Z", true);

        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.headers().firstValue("Transfer-Encoding")).contains("chunked");
        assertThat(export.headers().firstValue("Content-Length")).isEmpty();
        assertThat(export.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("text/csv").containsIgnoringCase("charset=UTF-8"));
        assertThat(export.body()).isEqualTo(ExpiryFixtures.goldenCsv());
        String exportId = export.headers().firstValue("X-Export-Id").orElseThrow();

        HttpResponse<byte[]> manifest = get("/api/v1/reporting/reports/expiry/exports/" + exportId + "/manifest", true);
        JsonNode parsed = JSON.readTree(manifest.body());
        assertThat(manifest.statusCode()).isEqualTo(200);
        assertThat(parsed.get("sha256").asText()).isEqualTo(sha256(export.body()));
        assertThat(parsed.get("bytes").asLong()).isEqualTo(export.body().length);
    }

    @Test
    void aTokenIsRequired() throws Exception {
        assertThat(get("/api/v1/reporting/reports/expiry.csv", false).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/reporting/reports/expiry", false).statusCode()).isEqualTo(401);
    }

    @Test
    void aFiveThousandRowExportStreamsAcrossTwentyFivePagesAndMatchesItsManifest() throws Exception {
        catalogServes(generatedRows(5_000));
        long started = System.nanoTime();

        HttpResponse<byte[]> export = get("/api/v1/reporting/reports/expiry.csv?asOf=2026-10-19T12:00:00Z", true);

        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        String csv = new String(export.body(), StandardCharsets.UTF_8);
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(csv.lines().count()).isEqualTo(5_001);
        assertThat(csv).contains("gs://bucket-000000,").contains("gs://bucket-004999,");
        HttpResponse<byte[]> manifest = get("/api/v1/reporting/reports/expiry/exports/"
                + export.headers().firstValue("X-Export-Id").orElseThrow() + "/manifest", true);
        JsonNode parsed = JSON.readTree(manifest.body());
        assertThat(parsed.get("rows").asInt()).isEqualTo(5_000);
        assertThat(parsed.get("sha256").asText()).isEqualTo(sha256(export.body()));
        assertThat(parsed.at("/source/totalElements").asInt()).isEqualTo(5_000);
        System.out.println("RPT-01 measured: 5000-row CSV export, " + export.body().length + " bytes in " + millis
                + " ms over a loopback Tomcat with a stubbed catalog (25 pages of 200)");
    }

    @Test
    void anExportThatFailsAfterItStartedEndsWithTheMarkerOverARealConnection() throws Exception {
        List<ActivityRow> rows = generatedRows(1_000);
        given(catalog.getAssetActivity(any())).willAnswer(inv -> {
            String after = ((ActivityRequest) inv.getArgument(0)).after();
            // the fourth request (page 3) is the one that follows the cursor of row 599
            if ("gs://bucket-000599".equals(after)) {
                throw feign(503);
            }
            return keysetPage(rows, after);
        });

        HttpResponse<byte[]> export = get("/api/v1/reporting/reports/expiry.csv?asOf=2026-10-19T12:00:00Z", true);

        String csv = new String(export.body(), StandardCharsets.UTF_8);
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(csv).contains("gs://bucket-000599,").doesNotContain("gs://bucket-000600,");
        assertThat(csv).endsWith("the catalog failed at page 3: The catalog answered HTTP 503");
        assertThat(csv).doesNotContain("\"timestamp\"");
        String exportId = export.headers().firstValue("X-Export-Id").orElseThrow();
        assertThat(get("/api/v1/reporting/reports/expiry/exports/" + exportId + "/manifest", true).statusCode())
                .isEqualTo(404);
    }

    @Test
    void anOversizedExportIsA413OnARealConnectionBeforeTheBody() throws Exception {
        given(catalog.getAssetActivity(any())).willReturn(new ActivityPage(List.of(), 500_000, 2_500, 0, PAGE_SIZE));

        HttpResponse<byte[]> export = get("/api/v1/reporting/reports/expiry.csv", true);

        assertThat(export.statusCode()).isEqualTo(413);
        assertThat(export.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("json"));
        assertThat(new String(export.body(), StandardCharsets.UTF_8)).contains("narrow the filters");
    }
}
