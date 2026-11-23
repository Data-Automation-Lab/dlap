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

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AuthZEN 1.0 access-evaluation request, profiled for DLAP
 * (enforcement-contract.md §2). The wire shape is {@code subject} /
 * {@code action} / {@code resource} / {@code context}; only the members DLAP
 * reads are typed, and unknown members are ignored per AuthZEN's open model.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthZenEvaluationRequest {

    @NotNull
    private Subject subject;

    @NotNull
    private Action action;

    private Resource resource;

    /** Free-form request context; DLAP evaluates the keys named by condition clauses. */
    private Map<String, Object> context;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Subject {
        /** {@code agent}, {@code user}, or {@code service}. */
        private String type;
        private String id;
        /** {@code roles}, {@code tier}, {@code on_behalf_of}. */
        private Map<String, Object> properties;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Action {
        private String name;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Resource {
        private String type;
        /** DLAP catalog asset UUID (may be null when only properties are supplied). */
        private String id;
        /** {@code assetUri}, {@code labels} hints. */
        private Map<String, Object> properties;
    }
}
