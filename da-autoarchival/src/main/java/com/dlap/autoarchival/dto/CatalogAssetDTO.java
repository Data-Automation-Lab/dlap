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

package com.dlap.autoarchival.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * Read-only subset of da-catalog's {@code AssetOutputDTO} consumed by da-autoarchival
 * over Feign. Mirrors the equivalent DTO in da-autodelete so both retire-stage services
 * resolve an asset the same way.
 *
 * <p>Only the fields needed to route an archival call are declared:
 * <ul>
 *   <li>{@code cloudProvider} - selects the {@code ICloudStorageService}.</li>
 *   <li>{@code assetUri} - the canonical cloud locator the storage SDK operates on
 *       (e.g. {@code gs://bucket/key}, {@code s3://bucket/key}).</li>
 *   <li>{@code nativeAssetId} - secondary locator source for discovery-written assets.</li>
 * </ul>
 * Unknown catalog fields are ignored so the DTO survives catalog-side schema growth.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogAssetDTO {

    private UUID assetId;
    private String assetName;
    private String assetType;
    private String cloudProvider;
    private String assetUri;
    private String nativeAssetId;
    private String region;
}
