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

package com.dlap.autoarchival.client;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.dlap.autoarchival.dto.CatalogAssetDTO;

/**
 * Feign client for looking up canonical {@code Asset} records in da-catalog.
 *
 * <p>Used by {@link com.dlap.autoarchival.service.AssetLocationResolver} to translate a
 * policy-supplied asset id into the {@code (cloudProvider, assetUri)} pair the archival
 * SDKs need, instead of inventing a location.
 *
 * <p>Authorization is attached by the shared service-account Feign interceptor in
 * da-protos ({@code ServiceAuthAutoConfiguration}, {@code dlap.service-auth.*}). The
 * target endpoint {@code GET /api/v1/catalog/assets/{assetId}} allows
 * {@code ROLE_SERVICE_ACCOUNT} (ADR-0034). Mirrors da-autodelete's
 * {@code CatalogAssetFeignClient}.
 */
@FeignClient(
        name = "da-catalog",
        url = "${dlap.catalog-service.url:http://da-catalog:8082}",
        path = "/api/v1/catalog")
public interface CatalogAssetFeignClient {

    /**
     * GET {@code /api/v1/catalog/assets/{assetId}}.
     *
     * <p>Feign raises a {@code FeignException} on 404, network failure, or any other
     * non-2xx response; {@link com.dlap.autoarchival.service.AssetLocationResolver}
     * converts that into "unresolvable" and fails the task closed.
     *
     * @param assetId catalog asset UUID
     * @return the populated asset on HTTP 200
     */
    @GetMapping("/assets/{assetId}")
    CatalogAssetDTO getAssetById(@PathVariable("assetId") UUID assetId);
}
