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

package com.dlap.policyengine.client;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.client.dto.CatalogAssetIdResolutionDTO;
import com.dlap.policyengine.client.dto.CatalogPageDTO;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;

/**
 * Circuit-broken access to da-catalog. Every method answers {@link Optional#empty()} when the
 * catalog cannot answer; none throws.
 *
 * <p>A 404 is an answer, not a failure: it is turned into an empty result inside the call, so a
 * run of unknown ids or URIs never opens the circuit for the callers that need the catalog to be
 * up (the PDP and the scheduled sweep).
 */
@Service
@RequiredArgsConstructor
public class ResilientCatalogAssetClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientCatalogAssetClient.class);

    private final ICatalogAssetClient delegate;

    /**
     * Fetches one asset.
     *
     * @param assetId the catalog id
     * @return the asset, or empty when it does not exist or the catalog is unavailable
     */
    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackAssetById")
    public Optional<CatalogAssetDTO> getAssetById(UUID assetId) {
        try {
            return Optional.ofNullable(delegate.getAssetById(assetId));
        } catch (FeignException.NotFound e) {
            log.debug("Catalog has no asset {}", assetId);
            return Optional.empty();
        }
    }

    /**
     * Resolves an asset URI to its catalog id.
     *
     * @param assetUri the URI in any form the catalog normalises
     * @return the id, or empty when no asset has that URI or the catalog is unavailable
     */
    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackResolve")
    public Optional<UUID> resolveAssetIdByUri(String assetUri) {
        if (assetUri == null || assetUri.isBlank()) {
            return Optional.empty();
        }
        try {
            CatalogAssetIdResolutionDTO resolved = delegate.resolveAssetIdByUri(assetUri);
            return Optional.ofNullable(resolved).map(CatalogAssetIdResolutionDTO::assetId);
        } catch (FeignException.NotFound e) {
            log.debug("Catalog resolves no asset for the URI");
            return Optional.empty();
        }
    }

    /**
     * Fetches one page of the activity report.
     *
     * @param page     zero-based page number
     * @param size     page size
     * @param sort     sort expression
     * @param statuses lifecycle statuses to include
     * @param asOf     the instant the rows are computed against
     * @return the page, or empty when the catalog is unavailable (an empty page is a present result)
     */
    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackActivity")
    public Optional<CatalogPageDTO<CatalogActivityRowDTO>> getAssetActivity(int page, int size, String sort,
            List<String> statuses, Instant asOf) {
        return Optional.ofNullable(delegate.getAssetActivity(page, size, sort, statuses, asOf.toString()));
    }

    /**
     * Fetches the page of the activity report that follows a keyset cursor (CAT-04, POL-05, Q-445).
     *
     * @param after    the cursor, the {@code nextAfter} of the previous page
     * @param size     page size
     * @param statuses lifecycle statuses to include
     * @param asOf     the instant the rows are computed against
     * @return the page, or empty when the catalog is unavailable (an empty page is a present result)
     */
    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackActivityAfter")
    public Optional<CatalogPageDTO<CatalogActivityRowDTO>> getAssetActivityAfter(String after, int size,
            List<String> statuses, Instant asOf) {
        return Optional.ofNullable(delegate.getAssetActivityAfter(after, size, statuses, asOf.toString()));
    }

    @SuppressWarnings("unused")
    private Optional<CatalogAssetDTO> fallbackAssetById(UUID assetId, Throwable ex) {
        log.warn("Circuit breaker fallback for catalog asset lookup {}: {} - {}",
                assetId, name(ex), message(ex));
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private Optional<UUID> fallbackResolve(String assetUri, Throwable ex) {
        log.warn("Circuit breaker fallback for catalog URI resolution: {} - {}", name(ex), message(ex));
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private Optional<CatalogPageDTO<CatalogActivityRowDTO>> fallbackActivity(int page, int size, String sort,
            List<String> statuses, Instant asOf, Throwable ex) {
        log.warn("Circuit breaker fallback for catalog activity page {}: {} - {}", page, name(ex), message(ex));
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private Optional<CatalogPageDTO<CatalogActivityRowDTO>> fallbackActivityAfter(String after, int size,
            List<String> statuses, Instant asOf, Throwable ex) {
        log.warn("Circuit breaker fallback for catalog activity page after a keyset cursor: {} - {}", name(ex),
                message(ex));
        return Optional.empty();
    }

    private static String name(Throwable ex) {
        return ex != null ? ex.getClass().getSimpleName() : "unknown";
    }

    private static String message(Throwable ex) {
        return ex != null ? ex.getMessage() : "no cause";
    }
}
