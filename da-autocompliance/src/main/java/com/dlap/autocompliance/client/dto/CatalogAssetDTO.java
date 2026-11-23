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

package com.dlap.autocompliance.client.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * Read-only projection of da-catalog {@code AssetOutputDTO} used by the
 * catalog-backed compliance evaluators. Only the attributes the seeded
 * controls inspect are modeled; unknown fields are ignored so the DTO
 * survives catalog-side schema growth.
 *
 * <p>Deserialized from {@code GET /api/v1/catalog/assets}. The real asset
 * attributes evaluated are: {@code assetType}, {@code region},
 * {@code labels}, {@code technicalMetadata}, {@code businessMetadataJson}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogAssetDTO {

    private String assetId;
    private String assetName;
    private String cloudProvider;
    private String assetType;
    private String assetUri;
    private String region;
    private List<String> labels;
    private Map<String, Object> technicalMetadata;
    private Map<String, Object> businessMetadataJson;
    private Map<String, Object> detailsJson;
}
