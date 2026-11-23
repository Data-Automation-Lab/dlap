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

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.dlap.autolabel.client.dto.AssetLabelsPostFeignRequest;
import com.dlap.autolabel.client.dto.CatalogAssetPageDTO;
import com.dlap.autolabel.client.dto.CatalogAssetSummaryDTO;

/**
 * Feign client for da-catalog REST API used by auto-labeling.
 */
@FeignClient(name = "da-catalog", url = "${da-catalog.service.url:http://localhost:8082}", path = "/api/v1/catalog")
public interface AssetCatalogClient {

    @GetMapping("/assets")
    CatalogAssetPageDTO getAssetsByConnection(
            @RequestParam("connectionId") UUID connectionId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "500") int size);

    /**
     * Enumerate all catalog assets (no connection filter). Backs the
     * {@code ALL}/{@code ALL_UNLABELED} labeling-request scope, which must
     * process every asset rather than one cloud connection's worth.
     */
    @GetMapping("/assets")
    CatalogAssetPageDTO getAllAssets(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "500") int size);

    @GetMapping("/assets/{assetId}")
    CatalogAssetSummaryDTO getAssetById(@PathVariable("assetId") UUID assetId);

    @PostMapping("/assets/{assetId}/labels")
    void addLabelsToAsset(@PathVariable("assetId") UUID assetId, @RequestBody AssetLabelsPostFeignRequest labelsToAssign);

    @DeleteMapping("/assets/{assetId}/labels/{labelName}")
    void removeLabelFromAsset(@PathVariable("assetId") UUID assetId, @PathVariable("labelName") String labelName);
}
