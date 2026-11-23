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

package com.dlap.catalog.dto;

import java.util.List;

public class LineageResponseDTO {
    private List<LineageAssetDTO> upstreamAssets;
    private List<LineageAssetDTO> downstreamAssets;

    public LineageResponseDTO() {}

    public LineageResponseDTO(List<LineageAssetDTO> upstreamAssets, List<LineageAssetDTO> downstreamAssets) {
        this.upstreamAssets = upstreamAssets;
        this.downstreamAssets = downstreamAssets;
    }

    // Getters and Setters
    public List<LineageAssetDTO> getUpstreamAssets() {
        return upstreamAssets;
    }

    public void setUpstreamAssets(List<LineageAssetDTO> upstreamAssets) {
        this.upstreamAssets = upstreamAssets;
    }

    public List<LineageAssetDTO> getDownstreamAssets() {
        return downstreamAssets;
    }

    public void setDownstreamAssets(List<LineageAssetDTO> downstreamAssets) {
        this.downstreamAssets = downstreamAssets;
    }
} 