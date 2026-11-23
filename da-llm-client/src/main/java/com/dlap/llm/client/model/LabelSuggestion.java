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

package com.dlap.llm.client.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a label suggestion from an LLM with associated confidence score.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabelSuggestion {

    /**
     * The suggested label text.
     */
    private String label;

    /**
     * Confidence score for this suggestion (0.0 to 1.0).
     */
    private Double confidence;

    /**
     * Optional reasoning or explanation for this suggestion.
     */
    private String reasoning;

    /**
     * Category or type of this label (e.g., "PII", "BUSINESS", "TECHNICAL").
     */
    private String category;

    /**
     * Provenance the consumer should stamp on the resulting label assignment, for
     * example {@code SMART_LABEL_RULE} for a deterministic rule match. {@code null}
     * means the provider is model-based and the consumer applies its default
     * model-inferred provenance. Values are the {@code AssignmentSource} names
     * the catalog accepts.
     */
    private String source;

    /**
     * Identifier of the deterministic rule that produced this suggestion, for
     * example {@code pii.government-id}. {@code null} for model-based providers.
     */
    private String ruleId;
}
