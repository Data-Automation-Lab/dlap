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

import com.dlap.policyengine.dsl.PolicyDsl;

/**
 * Internal, deterministic outcome of an access evaluation
 * (enforcement-contract.md §3/§6). Produced by
 * {@link AccessDecisionService}, then projected onto both the AuthZEN
 * response and the decision-log event.
 *
 * @param decisionId       stable {@code dec-...} id (also the audit join key)
 * @param allow            the boolean verdict
 * @param reason           admin-facing reason naming the deciding policy / default
 * @param policyIds        metadata ids of the policies that decided the verdict
 * @param obligations      unioned obligations of all contributing allows
 * @param defaultApplied   true when no policy matched and the default decided
 * @param latencyMs        wall-clock evaluation latency
 */
public record AccessDecision(
        String decisionId,
        boolean allow,
        String reason,
        List<String> policyIds,
        List<PolicyDslObligation> obligations,
        boolean defaultApplied,
        long latencyMs) {

    /** {@code spec.effect: allow} (alias of {@link PolicyDsl#EFFECT_ALLOW}). */
    public static final String EFFECT_ALLOW = PolicyDsl.EFFECT_ALLOW;

    /** {@code spec.effect: deny} (alias of {@link PolicyDsl#EFFECT_DENY}). */
    public static final String EFFECT_DENY = PolicyDsl.EFFECT_DENY;

    /** {@code ALLOW} / {@code DENY} - the decision-log event's {@code decision} field. */
    public String decisionLabel() {
        return allow ? "ALLOW" : "DENY";
    }

    /**
     * A resolved obligation ({@code type} + {@code labels}) - a value copy of a
     * {@code PolicyDsl.Obligation} carried into decisions and events.
     */
    public record PolicyDslObligation(String type, List<String> labels) {
        public PolicyDslObligation {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }
}
