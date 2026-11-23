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

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * Matches the Spring Data {@code Page} JSON shape for Feign deserialization
 * of da-catalog {@code GET /api/v1/catalog/assets}. Only {@code content},
 * {@code totalElements}, {@code totalPages}, and {@code number} are read;
 * pagination lets the scan walk every catalog page.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogAssetPageDTO {

    private List<CatalogAssetDTO> content = Collections.emptyList();
    private long totalElements;
    private int totalPages;
    private int number;
    private boolean last;

    public List<CatalogAssetDTO> getContent() {
        return content != null ? content : Collections.emptyList();
    }
}
