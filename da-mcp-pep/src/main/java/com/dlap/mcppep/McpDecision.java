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

package com.dlap.mcppep;

import java.util.List;

/**
 * The PEP's enforcement verdict for a single {@link McpAccessRequest}.
 *
 * <p>Derived from the AuthZEN evaluation response
 * (docs/specs/enforcement-contract.md §3) or synthesized locally when the
 * verdict is served from cache or produced by a fail-open/fail-closed rule.
 *
 * @param allow       whether the access is permitted
 * @param reason      human-readable reason (the {@code reason_admin.en} from
 *                    the PDP, or a local explanation)
 * @param obligations obligations the PEP must honor on an allow (never null)
 * @param fromCache   true when this verdict was served from the local cache
 * @param failClosed  true when this verdict was produced by a fail-closed or
 *                    fail-open fallback because the PDP was unavailable
 */
public record McpDecision(
        boolean allow,
        String reason,
        List<Obligation> obligations,
        boolean fromCache,
        boolean failClosed) {

    /** Normalizes {@code obligations} to a non-null immutable list. */
    public McpDecision {
        obligations = (obligations == null) ? List.of() : List.copyOf(obligations);
    }

    /** An allow verdict with the given reason and obligations. */
    public static McpDecision allow(String reason, List<Obligation> obligations) {
        return new McpDecision(true, reason, obligations, false, false);
    }

    /** A deny verdict with the given reason and no obligations. */
    public static McpDecision deny(String reason) {
        return new McpDecision(false, reason, List.of(), false, false);
    }

    /** Returns a copy of this decision flagged as served from cache. */
    public McpDecision asCached() {
        return new McpDecision(allow, reason, obligations, true, failClosed);
    }
}
