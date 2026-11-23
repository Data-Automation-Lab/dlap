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

package com.dlap.autolabel.client.feign;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dlap.autolabel.client.dto.CatalogAssetPageDTO;
import com.dlap.autolabel.client.dto.CatalogAssetSummaryDTO;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ResilientAssetCatalogClient {

    private final AssetCatalogClient delegate;

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackAssetPage")
    public CatalogAssetPageDTO getAssetsByConnection(UUID connectionId, int page, int size) {
        return delegate.getAssetsByConnection(connectionId, page, size);
    }

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackAllAssets")
    public CatalogAssetPageDTO getAllAssets(int page, int size) {
        return delegate.getAllAssets(page, size);
    }

    @CircuitBreaker(name = "da-catalog", fallbackMethod = "fallbackAssetById")
    public CatalogAssetSummaryDTO getAssetById(UUID assetId) {
        return delegate.getAssetById(assetId);
    }

    @SuppressWarnings("unused")
    private CatalogAssetPageDTO fallbackAssetPage(UUID connectionId, int page, int size, Throwable ex) {
        return new CatalogAssetPageDTO();
    }

    @SuppressWarnings("unused")
    private CatalogAssetPageDTO fallbackAllAssets(int page, int size, Throwable ex) {
        return new CatalogAssetPageDTO();
    }

    @SuppressWarnings("unused")
    private CatalogAssetSummaryDTO fallbackAssetById(UUID assetId, Throwable ex) {
        return null;
    }
}

