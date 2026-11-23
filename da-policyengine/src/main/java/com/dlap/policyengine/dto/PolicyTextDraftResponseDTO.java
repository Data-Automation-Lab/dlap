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

package com.dlap.policyengine.dto;

import java.util.List;

import com.dlap.policyengine.dsl.PolicyDsl;

/**
 * Response body for {@code POST /api/v1/policyengine/policies/draft-from-text}.
 *
 * <p>This is a DRAFT. Nothing is persisted. The UI renders it in the visual
 * builder for a human to inspect, correct and confirm; only the existing
 * {@code createPolicy} endpoint creates a policy.
 *
 * <p>On a rejected draft {@code dslYaml} and {@code structured} are
 * {@code null} and {@code warnings} explains why. No field is ever
 * back-filled with a guessed value.
 *
 * @param dslYaml Canonical {@code dlap/v1} YAML, or {@code null} when rejected.
 * @param structured Parsed form of {@code dslYaml}, or {@code null} when rejected.
 * @param confidence Model-reported confidence in {@code [0,1]}, or {@code null}
 *     when the model did not report a usable value.
 * @param assumptions Assumptions the model says it made. Never empty-filled.
 * @param warnings Everything a human must check before confirming: forced
 *     safety defaults, rejection reasons, unsupported grammar.
 */
public record PolicyTextDraftResponseDTO(
        String dslYaml,
        PolicyDsl structured,
        Double confidence,
        List<String> assumptions,
        List<String> warnings) {
}
