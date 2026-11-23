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
package com.dlap.autodelete.client;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dlap.autodelete.dto.CatalogAssetDTO;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;

/**
 * Circuit-breaker wrapper around {@link CatalogAssetFeignClient}.
 *
 * <p>Mirrors the convention used in {@code da-policyengine} and
 * {@code da-reporting}: Feign exceptions (404, timeout, connection refused,
 * catalog circuit open) are converted to {@link Optional#empty()} so the
 * calling resolver can fall back to URI parsing rather than failing the
 * deletion task outright.
 */
@Service
@RequiredArgsConstructor
public class ResilientCatalogAssetClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientCatalogAssetClient.class);

    private final CatalogAssetFeignClient delegate;

    /**
     * Looks up an asset by catalog UUID.
     *
     * @param assetId catalog UUID (never null)
     * @return populated DTO on success, {@link Optional#empty()} if the
     *         catalog returned 404, is unreachable, or the circuit is open.
     */
    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackAssetById")
    public Optional<CatalogAssetDTO> getAssetById(UUID assetId) {
        try {
            return Optional.ofNullable(delegate.getAssetById(assetId));
        } catch (RuntimeException ex) {
            // Feign wraps HTTP non-2xx as FeignException (RuntimeException).
            // Log at DEBUG and return empty so the resolver can fall back.
            log.debug("Catalog lookup failed for asset {}: {} - {}", assetId,
                    ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unused")
    private Optional<CatalogAssetDTO> fallbackAssetById(UUID assetId, Throwable ex) {
        log.warn("Circuit breaker fallback for catalog asset lookup {}: {} - {}",
                assetId,
                ex != null ? ex.getClass().getSimpleName() : "unknown",
                ex != null ? ex.getMessage() : "no cause");
        return Optional.empty();
    }
}
