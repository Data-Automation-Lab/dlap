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

package com.dlap.discovery.client.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

// M8 gap fix: previous declaration relied on Eureka name resolution
// (eureka.client.enabled=false in our docker stack), so the client would
// throw UnknownHostException on first call. Use explicit url= with a
// dev fallback identical to the other Feign clients in the repo
// (da-autolabel/AssetCatalogClient, da-autodelete/CatalogAssetFeignClient,
// etc.). Operators override via DA_CATALOG_SERVICE_URL or
// dlap.catalog-service.url at deploy time.
@FeignClient(
        name = "da-catalog",
        url = "${dlap.catalog-service.url:http://da-catalog:8082}",
        path = "/api/v1/catalog")
public interface CatalogServiceClient {
    /**
     * Retrieves the total number of assets in the catalog.
     * This endpoint needs to be implemented in da-catalog.
     * Example: GET /api/v1/catalog/assets/count
     *
     * @return Total number of assets.
     */
    @GetMapping("/assets/count") // This is a placeholder path
    Long getTotalAssetsInCatalog();
    // Add other methods to call da-catalog APIs as needed
}
