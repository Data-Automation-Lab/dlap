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

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.dlap.autodelete.dto.CatalogAssetDTO;

/**
 * Feign client for looking up canonical {@code Asset} records in da-catalog.
 *
 * <p>Used by {@link com.dlap.autodelete.service.AssetLocatorResolver} to
 * translate a caller-supplied asset identifier (UUID string) into the
 * {@code (cloudProvider, locatorUri)} pair the deletion SDKs need.
 *
 * <p>The {@code path} matches the base path used by
 * {@code da-catalog}'s {@code AssetController} ({@code /api/v1/catalog}).
 * The convention mirrors the policyengine's {@code ICatalogAssetClient}.
 */
@FeignClient(
        name = "da-catalog",
        url = "${dlap.catalog-service.url:http://da-catalog:8082}",
        path = "/api/v1/catalog")
public interface CatalogAssetFeignClient {

    /**
     * GET {@code /api/v1/catalog/assets/{assetId}}.
     *
     * <p>Feign will raise a {@code FeignException} on 404, network failure,
     * or other non-2xx responses - the resilient wrapper converts those into
     * {@code Optional.empty()} so the resolver can fall through to URI parsing.
     *
     * @param assetId catalog asset UUID
     * @return populated {@link CatalogAssetDTO} on HTTP 200
     */
    @GetMapping("/assets/{assetId}")
    CatalogAssetDTO getAssetById(@PathVariable("assetId") UUID assetId);
}
