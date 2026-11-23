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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ResilientCatalogClient {

    private final CatalogServiceClient delegate;

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackLong")
    public Long getTotalAssetCount() {
        return delegate.getTotalAssetCount();
    }

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackLong")
    public Long getClassifiedAssetsCount() {
        return delegate.getClassifiedAssetsCount();
    }

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackLong")
    public Long getAssetsWithMetadataCount() {
        return delegate.getAssetsWithMetadataCount();
    }

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackLong")
    public Long getAssetsWithBusinessContextCount() {
        return delegate.getAssetsWithBusinessContextCount();
    }

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackLong")
    public Long getAssetsWithLineageCount() {
        return delegate.getAssetsWithLineageCount();
    }

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackLong")
    public Long getTotalLineageConnectionsCount() {
        return delegate.getTotalLineageConnectionsCount();
    }

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackGovernanceIssues")
    public Map<String, Object> getGovernanceIssues(String severity) {
        return delegate.getGovernanceIssues(severity);
    }

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackLong")
    public Long countAssetsByStatus(String status) {
        return delegate.countAssetsByStatus(status);
    }

    /**
     * One page of the catalog's asset activity report. There is deliberately no fallback: an
     * empty page for an unreachable catalog would read as "no assets", so the failure (or an open
     * circuit) reaches the caller, which reports the catalog as unavailable.
     *
     * @param request the page, sort and filters
     * @return the catalog's page
     */
    @CircuitBreaker(name = "da-catalog")
    public CatalogServiceClient.ActivityPage getAssetActivity(ActivityRequest request) {
        return delegate.getAssetActivity(request.page(), request.size(), request.sort(), request.provider(),
                request.kind(), request.status(), request.lastReadBefore(), request.lastReadAfter(),
                request.lastReadKnown(), request.retentionStatus(), request.legalHold(), request.readCoverage(),
                request.asOf(), request.after());
    }

    /**
     * One request for a page of the activity report. List filters may be null or empty (no
     * filter); text filters are already in the form the catalog parses.
     *
     * @param page            zero-based page number
     * @param size            requested page size
     * @param sort            {@code property,direction}
     * @param provider        providers
     * @param kind            kinds
     * @param status          lifecycle statuses
     * @param lastReadBefore  ISO-8601 instant
     * @param lastReadAfter   ISO-8601 instant
     * @param lastReadKnown   {@code true} or {@code false}
     * @param retentionStatus retention statuses
     * @param legalHold       {@code true} or {@code false}
     * @param readCoverage    read coverage values
     * @param asOf            ISO-8601 instant
     * @param after           keyset cursor, the {@code assetUri} of the last row already seen; null for
     *                        offset paging (Q-445)
     */
    public record ActivityRequest(int page, int size, String sort, List<String> provider, List<String> kind,
            List<String> status, String lastReadBefore, String lastReadAfter, String lastReadKnown,
            List<String> retentionStatus, String legalHold, List<String> readCoverage, String asOf, String after) {

        /**
         * A request for an offset page, with no keyset cursor.
         *
         * @param page            zero-based page number
         * @param size            requested page size
         * @param sort            {@code property,direction}
         * @param provider        providers
         * @param kind            kinds
         * @param status          lifecycle statuses
         * @param lastReadBefore  ISO-8601 instant
         * @param lastReadAfter   ISO-8601 instant
         * @param lastReadKnown   {@code true} or {@code false}
         * @param retentionStatus retention statuses
         * @param legalHold       {@code true} or {@code false}
         * @param readCoverage    read coverage values
         * @param asOf            ISO-8601 instant
         */
        public ActivityRequest(int page, int size, String sort, List<String> provider, List<String> kind,
                List<String> status, String lastReadBefore, String lastReadAfter, String lastReadKnown,
                List<String> retentionStatus, String legalHold, List<String> readCoverage, String asOf) {
            this(page, size, sort, provider, kind, status, lastReadBefore, lastReadAfter, lastReadKnown,
                    retentionStatus, legalHold, readCoverage, asOf, null);
        }
    }

    @SuppressWarnings("unused")
    private Long fallbackLong(String ignored, Throwable ex) {
        return 0L;
    }

    @SuppressWarnings("unused")
    private Long fallbackLong(Throwable ex) {
        return 0L;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> fallbackGovernanceIssues(String severity, Throwable ex) {
        return Collections.emptyMap();
    }
}

