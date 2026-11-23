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

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AuthZEN 1.0 access-evaluation response, profiled for DLAP
 * (enforcement-contract.md §3): a boolean {@code decision} plus a
 * {@code context} carrying the decision id, admin reason, and DLAP's
 * obligation extension. Null members are omitted from the wire.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthZenEvaluationResponse {

    private boolean decision;

    private Context context;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Context {
        /** Decision id - the same id appears in the decision-log event (§6). */
        private String id;

        /** {@code {"en": "..."}} - human/admin reason naming the deciding policy. */
        @JsonProperty("reason_admin")
        private Map<String, String> reasonAdmin;

        /** DLAP extension: obligations the PEP must honour on an allow. */
        private List<Obligation> obligations;
    }

    /** Obligation as returned on the wire: {@code {type, labels}}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Obligation {
        private String type;
        private List<String> labels;
    }
}
