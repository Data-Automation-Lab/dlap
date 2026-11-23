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

import java.util.List;

/**
 * The compiled push-down intent of a single {@code kind: AccessPolicy}
 * document (ADR-0040 push-down mode). This is the cloud-neutral input a
 * {@link PolicyProjectionSink} consumes to self-enforce a policy on a platform
 * that does not call the AuthZEN PDP.
 *
 * <p>It carries the policy's identity, the {@code effect} (allow → grant,
 * deny → revoke), the set of {@link GrantIntent}s (principal × privilege ×
 * securable), and the raw label-selector used to pick the securables (kept for
 * provenance/logging). Produced by {@link AccessPolicyToGrantTranslator};
 * consumed by sinks.
 *
 * @param policyId       the AccessPolicy {@code metadata.id}
 * @param policyName     the AccessPolicy {@code metadata.name} (may be null)
 * @param effect         {@code allow} or {@code deny} (verbatim from the DSL)
 * @param intents        the flattened grant/revoke tuples
 * @param labelSelector  human-readable description of the {@code match.labels}
 *                       selector the securables were drawn from (provenance)
 */
public record CompiledAccessPolicy(
        String policyId,
        String policyName,
        String effect,
        List<GrantIntent> intents,
        String labelSelector) {

    public CompiledAccessPolicy {
        intents = intents == null ? List.of() : List.copyOf(intents);
    }

    /** @return true when this policy has nothing to project (no intents). */
    public boolean isEmpty() {
        return intents.isEmpty();
    }
}
