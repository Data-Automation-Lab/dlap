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

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for restore request from archived storage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestoreRequestDTO {
    
    @NotNull
    @NotEmpty
    private List<String> assetIds;
    
    private String requestedBy;
    private String reason;
    private String targetLocation;
    private Integer retentionDays; // How long to keep restored data accessible
    private Boolean priorityRestore; // Fast restore vs standard
    private String restoreTier; // Added field expected by service
    
} 