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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Feign client for da-catalog service
 * Enables cross-service communication for dashboard aggregation
 * 
 * @author DLAP Development Team
 * @since 2025-01-02
 */
@FeignClient(name = "da-catalog", url = "${dlap.services.da-catalog.url:http://localhost:8082}")
public interface CatalogServiceClient {

    /**
     * Get total asset count for governance calculations
     */
    @GetMapping("/api/v1/catalog/assets/count")
    Long getTotalAssetCount();

    /**
     * Get classified assets count for data classification metrics
     */
    @GetMapping("/api/v1/catalog/assets/count/classified")
    Long getClassifiedAssetsCount();

    /**
     * Get assets with metadata count for metadata completeness metrics
     */
    @GetMapping("/api/v1/catalog/assets/count/with-metadata")
    Long getAssetsWithMetadataCount();

    /**
     * Get assets with business context count for governance scoring
     */
    @GetMapping("/api/v1/catalog/assets/count/with-business-context")
    Long getAssetsWithBusinessContextCount();

    /**
     * Get assets with lineage count for lineage coverage metrics
     */
    @GetMapping("/api/v1/catalog/assets/count/with-lineage")
    Long getAssetsWithLineageCount();

    /**
     * Get total lineage connections count
     */
    @GetMapping("/api/v1/catalog/assets/lineage/connections/count")
    Long getTotalLineageConnectionsCount();

    /**
     * Get governance issues from catalog service
     */
    @GetMapping("/api/v1/catalog/assets/governance-issues")
    Map<String, Object> getGovernanceIssues(@RequestParam(required = false) String severity);

    /**
     * Get asset classification breakdown for scoring
     */
    @GetMapping("/api/v1/catalog/assets/classification-breakdown")
    Map<String, Long> getClassificationBreakdown();

    @GetMapping("/api/v1/catalog/assets/count/status/{status}")
    Long countAssetsByStatus(@PathVariable("status") String status);

    /**
     * One page of the catalog's asset activity report (RPT-01), {@code GET /api/v1/catalog/assets/activity}.
     * Every filter is optional; a {@code null} value is left out of the request. The catalog parses and
     * validates the values, and clamps {@code size} to its own maximum.
     *
     * @param page            zero-based page number
     * @param size            requested page size
     * @param sort            sort as {@code property,direction}; the catalog adds {@code assetUri} as the tie-breaker
     * @param provider        cloud providers
     * @param kind            asset kinds (service types)
     * @param status          lifecycle statuses
     * @param lastReadBefore  ISO-8601 instant: only assets last read strictly before it
     * @param lastReadAfter   ISO-8601 instant: only assets last read strictly after it
     * @param lastReadKnown   {@code true} or {@code false}
     * @param retentionStatus stored retention statuses
     * @param legalHold       {@code true} or {@code false}
     * @param readCoverage    read coverage values, or {@code UNKNOWN}
     * @param asOf            ISO-8601 instant the day counts are computed against
     * @param after           keyset cursor (Q-445, D45): the {@code assetUri} of the last row already seen;
     *                        when set, {@code page} is 0 and {@code sort} is {@code assetUri,asc}. Left out
     *                        of the request when {@code null}
     * @return the page, with the catalog's row shape
     */
    @GetMapping("/api/v1/catalog/assets/activity")
    ActivityPage getAssetActivity(
            @RequestParam("page") int page,
            @RequestParam("size") int size,
            @RequestParam("sort") String sort,
            @RequestParam(name = "provider", required = false) List<String> provider,
            @RequestParam(name = "kind", required = false) List<String> kind,
            @RequestParam(name = "status", required = false) List<String> status,
            @RequestParam(name = "lastReadBefore", required = false) String lastReadBefore,
            @RequestParam(name = "lastReadAfter", required = false) String lastReadAfter,
            @RequestParam(name = "lastReadKnown", required = false) String lastReadKnown,
            @RequestParam(name = "retentionStatus", required = false) List<String> retentionStatus,
            @RequestParam(name = "legalHold", required = false) String legalHold,
            @RequestParam(name = "readCoverage", required = false) List<String> readCoverage,
            @RequestParam(name = "asOf", required = false) String asOf,
            @RequestParam(name = "after", required = false) String after);

    /**
     * The part of the catalog's Spring page that the report reads. Unknown properties (the catalog
     * sends {@code pageable}, {@code sort} and more) are ignored.
     *
     * @param content       the rows of this page
     * @param totalElements rows over all pages
     * @param totalPages    number of pages
     * @param number        zero-based number of this page
     * @param size          the page size the catalog applied, which can be below the requested size
     * @param nextAfter     keyset cursor of the next page: the {@code assetUri} of this page's last row
     *                      when more rows follow in {@code assetUri} order; null on the last page. A
     *                      catalog that predates keyset paging does not send it
     * @param last          whether the catalog calls this the last page; null when it does not say
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ActivityPage(List<ActivityRow> content, long totalElements, int totalPages, int number, int size,
                        String nextAfter, Boolean last) {
        /**
         * Makes the content never null.
         *
         * @param content       the rows; null means none
         * @param totalElements rows over all pages
         * @param totalPages    number of pages
         * @param number        zero-based page number
         * @param size          applied page size
         * @param nextAfter     keyset cursor of the next page, or null
         * @param last          the catalog's last-page flag, or null
         */
        public ActivityPage {
            content = content == null ? List.of() : List.copyOf(content);
        }

        /**
         * A page that carries no keyset cursor and no last-page flag, as an offset caller reads it.
         *
         * @param content       the rows; null means none
         * @param totalElements rows over all pages
         * @param totalPages    number of pages
         * @param number        zero-based page number
         * @param size          applied page size
         */
        public ActivityPage(List<ActivityRow> content, long totalElements, int totalPages, int number, int size) {
            this(content, totalElements, totalPages, number, size, null, null);
        }
    }

    /**
     * One row of the catalog's activity report, as {@code AssetActivityRowDTO} writes it. Names
     * are the catalog's. Text vocabularies ({@code retentionStatus}, {@code retentionBasis},
     * {@code readCoverage}) stay strings so a value the catalog adds later does not break the
     * report. A {@code null} is unknown (ADR-0046), never zero, stale or fresh.
     *
     * @param assetId               DLAP identifier
     * @param assetUri              canonical URI
     * @param assetName             display name
     * @param cloudProvider         provider
     * @param serviceType           asset kind
     * @param region                normalized region
     * @param status                lifecycle status
     * @param ownerInfo             resolved owner
     * @param cloudCreatedAt        provider creation time
     * @param cloudCreatedSource    evidence channel of {@code cloudCreatedAt}
     * @param ageDays               whole days from creation to {@code asOf}
     * @param sizeBytes             latest observed size
     * @param lastAccessedAt        latest successful read
     * @param lastAccessSource      evidence channel of {@code lastAccessedAt}
     * @param idleDays              whole days from the last read to {@code asOf}
     * @param readCoverage          read-logging coverage as the catalog states it, or null
     * @param readCoverageObservedAt when the catalog's coverage was last read from the provider
     *                              (Q-475), or null
     * @param readCoverageSince     first observation of the current unbroken run of FULL coverage
     *                              (Q-434, Q-444), or null
     * @param lastModifiedAt        latest successful write
     * @param retentionStatus       stored retention status
     * @param retentionExpiresAt    expiry the last evaluation found
     * @param retentionEvaluatedAt  instant of the last evaluation
     * @param retentionDays         period of the deciding rule
     * @param retentionBasis        basis of the deciding rule
     * @param retentionSource       source of the deciding rule
     * @param legalHold             DLAP legal hold
     * @param labels                label names
     * @param asOf                  the instant the catalog computed the day counts against
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ActivityRow(
            UUID assetId,
            String assetUri,
            String assetName,
            String cloudProvider,
            String serviceType,
            String region,
            String status,
            String ownerInfo,
            Instant cloudCreatedAt,
            String cloudCreatedSource,
            Long ageDays,
            Long sizeBytes,
            Instant lastAccessedAt,
            String lastAccessSource,
            Long idleDays,
            String readCoverage,
            Instant readCoverageObservedAt,
            Instant readCoverageSince,
            Instant lastModifiedAt,
            String retentionStatus,
            Instant retentionExpiresAt,
            Instant retentionEvaluatedAt,
            Integer retentionDays,
            String retentionBasis,
            String retentionSource,
            boolean legalHold,
            List<String> labels,
            Instant asOf) {

        /**
         * Makes the label list never null.
         *
         * @param assetId              DLAP identifier
         * @param assetUri             canonical URI
         * @param assetName            display name
         * @param cloudProvider        provider
         * @param serviceType          asset kind
         * @param region               region
         * @param status               lifecycle status
         * @param ownerInfo            owner
         * @param cloudCreatedAt       creation time
         * @param cloudCreatedSource   creation evidence channel
         * @param ageDays              age in days
         * @param sizeBytes            size
         * @param lastAccessedAt       last read
         * @param lastAccessSource     read evidence channel
         * @param idleDays             idle days
         * @param readCoverage         read coverage
         * @param readCoverageObservedAt coverage observation time
         * @param readCoverageSince    start of the current FULL run
         * @param lastModifiedAt       last write
         * @param retentionStatus      retention status
         * @param retentionExpiresAt   expiry
         * @param retentionEvaluatedAt evaluation instant
         * @param retentionDays        rule period
         * @param retentionBasis       rule basis
         * @param retentionSource      rule source
         * @param legalHold            legal hold
         * @param labels               label names; null means none
         * @param asOf                 as-of instant
         */
        public ActivityRow {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }
} 