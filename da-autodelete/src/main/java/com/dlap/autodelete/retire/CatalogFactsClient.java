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

package com.dlap.autodelete.retire;

import com.dlap.autodelete.client.CatalogAssetFeignClient;
import com.dlap.autodelete.dto.CatalogAssetDTO;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads the facts the delete rules need (legal hold, read coverage, canonical URI) from
 * {@code GET /api/v1/catalog/assets/{id}}.
 *
 * <p>Unlike {@code ResilientCatalogAssetClient} there is no fallback: an unreachable catalog is a
 * {@link RetireRemoteException}, never an asset without a hold.
 */
@Component
public class CatalogFactsClient {

    private final CatalogAssetFeignClient feign;

    /**
     * Creates the client.
     *
     * @param feign the catalog Feign client
     */
    public CatalogFactsClient(CatalogAssetFeignClient feign) {
        this.feign = feign;
    }

    /**
     * Reads an asset.
     *
     * @param assetId the catalog id
     * @return the facts, or empty when the catalog does not know the asset
     * @throws RetireRemoteException when the catalog gave no usable answer
     */
    public Optional<AssetFacts> read(UUID assetId) {
        CatalogAssetDTO dto;
        try {
            dto = feign.getAssetById(assetId);
        } catch (FeignException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw ApprovalClient.failure("read catalog asset " + assetId, e);
        } catch (RuntimeException e) {
            throw new RetireRemoteException("read catalog asset " + assetId + " failed: "
                    + e.getClass().getSimpleName(), true, e);
        }
        if (dto == null) {
            return Optional.empty();
        }
        return Optional.of(new AssetFacts(dto.getAssetId() == null ? assetId : dto.getAssetId(), dto.getAssetUri(),
                dto.getCloudProvider(), dto.getServiceType(), dto.getLegalHold(), dto.getReadCoverage(),
                dto.getReadCoverageReasons(), dto.getSizeBytes()));
    }
}
