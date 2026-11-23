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

package com.dlap.catalog.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.catalog.common.GlobalExceptionHandler;
import com.dlap.catalog.config.SecurityConfiguration;
import com.dlap.catalog.dto.AssetActivityPageDTO;
import com.dlap.catalog.dto.AssetActivityRowDTO;
import com.dlap.catalog.dto.AssetOutputDTO;
import com.dlap.catalog.service.IAssetService;
import com.dlap.catalog.service.IAssetService.ActivityQuery;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

/**
 * {@code @WebMvcTest} slice for {@link AssetController}.
 *
 * <p>The module's {@code DaCatalogApplication} declares
 * {@code @EnableJpaRepositories} / {@code @EntityScan}, which, if used as the
 * slice's configuration source, eagerly wires the full JPA repository
 * infrastructure and fails without a database. To keep this a pure web + security
 * slice, the test supplies its own minimal {@link TestApp}
 * {@code @SpringBootConfiguration} (no JPA), and imports the real
 * {@link SecurityConfiguration} so {@code @PreAuthorize} is genuinely enforced.
 * The {@link JwtDecoder} is mocked so no Keycloak JWKS endpoint is contacted.
 *
 * <p>Covers the happy-path read shape for {@code GET /assets} and
 * {@code GET /assets/{id}}, the recently-added {@code GET /assets/resolve}
 * (200 on hit, 404 on miss), and that method security is enforced (401 for
 * anonymous, 403 for an authenticated-but-unauthorized role).
 *
 * <p>CAT-02b adds {@code GET /assets/activity} (paging, filters, the report instant, a 400 for a
 * malformed value, every named role plus the machine principal), and the time, size and
 * retention fields of the asset response, where an unknown value is omitted and never a default.
 * The module's {@link GlobalExceptionHandler} is part of the slice so a 400 is asserted as the
 * service sends it.
 */
@WebMvcTest(AssetController.class)
@ContextConfiguration(classes = AssetControllerTest.TestApp.class)
@Import({SecurityConfiguration.class, GlobalExceptionHandler.class})
class AssetControllerTest {

    /**
     * Minimal Boot configuration for the slice: enough web/security/jackson
     * auto-config to run MockMvc, deliberately excluding the JPA repository
     * wiring pulled in by the production {@code DaCatalogApplication}.
     */
    @SpringBootConfiguration
    @ImportAutoConfiguration({
        WebMvcAutoConfiguration.class,
        ErrorMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
    })
    @ComponentScan(
        basePackageClasses = AssetController.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE, classes = AssetController.class))
    static class TestApp {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IAssetService assetService;

    // Mocked so the imported SecurityConfiguration's jwtDecoder() bean (which
    // reads jwk-set-uri and would build a network-backed decoder) is replaced.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID assetId;
    private AssetOutputDTO asset;

    @BeforeEach
    void setUp() {
        assetId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        asset = new AssetOutputDTO();
        asset.setAssetId(assetId);
        asset.setAssetName("customer_master_table");
        asset.setAssetType("TABLE");
        asset.setCloudProvider("AWS");
        asset.setAssetUri("s3://dlap-demo/customer");
    }

    @Test
    @WithMockUser(roles = "DATA_ENGINEER")
    void getAssetById_returnsOkWithAssetShape() throws Exception {
        given(assetService.getAssetById(assetId)).willReturn(asset);

        mockMvc.perform(get("/api/v1/catalog/assets/{assetId}", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.toString()))
                .andExpect(jsonPath("$.assetName").value("customer_master_table"))
                .andExpect(jsonPath("$.assetType").value("TABLE"))
                .andExpect(jsonPath("$.assetUri").value("s3://dlap-demo/customer"));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void getAllAssets_returnsPagedShape() throws Exception {
        Page<AssetOutputDTO> page = new PageImpl<>(java.util.List.of(asset), Pageable.ofSize(20), 1);
        given(assetService.getAllAssets(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/catalog/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].assetId").value(assetId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ACCOUNT")
    void resolveAssetIdByUri_whenFound_returnsResolutionDto() throws Exception {
        String uri = "s3://dlap-demo/customer";
        given(assetService.resolveAssetIdByUri(uri)).willReturn(Optional.of(assetId));

        mockMvc.perform(get("/api/v1/catalog/assets/resolve").param("assetUri", uri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.toString()))
                .andExpect(jsonPath("$.assetUri").value(uri));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ACCOUNT")
    void resolveAssetIdByUri_whenMissing_returnsNotFound() throws Exception {
        String uri = "s3://dlap-demo/does-not-exist";
        given(assetService.resolveAssetIdByUri(eq(uri))).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/catalog/assets/resolve").param("assetUri", uri))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void getAllAssets_withUnauthorizedRole_returnsForbidden() throws Exception {
        // AUDITOR is authenticated but NOT in the @PreAuthorize list for
        // GET /assets - asserts @PreAuthorize is genuinely enforced.
        mockMvc.perform(get("/api/v1/catalog/assets"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllAssets_whenAnonymous_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/assets"))
                .andExpect(status().isUnauthorized());
    }

    // ---- GET /assets/activity (CAT-02b) -----------------------------------------------------

    private static final String ACTIVITY = "/api/v1/catalog/assets/activity";

    /** One fully populated row: every time, the size and the retention result are present. */
    private AssetActivityRowDTO knownRow() {
        return new AssetActivityRowDTO(assetId, "gs://finance-archive", "finance-archive", "GCP", "GCS_BUCKET",
                "BUCKET", "us-central1", "ACTIVE", "data-eng@example.com",
                Instant.parse("2026-09-01T00:00:00Z"), "CRAWLER",
                Instant.parse("2026-08-01T00:00:00Z"), "CRAWLER", 80L, 4096L,
                Instant.parse("2026-10-18T00:00:00Z"), Instant.parse("2026-10-01T10:15:30Z"),
                "AUDIT_LOG:GCP_CLOUD_AUDIT", 18L, "FULL",
                List.of("Cloud Audit DATA_READ logs are recorded"), Instant.parse("2026-10-19T06:41:00Z"),
                Instant.parse("2026-09-20T06:41:00Z"), Instant.parse("2026-10-02T00:00:00Z"),
                Instant.parse("2026-10-03T00:00:00Z"), null, RetentionStatus.RETAINED,
                Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2026-10-19T00:00:00Z"), 365,
                RetentionBasis.CREATED, "POLICY", null, false, List.of("PII", "Finance"),
                Instant.parse("2026-10-19T12:00:00Z"));
    }

    /** A row where everything the catalog cannot know is unknown. */
    private AssetActivityRowDTO unknownRow() {
        return new AssetActivityRowDTO(UUID.fromString("223e4567-e89b-12d3-a456-426614174000"), "s3://orphan", null,
                "AWS", "S3_BUCKET", null, null, "ACTIVE", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, RetentionStatus.UNKNOWN, null, null, null, null, null,
                null, false, List.of(), Instant.parse("2026-10-19T12:00:00Z"));
    }

    private ArgumentCaptor<ActivityQuery> stubActivity(AssetActivityRowDTO... rows) {
        ArgumentCaptor<ActivityQuery> query = ArgumentCaptor.forClass(ActivityQuery.class);
        given(assetService.getAssetActivity(query.capture(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(rows), Pageable.ofSize(50), rows.length));
        return query;
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_returnsAPageOfRowsWithTheFullShape() throws Exception {
        stubActivity(knownRow());

        mockMvc.perform(get(ACTIVITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].assetId").value(assetId.toString()))
                .andExpect(jsonPath("$.content[0].assetUri").value("gs://finance-archive"))
                .andExpect(jsonPath("$.content[0].cloudCreatedAt").value("2026-08-01T00:00:00Z"))
                .andExpect(jsonPath("$.content[0].ageDays").value(80))
                .andExpect(jsonPath("$.content[0].sizeBytes").value(4096))
                .andExpect(jsonPath("$.content[0].lastAccessedAt").value("2026-10-01T10:15:30Z"))
                .andExpect(jsonPath("$.content[0].idleDays").value(18))
                .andExpect(jsonPath("$.content[0].readCoverage").value("FULL"))
                .andExpect(jsonPath("$.content[0].readCoverageObservedAt").value("2026-10-19T06:41:00Z"))
                .andExpect(jsonPath("$.content[0].readCoverageSince").value("2026-09-20T06:41:00Z"))
                .andExpect(jsonPath("$.content[0].lastModifiedAt").value("2026-10-02T00:00:00Z"))
                .andExpect(jsonPath("$.content[0].retentionStatus").value("RETAINED"))
                .andExpect(jsonPath("$.content[0].retentionBasis").value("CREATED"))
                .andExpect(jsonPath("$.content[0].retentionDays").value(365))
                .andExpect(jsonPath("$.content[0].legalHold").value(false))
                .andExpect(jsonPath("$.content[0].labels[0]").value("PII"))
                .andExpect(jsonPath("$.content[0].asOf").value("2026-10-19T12:00:00Z"));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_writesUnknownValuesAsNullNeverAsZeroOrADefault() throws Exception {
        stubActivity(unknownRow());

        mockMvc.perform(get(ACTIVITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sizeBytes").value((Object) null))
                .andExpect(jsonPath("$.content[0].lastAccessedAt").value((Object) null))
                .andExpect(jsonPath("$.content[0].idleDays").value((Object) null))
                .andExpect(jsonPath("$.content[0].cloudCreatedAt").value((Object) null))
                .andExpect(jsonPath("$.content[0].readCoverage").value((Object) null))
                .andExpect(jsonPath("$.content[0].readCoverageObservedAt").value((Object) null))
                .andExpect(jsonPath("$.content[0].readCoverageSince").value((Object) null))
                .andExpect(jsonPath("$.content[0].retentionStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.content[0].legalHold").value(false));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_withNoParametersAsksForNoFilterAndLeavesTheReportInstantToTheServiceClock() throws Exception {
        ArgumentCaptor<ActivityQuery> query = stubActivity(knownRow());

        mockMvc.perform(get(ACTIVITY)).andExpect(status().isOk());

        ActivityQuery q = query.getValue();
        assertThat(q.providers()).isEmpty();
        assertThat(q.kinds()).isEmpty();
        assertThat(q.statuses()).isEmpty();
        assertThat(q.retentionStatuses()).isEmpty();
        assertThat(q.readCoverages()).isEmpty();
        assertThat(q.lastReadBefore()).isNull();
        assertThat(q.lastReadAfter()).isNull();
        assertThat(q.lastReadKnown()).isNull();
        assertThat(q.legalHold()).isNull();
        // The controller never reads a clock: the service applies its injected Clock.
        assertThat(q.asOf()).isNull();
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_passesEveryFilterToTheService() throws Exception {
        ArgumentCaptor<ActivityQuery> query = stubActivity(knownRow());

        mockMvc.perform(get(ACTIVITY)
                        .param("provider", "GCP", "AWS")
                        .param("kind", "GCS_BUCKET")
                        .param("status", "ACTIVE")
                        .param("lastReadBefore", "2026-07-19T00:00:00Z")
                        .param("lastReadAfter", "2026-01-01T00:00:00Z")
                        .param("lastReadKnown", "true")
                        .param("retentionStatus", "DUE")
                        .param("legalHold", "FALSE")
                        .param("readCoverage", "full")
                        .param("asOf", "2026-10-01T00:00:00Z"))
                .andExpect(status().isOk());

        ActivityQuery q = query.getValue();
        assertThat(q.providers()).containsExactly("GCP", "AWS");
        assertThat(q.kinds()).containsExactly("GCS_BUCKET");
        assertThat(q.statuses()).containsExactly("ACTIVE");
        assertThat(q.lastReadBefore()).isEqualTo(Instant.parse("2026-07-19T00:00:00Z"));
        assertThat(q.lastReadAfter()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(q.lastReadKnown()).isTrue();
        assertThat(q.retentionStatuses()).containsExactly("DUE");
        assertThat(q.legalHold()).isFalse();
        assertThat(q.readCoverages()).containsExactly("full");
        assertThat(q.asOf()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_acceptsACommaSeparatedListAndAnOffsetInstant() throws Exception {
        ArgumentCaptor<ActivityQuery> query = stubActivity(knownRow());

        mockMvc.perform(get(ACTIVITY)
                        .param("provider", "GCP,AWS")
                        .param("asOf", "2026-10-19T14:00:00+02:00"))
                .andExpect(status().isOk());

        assertThat(query.getValue().providers()).containsExactly("GCP", "AWS");
        assertThat(query.getValue().asOf()).isEqualTo(Instant.parse("2026-10-19T12:00:00Z"));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_pagesAndSortsThroughThePageableParameters() throws Exception {
        stubActivity(knownRow());

        mockMvc.perform(get(ACTIVITY).param("page", "2").param("size", "25").param("sort", "sizeBytes,desc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(assetService).getAssetActivity(any(ActivityQuery.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("sizeBytes")));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_defaultsToFiftyRowsOrderedByAssetUri() throws Exception {
        stubActivity(knownRow());

        mockMvc.perform(get(ACTIVITY)).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(assetService).getAssetActivity(any(ActivityQuery.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.asc("assetUri")));
    }

    // ---- keyset paging (CAT-04, Q-445) ------------------------------------------------------

    private AssetActivityRowDTO rowAt(String uri) {
        AssetActivityRowDTO k = knownRow();
        return new AssetActivityRowDTO(k.assetId(), uri, k.assetName(), k.cloudProvider(), k.serviceType(),
                k.assetType(), k.region(), k.status(), k.ownerInfo(), k.discoveredAt(), k.discoverySource(),
                k.cloudCreatedAt(), k.cloudCreatedSource(), k.ageDays(), k.sizeBytes(), k.sizeObservedAt(),
                k.lastAccessedAt(), k.lastAccessSource(), k.idleDays(), k.readCoverage(),
                k.readCoverageReasons(), k.readCoverageObservedAt(), k.readCoverageSince(), k.lastModifiedAt(),
                k.lastPermissionChangeAt(), k.recreatedAt(), k.retentionStatus(), k.retentionExpiresAt(),
                k.retentionEvaluatedAt(), k.retentionDays(), k.retentionBasis(), k.retentionSource(),
                k.retentionPolicyId(), k.legalHold(), k.labels(), k.asOf());
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_afterAsksTheKeysetPathForTheNextPageAndWritesNextAfter() throws Exception {
        given(assetService.getAssetActivityKeyset(any(ActivityQuery.class), eq("gs://a"), eq(2)))
                .willReturn(new AssetActivityPageDTO(List.of(rowAt("gs://b"), rowAt("gs://c")),
                        Pageable.ofSize(2), 9, "gs://c", false, true));

        mockMvc.perform(get(ACTIVITY).param("after", "gs://a").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].assetUri").value("gs://b"))
                .andExpect(jsonPath("$.content[1].assetUri").value("gs://c"))
                .andExpect(jsonPath("$.nextAfter").value("gs://c"))
                .andExpect(jsonPath("$.totalElements").value(9))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.first").value(false));

        verify(assetService, never()).getAssetActivity(any(), any());
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_theLastKeysetPageWritesANullNextAfter() throws Exception {
        given(assetService.getAssetActivityKeyset(any(ActivityQuery.class), eq("gs://c"), eq(50)))
                .willReturn(new AssetActivityPageDTO(List.of(rowAt("gs://d")), Pageable.ofSize(50), 4, null,
                        false, false));

        mockMvc.perform(get(ACTIVITY).param("after", "gs://c"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextAfter").value((Object) null))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_keysetCarriesTheFiltersToo() throws Exception {
        ArgumentCaptor<ActivityQuery> query = ArgumentCaptor.forClass(ActivityQuery.class);
        given(assetService.getAssetActivityKeyset(query.capture(), eq("gs://a"), eq(50)))
                .willReturn(new AssetActivityPageDTO(List.of(), Pageable.ofSize(50), 0, null, false, false));

        mockMvc.perform(get(ACTIVITY).param("after", "gs://a").param("provider", "GCP").param("legalHold", "true"))
                .andExpect(status().isOk());

        assertThat(query.getValue().providers()).containsExactly("GCP");
        assertThat(query.getValue().legalHold()).isTrue();
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_aBlankAfterIsOrdinaryOffsetPaging() throws Exception {
        stubActivity(knownRow());

        mockMvc.perform(get(ACTIVITY).param("after", "  ")).andExpect(status().isOk());

        verify(assetService, never()).getAssetActivityKeyset(any(), any(), anyInt());
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_afterWithAPageNumberIsABadRequest() throws Exception {
        mockMvc.perform(get(ACTIVITY).param("after", "gs://a").param("page", "1"))
                .andExpect(status().isBadRequest());

        verify(assetService, never()).getAssetActivityKeyset(any(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sizeBytes,asc", "assetUri,desc", "assetName"})
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_afterWithAnotherSortThanAssetUriAscendingIsABadRequest(String sort) throws Exception {
        mockMvc.perform(get(ACTIVITY).param("after", "gs://a").param("sort", sort))
                .andExpect(status().isBadRequest());

        verify(assetService, never()).getAssetActivityKeyset(any(), any(), anyInt());
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_aCursorTheServiceRejectsIsABadRequest() throws Exception {
        given(assetService.getAssetActivityKeyset(any(ActivityQuery.class), any(), anyInt()))
                .willThrow(new IllegalArgumentException("after is not an asset URI"));

        mockMvc.perform(get(ACTIVITY).param("after", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("after is not an asset URI"));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_offsetPagingIsUnchangedAndAFirstPageOffersTheCursorToStartAWalk() throws Exception {
        given(assetService.getAssetActivity(any(ActivityQuery.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(rowAt("gs://a"), rowAt("gs://b")), PageRequest.of(0, 2), 5));

        mockMvc.perform(get(ACTIVITY).param("size", "2"))
                .andExpect(status().isOk())
                // The Spring page fields an offset caller has always read.
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.numberOfElements").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.empty").value(false))
                .andExpect(jsonPath("$.pageable").exists())
                .andExpect(jsonPath("$.sort").exists())
                // The one addition.
                .andExpect(jsonPath("$.nextAfter").value("gs://b"));

        verify(assetService, never()).getAssetActivityKeyset(any(), any(), anyInt());
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_offsetPageInAnotherSortOrderHasNoCursor() throws Exception {
        given(assetService.getAssetActivity(any(ActivityQuery.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(rowAt("gs://a"), rowAt("gs://b")), PageRequest.of(0, 2), 5));

        mockMvc.perform(get(ACTIVITY).param("size", "2").param("sort", "sizeBytes,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextAfter").value((Object) null))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_theLastOffsetPageHasNoCursor() throws Exception {
        given(assetService.getAssetActivity(any(ActivityQuery.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(rowAt("gs://e")), PageRequest.of(2, 2), 5));

        mockMvc.perform(get(ACTIVITY).param("size", "2").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextAfter").value((Object) null))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.first").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lastReadBefore", "lastReadAfter", "asOf"})
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_aMalformedInstantIsABadRequestNotAServerError(String parameter) throws Exception {
        mockMvc.perform(get(ACTIVITY).param(parameter, "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString(parameter)));

        verify(assetService, never()).getAssetActivity(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lastReadKnown", "legalHold"})
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_aMalformedBooleanIsABadRequest(String parameter) throws Exception {
        mockMvc.perform(get(ACTIVITY).param(parameter, "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString(parameter)));

        verify(assetService, never()).getAssetActivity(any(), any());
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_aFilterValueTheServiceRejectsIsABadRequest() throws Exception {
        given(assetService.getAssetActivity(any(ActivityQuery.class), any(Pageable.class)))
                .willThrow(new IllegalArgumentException("Unknown provider 'MARS'"));

        mockMvc.perform(get(ACTIVITY).param("provider", "MARS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown provider 'MARS'"));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void activity_isNotShadowedByTheAssetIdPath() throws Exception {
        // GET /assets/{assetId} takes a UUID; the literal path must win and never reach it.
        stubActivity(knownRow());

        mockMvc.perform(get(ACTIVITY)).andExpect(status().isOk());

        verify(assetService, never()).getAssetById(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER", "DATA_SCIENTIST",
            "DATA_ANALYST", "AUDITOR", "VIEWER", "SERVICE_ACCOUNT"})
    void activity_isOpenToEveryNamedRoleAndTheMachinePrincipal(String role) throws Exception {
        stubActivity(knownRow());

        mockMvc.perform(get(ACTIVITY).with(user("caller").authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk());
    }

    @Test
    void activity_aTokenWithNoDlapRoleIsForbidden() throws Exception {
        mockMvc.perform(get(ACTIVITY).with(user("caller").authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verify(assetService, never()).getAssetActivity(any(), any());
    }

    @Test
    void activity_whenAnonymous_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(ACTIVITY)).andExpect(status().isUnauthorized());

        verify(assetService, never()).getAssetActivity(any(), any());
    }

    // ---- GET /assets/{id}: the time, size and retention fields (CAT-02b) ---------------------

    @Test
    @WithMockUser(roles = "DATA_ENGINEER")
    void getAssetById_exposesTheTimeSizeAndRetentionFields() throws Exception {
        asset.setCloudCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        asset.setLastAccessedAt(Instant.parse("2026-10-01T10:15:30Z"));
        asset.setLastModifiedAt(Instant.parse("2026-10-02T00:00:00Z"));
        asset.setSizeBytes(0L);
        asset.setRetentionStatus(RetentionStatus.ON_HOLD);
        asset.setRetentionDays(30);
        asset.setRetentionBasis(RetentionBasis.LAST_ACCESSED);
        asset.setLegalHold(true);
        given(assetService.getAssetById(assetId)).willReturn(asset);

        mockMvc.perform(get("/api/v1/catalog/assets/{assetId}", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cloudCreatedAt").value("2026-08-01T00:00:00Z"))
                .andExpect(jsonPath("$.lastAccessedAt").value("2026-10-01T10:15:30Z"))
                .andExpect(jsonPath("$.lastModifiedAt").value("2026-10-02T00:00:00Z"))
                .andExpect(jsonPath("$.sizeBytes").value(0))
                .andExpect(jsonPath("$.retentionStatus").value("ON_HOLD"))
                .andExpect(jsonPath("$.retentionDays").value(30))
                .andExpect(jsonPath("$.retentionBasis").value("LAST_ACCESSED"))
                .andExpect(jsonPath("$.legalHold").value(true));
    }

    @Test
    @WithMockUser(roles = "DATA_ENGINEER")
    void getAssetById_exposesReadCoverageAndItsReasons() throws Exception {
        asset.setReadCoverage("FULL");
        asset.setReadCoverageReasons(List.of("CloudTrail data events cover the bucket"));
        asset.setReadCoverageObservedAt(Instant.parse("2026-10-19T06:41:00Z"));
        asset.setReadCoverageSince(Instant.parse("2026-09-20T06:41:00Z"));
        given(assetService.getAssetById(assetId)).willReturn(asset);

        mockMvc.perform(get("/api/v1/catalog/assets/{assetId}", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readCoverage").value("FULL"))
                .andExpect(jsonPath("$.readCoverageObservedAt").value("2026-10-19T06:41:00Z"))
                .andExpect(jsonPath("$.readCoverageSince").value("2026-09-20T06:41:00Z"))
                .andExpect(jsonPath("$.readCoverageReasons[0]").value("CloudTrail data events cover the bucket"));
    }

    @Test
    @WithMockUser(roles = "DATA_ENGINEER")
    void getAssetById_omitsWhatIsUnknown() throws Exception {
        given(assetService.getAssetById(assetId)).willReturn(asset);

        mockMvc.perform(get("/api/v1/catalog/assets/{assetId}", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastAccessedAt").doesNotExist())
                .andExpect(jsonPath("$.cloudCreatedAt").doesNotExist())
                .andExpect(jsonPath("$.sizeBytes").doesNotExist())
                .andExpect(jsonPath("$.readCoverage").doesNotExist())
                .andExpect(jsonPath("$.readCoverageReasons").doesNotExist())
                .andExpect(jsonPath("$.readCoverageObservedAt").doesNotExist())
                .andExpect(jsonPath("$.readCoverageSince").doesNotExist())
                .andExpect(jsonPath("$.retentionExpiresAt").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ACCOUNT")
    void resolveAssetIdByUri_handsTheCallersTextToTheServiceUnchanged() throws Exception {
        // Normalization is the service's job (AssetIdNormalizer); the response echoes the input.
        String alias = "projects/_/buckets/dlap-demo";
        given(assetService.resolveAssetIdByUri(alias)).willReturn(Optional.of(assetId));

        mockMvc.perform(get("/api/v1/catalog/assets/resolve").param("assetUri", alias))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.toString()))
                .andExpect(jsonPath("$.assetUri").value(alias));
    }
}
