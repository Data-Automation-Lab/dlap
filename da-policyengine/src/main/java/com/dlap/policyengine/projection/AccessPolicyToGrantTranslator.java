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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.dlap.policyengine.dsl.PolicyDsl;

/**
 * Pure, side-effect-free compiler from a {@code kind: AccessPolicy} document to
 * a {@link CompiledAccessPolicy} of platform-neutral {@link GrantIntent}s
 * (ADR-0040 push-down mode). No Spring wiring, no I/O - this is the unit-testable
 * core of the projection feature.
 *
 * <p>Translation rules:
 * <ul>
 *   <li><b>subjects → principals.</b> Both {@code subjects.roles} and
 *       {@code subjects.tiers} become principal tokens (roles verbatim, tiers
 *       prefixed {@code tier:} so a platform can map them to a group). The
 *       AccessPolicy OR-semantics between the two clauses is preserved as the
 *       union of principals.</li>
 *   <li><b>actions → privileges.</b> Each AuthZEN action name maps to a
 *       platform-neutral privilege verb via a fixed table
 *       ({@code read/query → SELECT}, {@code write → MODIFY},
 *       {@code export → SELECT}, {@code delete → MODIFY}); unknown actions map
 *       to {@code SELECT} (least-surprising read privilege) and are recorded
 *       so nothing is silently dropped.</li>
 *   <li><b>securables.</b> The caller resolves the {@code match.labels}
 *       selector to concrete asset URIs (best-effort) and passes them in; the
 *       translator produces one intent per principal × privilege × securable.</li>
 *   <li><b>effect.</b> {@code allow} → grant intents; {@code deny} → revoke
 *       intents (the push-down of an explicit deny is to remove the platform
 *       privilege - detection-grade, the PDP still owns the authoritative deny).</li>
 * </ul>
 */
@Component
public class AccessPolicyToGrantTranslator {

    /** Default privilege for an unrecognized action - the least-privileged read verb. */
    static final String DEFAULT_PRIVILEGE = "SELECT";

    /** Fixed action → platform-neutral privilege table. */
    private static final Map<String, String> ACTION_PRIVILEGE = Map.of(
            "read", "SELECT",
            "query", "SELECT",
            "export", "SELECT",
            "list", "SELECT",
            "write", "MODIFY",
            "update", "MODIFY",
            "delete", "MODIFY",
            "create", "MODIFY");

    /** Prefix marking a persona-tier principal so a platform can map it to a group. */
    static final String TIER_PREFIX = "tier:";

    /**
     * Compile an AccessPolicy DSL document plus its resolved securables into a
     * {@link CompiledAccessPolicy}.
     *
     * @param dsl        a parsed {@code kind: AccessPolicy} document
     * @param securables best-effort resolved securable identifiers (asset URIs)
     *                   the policy's {@code match.labels} selector picked out;
     *                   an empty list yields an empty compilation
     * @return the compiled intent (never null; possibly empty)
     * @throws IllegalArgumentException if {@code dsl} is not an AccessPolicy
     */
    public CompiledAccessPolicy translate(PolicyDsl dsl, List<String> securables) {
        if (dsl == null || !PolicyDsl.KIND_ACCESS_POLICY.equals(dsl.getKind())) {
            throw new IllegalArgumentException("translate requires a kind: AccessPolicy document");
        }
        PolicyDsl.Spec spec = dsl.getSpec();
        String policyId = dsl.getMetadata() != null ? dsl.getMetadata().getId() : null;
        String policyName = dsl.getMetadata() != null ? dsl.getMetadata().getName() : null;
        String effect = spec != null && spec.getEffect() != null
                ? spec.getEffect() : PolicyDsl.EFFECT_ALLOW;
        boolean grant = !PolicyDsl.EFFECT_DENY.equalsIgnoreCase(effect);

        List<String> principals = principals(spec);
        List<String> privileges = privileges(spec);
        List<String> targets = securables == null ? List.of() : securables;

        List<GrantIntent> intents = new ArrayList<>();
        for (String securable : targets) {
            if (securable == null || securable.isBlank()) {
                continue;
            }
            for (String principal : principals) {
                for (String privilege : privileges) {
                    intents.add(new GrantIntent(principal, privilege, securable, grant));
                }
            }
        }
        return new CompiledAccessPolicy(policyId, policyName, effect, intents, describeSelector(spec));
    }

    /** Union of role principals and {@code tier:}-prefixed tier principals (OR-semantics). */
    private List<String> principals(PolicyDsl.Spec spec) {
        Set<String> out = new LinkedHashSet<>();
        if (spec != null && spec.getSubjects() != null) {
            PolicyDsl.Subjects subjects = spec.getSubjects();
            if (subjects.getRoles() != null) {
                for (String r : subjects.getRoles()) {
                    if (r != null && !r.isBlank()) {
                        out.add(r);
                    }
                }
            }
            if (subjects.getTiers() != null) {
                for (String t : subjects.getTiers()) {
                    if (t != null && !t.isBlank()) {
                        out.add(TIER_PREFIX + t);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Distinct privilege verbs for the policy's actions (fixed table; unknown → SELECT). */
    private List<String> privileges(PolicyDsl.Spec spec) {
        Set<String> out = new LinkedHashSet<>();
        if (spec != null && spec.getActions() != null) {
            for (String action : spec.getActions()) {
                if (action == null || action.isBlank()) {
                    continue;
                }
                out.add(ACTION_PRIVILEGE.getOrDefault(
                        action.toLowerCase(Locale.ROOT), DEFAULT_PRIVILEGE));
            }
        }
        if (out.isEmpty()) {
            out.add(DEFAULT_PRIVILEGE);
        }
        return new ArrayList<>(out);
    }

    /** Human-readable rendering of the {@code match.labels} selector, for provenance. */
    private String describeSelector(PolicyDsl.Spec spec) {
        if (spec == null || spec.getMatch() == null) {
            return "match:*";
        }
        Object labels = spec.getMatch().get("labels");
        return labels == null ? "match:*" : "labels:" + labels;
    }
}
