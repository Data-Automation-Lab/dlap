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

package com.dlap.policyengine.access;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.dsl.AssetSnapshots;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link IAssetSnapshotResolver} (enforcement-contract.md section 2). Resolves the
 * resource against da-catalog through the circuit-broken {@link ResilientCatalogAssetClient}:
 * {@code resource.id} when it is a catalog UUID, otherwise {@code resource.properties.assetUri}
 * through {@code GET /assets/resolve}.
 *
 * <p>When the catalog knows the asset, its view is authoritative and complete: the snapshot is
 * {@link AssetSnapshots#fromCatalogAsset}, so the PDP evaluates the same lifecycle fields as a
 * lifecycle policy ({@code lastAccessedAt}, {@code createdAt}, {@code sizeBytes},
 * {@code readCoverage}, legal hold and retention state, POL-01) and the PEP's {@code labels} hint
 * is ignored. When it does not (unknown id, unknown URI, catalog down), the snapshot holds only the
 * PEP's hints; every other field is unknown, and an unknown field never satisfies an allow and
 * always satisfies a deny ({@link com.dlap.policyengine.dsl.PolicyDslEvaluator#applies}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogAssetSnapshotResolver implements IAssetSnapshotResolver {

    /** {@code resource.properties} keys the PEP may supply as hints. */
    static final String PROP_LABELS = "labels";
    static final String PROP_ASSET_URI = "assetUri";

    private final ResilientCatalogAssetClient catalogClient;

    @Override
    public ResolvedResource resolve(AuthZenEvaluationRequest.Resource resource) {
        String assetId = resource != null ? resource.getId() : null;
        Map<String, Object> props = resource != null ? resource.getProperties() : null;
        String assetUri = props != null && props.get(PROP_ASSET_URI) != null
                ? props.get(PROP_ASSET_URI).toString() : null;

        Optional<CatalogAssetDTO> asset = catalogAsset(assetId, assetUri);
        if (asset.isPresent()) {
            CatalogAssetDTO a = asset.get();
            Map<String, Object> snapshot = AssetSnapshots.fromCatalogAsset(a);
            if (assetUri != null) {
                snapshot.putIfAbsent(PROP_ASSET_URI, assetUri);
            }
            String resolvedId = a.getAssetId() != null ? a.getAssetId().toString() : assetId;
            String resolvedUri = assetUri != null ? assetUri : a.getAssetUri();
            return new ResolvedResource(snapshot, resolvedId, resolvedUri);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (props != null && props.get(PROP_LABELS) != null) {
            snapshot.put(PROP_LABELS, props.get(PROP_LABELS));
        }
        if (assetUri != null) {
            snapshot.put(PROP_ASSET_URI, assetUri);
        }
        log.debug("Resource not resolvable via the catalog; evaluating against PEP-supplied properties only");
        return new ResolvedResource(snapshot, assetId, assetUri);
    }

    private Optional<CatalogAssetDTO> catalogAsset(String assetId, String assetUri) {
        if (assetId != null && !assetId.isBlank()) {
            Optional<UUID> id = uuid(assetId);
            if (id.isPresent()) {
                Optional<CatalogAssetDTO> byId = catalogClient.getAssetById(id.get());
                if (byId.isPresent()) {
                    return byId;
                }
            }
        }
        if (assetUri != null && !assetUri.isBlank()) {
            return catalogClient.resolveAssetIdByUri(assetUri).flatMap(catalogClient::getAssetById);
        }
        return Optional.empty();
    }

    private static Optional<UUID> uuid(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            log.debug("resource.id '{}' is not a catalog UUID; trying the assetUri", text);
            return Optional.empty();
        }
    }
}
