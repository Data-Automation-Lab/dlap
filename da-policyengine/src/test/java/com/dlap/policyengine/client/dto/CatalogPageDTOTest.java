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

package com.dlap.policyengine.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * POL-01: the activity page the sweep reads deserialises from the JSON da-catalog writes
 * ({@code PageAssetActivityRowDTO} in {@code docs/api/openapi/catalog.json}: the direct
 * {@code PageImpl} shape), and from Spring Data's {@code VIA_DTO} shape, with the paging the
 * sweep follows.
 */
class CatalogPageDTOTest {

    /** The Jackson 2 mapper Spring configures (java.time module found on the class path). */
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final TypeReference<CatalogPageDTO<CatalogActivityRowDTO>> PAGE = new TypeReference<>() { };

    private static final String ROW = "{\"assetId\":\"1f2d3a4b-0000-4000-8000-000000000001\","
            + "\"assetUri\":\"gs://prod-customer-exports\",\"assetName\":\"prod-customer-exports\","
            + "\"cloudProvider\":\"GCP\",\"serviceType\":\"GCS_BUCKET\",\"assetType\":\"GCS_BUCKET\","
            + "\"region\":\"us-central1\",\"status\":\"ACTIVE\",\"ownerInfo\":null,"
            + "\"discoveredAt\":\"2026-01-02T00:00:00Z\",\"discoverySource\":\"CRAWLER\","
            + "\"cloudCreatedAt\":\"2024-05-01T00:00:00Z\",\"cloudCreatedSource\":\"CRAWLER\",\"ageDays\":902,"
            + "\"sizeBytes\":2048,\"sizeObservedAt\":\"2026-10-19T00:00:00Z\","
            + "\"lastAccessedAt\":\"2026-01-01T00:00:00Z\",\"lastAccessSource\":\"GCP_CLOUD_AUDIT\",\"idleDays\":292,"
            + "\"readCoverage\":\"PARTIAL\",\"lastModifiedAt\":null,\"lastPermissionChangeAt\":null,"
            + "\"recreatedAt\":null,\"retentionStatus\":\"UNKNOWN\",\"retentionExpiresAt\":null,"
            + "\"retentionEvaluatedAt\":null,\"retentionDays\":null,\"retentionBasis\":null,"
            + "\"retentionSource\":null,\"retentionPolicyId\":null,\"legalHold\":false,"
            + "\"labels\":[\"PII\"],\"asOf\":\"2026-10-20T00:00:00Z\"}";

    @Test
    void theDirectPageShapeDeserialisesWithEveryFieldTheSweepReads() throws Exception {
        String json = "{\"content\":[" + ROW + "],\"pageable\":{\"pageNumber\":0,\"pageSize\":200},"
                + "\"last\":false,\"totalPages\":3,\"totalElements\":401,\"size\":200,\"number\":0,"
                + "\"sort\":{\"sorted\":true},\"first\":true,\"numberOfElements\":1,\"empty\":false}";

        CatalogPageDTO<CatalogActivityRowDTO> page = JSON.readValue(json, PAGE);

        assertThat(page.hasNext()).isTrue();
        CatalogActivityRowDTO row = page.content().get(0);
        assertThat(row.assetUri()).isEqualTo("gs://prod-customer-exports");
        assertThat(row.lastAccessedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(row.cloudCreatedAt()).isEqualTo(Instant.parse("2024-05-01T00:00:00Z"));
        assertThat(row.readCoverage()).isEqualTo("PARTIAL");
        assertThat(row.sizeBytes()).isEqualTo(2048L);
        assertThat(row.legalHold()).isFalse();
        assertThat(row.labels()).containsExactly("PII");
        assertThat(row.lastModifiedAt()).isNull();
    }

    @Test
    void theLastDirectPageEndsTheSweep() throws Exception {
        String json = "{\"content\":[" + ROW + "],\"last\":true,\"totalPages\":3,\"number\":2}";
        assertThat(JSON.readValue(json, PAGE).hasNext()).isFalse();
    }

    @Test
    void theViaDtoShapePagesToo() throws Exception {
        String more = "{\"content\":[" + ROW + "],\"page\":{\"size\":200,\"number\":0,\"totalElements\":401,"
                + "\"totalPages\":3}}";
        String end = "{\"content\":[" + ROW + "],\"page\":{\"size\":200,\"number\":2,\"totalElements\":401,"
                + "\"totalPages\":3}}";
        assertThat(JSON.readValue(more, PAGE).hasNext()).isTrue();
        assertThat(JSON.readValue(end, PAGE).hasNext()).isFalse();
    }

    @Test
    void anEmptyPageOrAShapeWithoutPagingEndsTheSweep() throws Exception {
        assertThat(JSON.readValue("{\"content\":[],\"last\":false}", PAGE).hasNext()).isFalse();
        assertThat(new CatalogPageDTO<CatalogActivityRowDTO>(List.of(), null, null, null, null).hasNext()).isFalse();
        CatalogPageDTO<CatalogActivityRowDTO> noPaging = JSON.readValue("{\"content\":[" + ROW + "]}", PAGE);
        assertThat(noPaging.hasNext()).isFalse();
        assertThat(noPaging.pagingKnown()).as("the sweep aborts on such a page").isFalse();
    }

    // ----- POL-05 (Q-445): the keyset cursor nextAfter ----------------------------------------

    @Test
    void aNextAfterValueIsTheCursorOfTheNextPage() throws Exception {
        CatalogPageDTO<CatalogActivityRowDTO> page = JSON.readValue(
                "{\"content\":[" + ROW + "],\"nextAfter\":\"gs://prod-customer-exports\"}", PAGE);

        assertThat(page.keysetAnswered()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("gs://prod-customer-exports");
    }

    @Test
    void aNullNextAfterIsTheLastKeysetPageNotAnOffsetOnlyCatalog() throws Exception {
        CatalogPageDTO<CatalogActivityRowDTO> page = JSON.readValue(
                "{\"content\":[" + ROW + "],\"nextAfter\":null}", PAGE);

        assertThat(page.keysetAnswered()).as("the field was sent, as null").isTrue();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void anAnswerWithoutNextAfterIsFromAnOffsetOnlyCatalog() throws Exception {
        CatalogPageDTO<CatalogActivityRowDTO> page = JSON.readValue(
                "{\"content\":[" + ROW + "],\"last\":false,\"totalPages\":3,\"number\":0}", PAGE);

        assertThat(page.keysetAnswered()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void aBlankNextAfterIsNoCursor() {
        CatalogPageDTO<CatalogActivityRowDTO> page = new CatalogPageDTO<>(List.of(), null, null, null, null, "  ");

        assertThat(page.keysetAnswered()).isTrue();
        assertThat(page.nextCursor()).isNull();
    }
}
