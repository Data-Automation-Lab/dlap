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

package com.dlap.policyengine.projection;

/**
 * SPI for pushing a compiled {@code kind: AccessPolicy} down into a
 * platform-native access-control system (ADR-0040 push-down mode; sibling of
 * ADR-0035's {@code ComplianceLabelSink}). An implementation translates the
 * cloud-neutral {@link CompiledAccessPolicy} into its platform's grant model
 * (Unity Catalog {@code GRANT}/{@code REVOKE}, BigQuery policy tags, …) so the
 * platform self-enforces even when it never calls the AuthZEN PDP.
 *
 * <p>This is <b>detection-grade / best-effort</b>. The AuthZEN endpoint
 * (C1 PDP) is the authoritative source of truth; a projection is a convenience
 * that lets an already-existing platform control cover the common case. A sink
 * MUST NEVER throw onto the caller's path - a projection failure must not break
 * policy authoring or PDP evaluation.
 *
 * <p>The default {@link LoggingPolicyProjectionSink} is observe-only (it logs
 * what it <i>would</i> project), so the feature is safe to enable before a real
 * write path is wired. The whole feature is gated off by
 * {@code dlap.policy.projection.enabled} (default false); a deployment wires a
 * concrete sink such as {@link UnityCatalogPolicyProjectionSink}.
 */
public interface PolicyProjectionSink {

    /**
     * Project the policy's grant intents onto the platform (apply grants for an
     * {@code allow} policy; apply revokes for a {@code deny} policy). Called
     * when an AccessPolicy is created or (re-)enabled.
     *
     * @param compiled the compiled push-down intent (never null)
     */
    void project(CompiledAccessPolicy compiled);

    /**
     * Reverse a previously-projected policy (revoke what {@link #project} added,
     * or re-grant what a {@code deny} removed). Called when an AccessPolicy is
     * disabled or deleted. Implementations must be idempotent - reversing a
     * never-projected policy is a no-op.
     *
     * @param compiled the compiled push-down intent to reverse (never null)
     */
    void revoke(CompiledAccessPolicy compiled);

    /** @return true when a real platform integration is wired and enabled. */
    default boolean isEnabled() {
        return false;
    }
}
