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

package com.dlap.policyengine.client.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Answer of da-catalog {@code GET /api/v1/catalog/assets/resolve?assetUri=}: the catalog id of the
 * asset an URI names (in any form {@code AssetIdNormalizer} accepts, ADR-0047).
 *
 * @param assetId  the catalog asset id
 * @param assetUri the URI as the caller sent it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogAssetIdResolutionDTO(UUID assetId, String assetUri) {
}
