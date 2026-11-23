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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dlap.reporting.client.CatalogServiceClient.ActivityPage;
import com.dlap.reporting.client.ResilientCatalogClient;
import com.dlap.reporting.client.ResilientCatalogClient.ActivityRequest;
import com.dlap.reporting.config.SecurityConfiguration;
import com.dlap.reporting.export.ExportManifestRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import feign.FeignException;
import feign.Request;
import feign.Response;

/**
 * {@code @WebMvcTest} of the Asset Activity and Expiry report endpoints: the named-role gate, the
 * golden JSON and CSV, the manifest, the filter binding and every error status. The catalog is
 * stubbed with fixture pages in its wire shape; the service, the registry and the security
 * configuration are the real ones.
 */
@WebMvcTest(controllers = ExpiryReportController.class)
@Import({SecurityConfiguration.class, ExpiryReportControllerWebMvcTest.Config.class})
class ExpiryReportControllerWebMvcTest {

    private static final UUID EXPORT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String JSON_URL = "/api/v1/reporting/reports/expiry";
    private static final String CSV_URL = "/api/v1/reporting/reports/expiry.csv";
    private static final String MANIFEST_URL = "/api/v1/reporting/reports/expiry/exports/{id}/manifest";
    private static final String AS_OF = "asOf=2026-10-19T12:00:00Z";
    private static final UUID FAILED_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    /** The id the next export gets; the manifest registry outlives a test, so a test that expects none uses its own. */
    private static final AtomicReference<UUID> NEXT_EXPORT_ID = new AtomicReference<>(EXPORT_ID);

    /** The real service over a stubbed catalog, with a fixed clock, a fixed export id and pages of two. */
    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return ExpiryFixtures.CLOCK;
        }

        @Bean
        ExportManifestRegistry exportManifestRegistry() {
            return new ExportManifestRegistry(ExpiryFixtures.CLOCK, Duration.ofHours(24), 10);
        }

        @Bean
        ExpiryReportService expiryReportService(ResilientCatalogClient catalog, ExportManifestRegistry registry) {
            return new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, null, "live", 2, 100,
                    NEXT_EXPORT_ID::get);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResilientCatalogClient catalog;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    private static RequestPostProcessor as(String authority) {
        return jwt().authorities(new SimpleGrantedAuthority(authority));
    }

    @BeforeEach
    void resetExportId() {
        NEXT_EXPORT_ID.set(EXPORT_ID);
    }

    private void catalogServesTheSplitPages() {
        given(catalog.getAssetActivity(any()))
                .willAnswer(inv -> ExpiryFixtures.keyset((ActivityRequest) inv.getArgument(0)));
    }

    private void catalogServesOnePage() {
        given(catalog.getAssetActivity(any())).willReturn(ExpiryFixtures.all());
    }

    private static FeignException feign(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://da-catalog/x", java.util.Map.of(), null,
                StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("getAssetActivity",
                Response.builder().status(status).reason("x").request(request).headers(java.util.Map.of()).build());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // ---- access -------------------------------------------------------------------------------

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        mockMvc.perform(get(JSON_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(CSV_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(MANIFEST_URL, EXPORT_ID)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER", "DATA_SCIENTIST",
        "DATA_ANALYST", "AUDITOR", "VIEWER"})
    void allNineHumanRolesMayReadTheReportItsExportAndTheManifest(String role) throws Exception {
        catalogServesTheSplitPages();

        mockMvc.perform(get(JSON_URL).with(as("ROLE_" + role))).andExpect(status().isOk());
        mockMvc.perform(get(CSV_URL).with(as("ROLE_" + role))).andExpect(status().isOk());
        mockMvc.perform(get(MANIFEST_URL, EXPORT_ID).with(as("ROLE_" + role))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_SERVICE_ACCOUNT", "ROLE_USER", "ROLE_ADMINISTRATOR", "SCOPE_read", "ADMIN"})
    void aCallerWithoutAHumanDlapRoleIsForbiddenEverywhere(String authority) throws Exception {
        catalogServesTheSplitPages();

        mockMvc.perform(get(JSON_URL).with(as(authority))).andExpect(status().isForbidden());
        mockMvc.perform(get(CSV_URL).with(as(authority))).andExpect(status().isForbidden());
        mockMvc.perform(get(MANIFEST_URL, EXPORT_ID).with(as(authority))).andExpect(status().isForbidden());
    }

    // ---- JSON ---------------------------------------------------------------------------------

    @Test
    void theJsonReportMatchesTheGoldenOutput() throws Exception {
        catalogServesOnePage();

        String body = mockMvc.perform(get(JSON_URL + "?" + AS_OF).with(as("ROLE_VIEWER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode actual = json.readTree(body);
        assertThat(actual).isEqualTo(json.readTree(ExpiryFixtures.goldenJson("expiry-report.json")));
        List<String> fields = new java.util.ArrayList<>();
        actual.get("items").get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyElementsOf(ExpiryReportRow.COLUMNS);
    }

    @Test
    void theReportPrintsNotReadSinceForAFullAssetWithNoRecordedRead() throws Exception {
        catalogServesOnePage();

        String body = mockMvc.perform(get(JSON_URL + "?" + AS_OF).with(as("ROLE_VIEWER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode items = json.readTree(body).get("items");
        JsonNode notRead = items.get(2);
        assertThat(notRead.get("assetUri").asText()).isEqualTo("bq://example-analytics/sales/orders");
        assertThat(notRead.get("readSignalState").asText()).isEqualTo("NOT_READ");
        assertThat(notRead.get("readSignalText").asText()).isEqualTo("not read since 2026-09-20");
        assertThat(notRead.get("readCoverageSince").asText()).isEqualTo("2026-09-20T06:41:00Z");
        assertThat(notRead.get("readCoverageObservedAt").asText()).isEqualTo("2026-10-19T06:41:00Z");
        for (int i : new int[] {0, 1, 3, 4}) {
            assertThat(items.get(i).get("readSignalText").isNull()).as("item %d", i).isTrue();
        }
    }

    @Test
    void anUnknownValueIsWrittenAsNullAndNeverLeftOut() throws Exception {
        catalogServesOnePage();

        mockMvc.perform(get(JSON_URL + "?" + AS_OF).with(as("ROLE_VIEWER")))
                .andExpect(jsonPath("$.items[4].assetUri").value("s3://orphan-logs"))
                .andExpect(jsonPath("$.items[4].lastReadAt").value((Object) null))
                .andExpect(jsonPath("$.items[4].idleDays").value((Object) null))
                .andExpect(jsonPath("$.items[4].readCoverage").value((Object) null))
                .andExpect(jsonPath("$.items[4].readSignalState").value("UNKNOWN"))
                .andExpect(jsonPath("$.items[4].sizeBytes").value((Object) null))
                .andExpect(jsonPath("$.items[2].sizeBytes").value(0));
    }

    @Test
    void readCoverageIsShownAsTheCatalogDeliversItAndNotReadOnlyWithFullCoverage() throws Exception {
        catalogServesOnePage();

        String body = mockMvc.perform(get(JSON_URL + "?" + AS_OF).with(as("ROLE_AUDITOR")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        for (JsonNode item : json.readTree(body).get("items")) {
            String coverage = item.get("readCoverage").isNull() ? null : item.get("readCoverage").asText();
            boolean read = !item.get("lastReadAt").isNull();
            String state = item.get("readSignalState").asText();
            if (!read && !"FULL".equals(coverage)) {
                assertThat(state).as(item.get("assetUri").asText()).isNotEqualTo("NOT_READ");
            }
            if (state.equals("NOT_READ")) {
                assertThat(coverage).isEqualTo("FULL");
            }
        }
        assertThat(body).doesNotContainIgnoringCase("not read\"");
    }

    @Test
    void filtersAndPagingReachTheCatalogAsRepeatedAndCommaSeparatedValues() throws Exception {
        catalogServesOnePage();

        mockMvc.perform(get(JSON_URL + "?provider=gcp,aws&provider=azure&kind=GCS_BUCKET&status=active"
                + "&retentionStatus=due&legalHold=false&readCoverage=none&readCoverage=unknown&lastReadKnown=false"
                + "&idleDaysMin=180&page=3&size=2&sort=sizeBytes,desc&" + AS_OF).with(as("ROLE_CDO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.provider[0]").value("GCP"))
                .andExpect(jsonPath("$.filters.provider[1]").value("AWS"))
                .andExpect(jsonPath("$.filters.provider[2]").value("AZURE"))
                .andExpect(jsonPath("$.filters.idleDaysMin").value(180))
                .andExpect(jsonPath("$.page.sort").value("sizeBytes,desc"));

        ArgumentCaptor<ActivityRequest> sent = ArgumentCaptor.forClass(ActivityRequest.class);
        org.mockito.Mockito.verify(catalog).getAssetActivity(sent.capture());
        ActivityRequest request = sent.getValue();
        assertThat(request.provider()).containsExactly("GCP", "AWS", "AZURE");
        assertThat(request.kind()).containsExactly("GCS_BUCKET");
        assertThat(request.status()).containsExactly("ACTIVE");
        assertThat(request.retentionStatus()).containsExactly("DUE");
        assertThat(request.legalHold()).isEqualTo("false");
        assertThat(request.readCoverage()).containsExactly("NONE", "UNKNOWN");
        assertThat(request.lastReadKnown()).isEqualTo("false");
        assertThat(request.lastReadBefore()).isEqualTo("2026-04-22T12:00:00.000000001Z");
        assertThat(request.page()).isEqualTo(3);
        assertThat(request.size()).isEqualTo(2);
        assertThat(request.sort()).isEqualTo("sizeBytes,desc");
        assertThat(request.asOf()).isEqualTo("2026-10-19T12:00:00Z");
    }

    // ---- CSV and manifest ---------------------------------------------------------------------

    @Test
    void theCsvExportMatchesTheGoldenFileAndItsManifestVerifiesIt() throws Exception {
        catalogServesTheSplitPages();

        MvcResult export = mockMvc.perform(get(CSV_URL + "?" + AS_OF).with(as("ROLE_DATA_ANALYST")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"expiry-report-20261019T120000Z.csv\""))
                .andExpect(header().string("X-Export-Id", EXPORT_ID.toString()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Link", "</api/v1/reporting/reports/expiry/exports/" + EXPORT_ID
                        + "/manifest>; rel=\"describedby\""))
                .andReturn();
        byte[] csv = export.getResponse().getContentAsByteArray();

        assertThat(csv).isEqualTo(ExpiryFixtures.goldenCsv());

        String manifest = mockMvc.perform(get(MANIFEST_URL, EXPORT_ID).with(as("ROLE_AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode parsed = json.readTree(manifest);
        assertThat(parsed.get("sha256").asText()).isEqualTo(sha256(csv));
        assertThat(parsed.get("bytes").asLong()).isEqualTo(csv.length);
        assertThat(parsed.get("rows").asInt()).isEqualTo(5);
        assertThat(parsed).isEqualTo(json.readTree(ExpiryFixtures.goldenJson("expiry-export-manifest.json")));
    }

    @Test
    void theCsvNeutralisesAFormulaInAnAssetName() throws Exception {
        catalogServesTheSplitPages();

        String csv = mockMvc.perform(get(CSV_URL + "?" + AS_OF).with(as("ROLE_VIEWER")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://example.invalid\"\",\"\"raw\"\")\"");
        assertThat(csv).doesNotContain(",=HYPERLINK").doesNotContain(",\"=HYPERLINK");
    }

    @Test
    void theCsvNeverSaysNotReadForAnAssetWithoutFullCoverage() throws Exception {
        catalogServesTheSplitPages();

        String csv = mockMvc.perform(get(CSV_URL + "?" + AS_OF).with(as("ROLE_VIEWER")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        long notRead = csv.lines().filter(l -> l.contains("NOT_READ")).count();
        assertThat(notRead).isEqualTo(1);
        assertThat(csv.lines().filter(l -> l.contains("NOT_READ")).findFirst().orElseThrow())
                .contains(",NOT_READ,FULL,");
        assertThat(csv.lines().filter(l -> l.toLowerCase(java.util.Locale.ROOT).contains("not read")).toList())
                .as("the words appear only on the FULL-coverage row, with the coverage start")
                .singleElement().asString().contains(",NOT_READ,FULL,").endsWith(",not read since 2026-09-20");
    }

    @Test
    void anUnknownOrMalformedManifestIdIsRefusedWithAClearStatus() throws Exception {
        mockMvc.perform(get(MANIFEST_URL, UUID.randomUUID()).with(as("ROLE_VIEWER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("is known to this instance")));
        mockMvc.perform(get(MANIFEST_URL, "not-a-uuid").with(as("ROLE_VIEWER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UUID")));
    }

    // ---- errors -------------------------------------------------------------------------------

    @Test
    void aMalformedFilterIsA400ThatNamesTheParameterAndNeverCallsTheCatalog() throws Exception {
        mockMvc.perform(get(JSON_URL + "?lastReadBefore=yesterday").with(as("ROLE_VIEWER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("lastReadBefore")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value(JSON_URL));
        mockMvc.perform(get(CSV_URL + "?readCoverage=most").header("Accept", "text/csv").with(as("ROLE_VIEWER")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("readCoverage")));
        mockMvc.perform(get(JSON_URL + "?size=abc").with(as("ROLE_VIEWER")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(JSON_URL + "?sort=password").with(as("ROLE_VIEWER")))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(catalog);
    }

    @Test
    void anUnavailableCatalogIsA503ForJsonAndCsvAndNeverAnEmptyReport() throws Exception {
        given(catalog.getAssetActivity(any())).willThrow(feign(500));

        mockMvc.perform(get(JSON_URL).with(as("ROLE_VIEWER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The catalog answered HTTP 500"));
        mockMvc.perform(get(CSV_URL).with(as("ROLE_VIEWER")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void anExportLargerThanTheLimitIsA413BeforeAnyByteIsSent() throws Exception {
        ActivityPage first = ExpiryFixtures.split(0);
        given(catalog.getAssetActivity(any())).willReturn(
                new ActivityPage(first.content(), 1_000, 500, 0, first.size()));

        MvcResult result = mockMvc.perform(get(CSV_URL).with(as("ROLE_VIEWER")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("narrow the filters")))
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Export-Id")).isNull();
    }

    @Test
    void anExportThatFailsAfterItStartedEndsWithTheMarkerHasNoManifestAndNoJsonError() throws Exception {
        NEXT_EXPORT_ID.set(FAILED_ID);
        given(catalog.getAssetActivity(any())).willAnswer(inv -> {
            int page = ExpiryFixtures.fixturePage(((ActivityRequest) inv.getArgument(0)).after());
            if (page == 1) {
                throw feign(503);
            }
            return ExpiryFixtures.split(page);
        });

        MvcResult result = mockMvc.perform(get(CSV_URL + "?" + AS_OF).with(as("ROLE_VIEWER")))
                .andExpect(status().isOk())
                .andReturn();

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("assetUri,assetId,");
        assertThat(csv).contains("\"EXPORT INCOMPLETE, DO NOT USE: the catalog failed at page 1");
        assertThat(csv).doesNotContain("\"timestamp\"").doesNotContain("\"status\"");
        mockMvc.perform(get(MANIFEST_URL, FAILED_ID).with(as("ROLE_VIEWER"))).andExpect(status().isNotFound());
    }
}
