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

package com.dlap.autocompliance.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dlap.autocompliance.client.dto.AssetIdResolutionResponse;
import com.dlap.autocompliance.client.dto.CatalogAssetPageDTO;

/**
 * Feign client for read-only asset metadata from da-catalog, used by the
 * catalog-backed compliance scan. Authorization is attached by the shared
 * service-account Feign interceptor (da-protos {@code ServiceAuthAutoConfiguration},
 * {@code dlap.service-auth.*}).
 *
 * <p>The scan pages through {@code GET /api/v1/catalog/assets} (a Spring Data
 * {@code Page<AssetOutputDTO>}) so every catalog asset is evaluated against
 * the enabled controls.
 */
@FeignClient(
        name = "da-catalog",
        url = "${dlap.services.catalog.url:http://localhost:8082}",
        path = "/api/v1/catalog")
public interface CatalogAssetClient {

    /**
     * Fetch one page of catalog assets.
     *
     * @param page zero-based page index
     * @param size page size
     * @return the page of assets (empty content when the catalog is empty)
     */
    @GetMapping("/assets")
    CatalogAssetPageDTO getAssets(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "200") int size);

    /**
     * Resolve a canonical asset URI to its catalog id (the join key). Throws
     * {@code FeignException.NotFound} when the URI is unknown to the catalog.
     */
    @GetMapping("/assets/resolve")
    AssetIdResolutionResponse resolveAssetId(@RequestParam("assetUri") String assetUri);
}
