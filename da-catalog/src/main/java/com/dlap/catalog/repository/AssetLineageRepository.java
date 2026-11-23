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

package com.dlap.catalog.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.dlap.catalog.model.AssetLineage;

@Repository
public interface AssetLineageRepository extends JpaRepository<AssetLineage, UUID>, JpaSpecificationExecutor<AssetLineage> {

    // Find all lineage records where the given asset is downstream (i.e., find its direct upstream assets)
    List<AssetLineage> findByDownstreamAsset_AssetId(UUID downstreamAssetId);

    // Find all lineage records where the given asset is upstream (i.e., find its direct downstream assets)
    List<AssetLineage> findByUpstreamAsset_AssetId(UUID upstreamAssetId);

    // For deleting lineage when an asset is deleted (cascade might be handled by DB constraints too)
    void deleteByUpstreamAsset_AssetIdOrDownstreamAsset_AssetId(UUID upstreamAssetId, UUID downstreamAssetId);

    @Query("""
            SELECT COUNT(DISTINCT a.assetId) FROM com.dlap.common.model.entity.Asset a
            WHERE EXISTS (SELECT 1 FROM AssetLineage l WHERE l.upstreamAsset.assetId = a.assetId
               OR l.downstreamAsset.assetId = a.assetId)
            """)
    long countDistinctAssetsInLineageGraph();
} 