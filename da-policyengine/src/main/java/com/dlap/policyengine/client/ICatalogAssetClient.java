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

import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.client.dto.CatalogAssetIdResolutionDTO;
import com.dlap.policyengine.client.dto.CatalogPageDTO;

/**
 * Feign client for read-only asset metadata from da-catalog. Authorization is
 * attached by the shared service-account Feign interceptor (da-protos
 * ServiceAuthAutoConfiguration, dlap.service-auth.*). Every endpoint used here
 * admits {@code ROLE_SERVICE_ACCOUNT} (ADR-0034).
 */
@FeignClient(
        name = "da-catalog",
        url = "${dlap.services.catalog.url:http://localhost:8082}",
        path = "/api/v1/catalog")
public interface ICatalogAssetClient {

    /**
     * Fetches a single asset by catalog UUID.
     *
     * @param assetId catalog asset identifier
     * @return asset payload or Feign error if not found / unauthorized
     */
    @GetMapping("/assets/{assetId}")
    CatalogAssetDTO getAssetById(@PathVariable("assetId") UUID assetId);

    /**
     * Resolves an asset URI, in any form the catalog's {@code AssetIdNormalizer} accepts, to the
     * catalog id. The catalog answers 404 when no asset has that URI.
     *
     * @param assetUri the URI
     * @return the resolution
     */
    @GetMapping("/assets/resolve")
    CatalogAssetIdResolutionDTO resolveAssetIdByUri(@RequestParam("assetUri") String assetUri);

    /**
     * One page of the activity report (CAT-02b), computed as of one instant.
     *
     * @param page   zero-based page number
     * @param size   page size; the catalog caps it at 200
     * @param sort   sort, for example {@code assetUri,asc}; the catalog breaks ties by {@code assetUri}
     * @param status lifecycle statuses to include, any of
     * @param asOf   ISO-8601 instant the day counts are computed against
     * @return the page
     */
    @GetMapping("/assets/activity")
    CatalogPageDTO<CatalogActivityRowDTO> getAssetActivity(
            @RequestParam("page") int page,
            @RequestParam("size") int size,
            @RequestParam("sort") String sort,
            @RequestParam("status") List<String> status,
            @RequestParam("asOf") String asOf);

    /**
     * One page of the activity report by keyset (CAT-04, POL-05, Q-445): the rows whose {@code assetUri}
     * sorts after {@code after}, in {@code assetUri} order, so a row written while a sweep or an export is
     * running cannot shift a page. The answer carries {@code nextAfter}, the cursor of the next page, null
     * on the last page.
     *
     * @param after  the {@code assetUri} the previous page ended with ({@code nextAfter} of that page)
     * @param size   page size; the catalog caps it at 200
     * @param status lifecycle statuses to include, any of
     * @param asOf   ISO-8601 instant the day counts are computed against
     * @return the page
     */
    @GetMapping("/assets/activity")
    CatalogPageDTO<CatalogActivityRowDTO> getAssetActivityAfter(
            @RequestParam("after") String after,
            @RequestParam("size") int size,
            @RequestParam("status") List<String> status,
            @RequestParam("asOf") String asOf);
}
