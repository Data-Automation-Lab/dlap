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

package com.dlap.autolabel.client.rest.dto;

import java.util.List;
import java.util.Map;

import com.dlap.llm.client.config.MLConfigRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Request DTO to trigger an auto-labeling job.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabelingJobRequest {

    /**
     * A user-defined name for this labeling job for easier identification.
     */
    @NotNull(message = "Job name cannot be null")
    private String jobName;

    /**
     * Specifies the scope of assets to be labeled.
     * Only one of these should typically be provided.
     */
    @NotNull(message = "Labeling scope cannot be null")
    @Valid // Enable validation for nested LabelingScope object
    private LabelingScope scope;

    /**
     * Optional: Override ML configuration for this specific job.
     * If not provided, the globally configured ML settings will be used.
     */
    @Valid // Enable validation for nested MLConfigRequest object if annotations are added there
    private MLConfigRequest overrideMlConfig;

    /**
     * Optional: Parameters to control job execution, e.g., priority, run mode (dry-run vs apply).
     */
    private Map<String, String> jobParameters;

    /**
     * Nested DTO for defining the scope of the labeling job.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LabelingScope {
        /**
         * List of specific asset IDs to label.
         */
        private List<String> assetIds;

        /**
         * ID of a cloud connection. All assets under this connection will be considered.
         * This implies da-autolabel might need to query da-catalog for assets based on connectionId.
         */
        private String cloudConnectionId;

        /**
         * Criteria for selecting assets, e.g., based on existing labels, asset types, or other metadata.
         * This would be a more complex filter.
         * For now, represented as a map, but could be a more structured query object.
         */
        private Map<String, String> assetCriteria;

        /**
         * An identifier for a pre-defined asset group or collection.
         */
        private String assetGroupId;
        
        // It would be good to add a validation ensuring at least one scope field is provided.
        // This can be done with a custom class-level validator, or by checking in the service layer.
    }
} 