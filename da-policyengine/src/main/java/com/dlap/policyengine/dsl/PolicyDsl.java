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

package com.dlap.policyengine.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * In-memory representation of a YAML policy document as defined in
 * {@code docs/specs/policy-dsl.md} v1.1 (ADR-0021).
 *
 * <p>Parsed by {@link PolicyDslParser}; evaluated by {@link PolicyDslEvaluator}.
 * Field names match the YAML schema directly so Jackson YAML auto-binds.
 *
 * <p>Unknown fields are ignored (forward-compat with v1.2+ additions).
 *
 * <p>Two kinds share this one model (ADR-0031, ADR-0040):
 * <ul>
 *   <li>{@code kind: Policy} - lifecycle policies ({@code spec.triggers} +
 *       {@code spec.match} + {@code spec.action}).</li>
 *   <li>{@code kind: AccessPolicy} - Consumed-stage access intent
 *       ({@code spec.subjects} + {@code spec.actions} + {@code spec.match} +
 *       {@code spec.condition} + {@code spec.effect} + {@code spec.obligations}).</li>
 * </ul>
 * The AccessPolicy fields are nullable; a {@code kind: Policy} document leaves
 * them unset, so its binding and evaluation are byte-for-byte unchanged.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyDsl {

    /** {@code Policy} kind literal. */
    public static final String KIND_POLICY = "Policy";

    /** {@code AccessPolicy} kind literal (ADR-0040). */
    public static final String KIND_ACCESS_POLICY = "AccessPolicy";

    /** {@code spec.effect: allow} (AccessPolicy). */
    public static final String EFFECT_ALLOW = "allow";

    /** {@code spec.effect: deny} (AccessPolicy). */
    public static final String EFFECT_DENY = "deny";

    /** {@code apiVersion: dlap/v1} - currently the only accepted value. */
    private String apiVersion;

    /** {@code kind: Policy} or {@code kind: AccessPolicy}. */
    private String kind;

    private Metadata metadata;
    private Spec spec;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        private String id;
        private String name;
        private String description;
        private String owner;
        private boolean enabled = true;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Spec {
        /** {@code triggers}: mixed schedule + event entries. ({@code kind: Policy}) */
        private List<Trigger> triggers;

        /** {@code match}: free-form map of clauses; resolved by the evaluator. */
        private Map<String, Object> match;

        /** {@code action}: required for {@code kind: Policy}. */
        private Action action;

        /* ----- AccessPolicy fields (ADR-0040, kind: AccessPolicy) ----- */

        /** {@code subjects}: who the policy applies to. ({@code kind: AccessPolicy}) */
        private Subjects subjects;

        /** {@code actions}: AuthZEN action names the policy covers. */
        private List<String> actions;

        /** {@code condition}: request-context clauses; all present must hold. */
        private Condition condition;

        /** {@code effect}: {@link #EFFECT_ALLOW} or {@link #EFFECT_DENY}. */
        private String effect;

        /** {@code obligations}: attached to allow verdicts. */
        private List<Obligation> obligations;
    }


    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Trigger {
        /** {@code schedule} or {@code event}. */
        private String type;

        /** schedule-trigger: Quartz cron with seconds field. */
        private String cron;

        /** event-trigger: Kafka topic. */
        private String topic;

        /** event-trigger shorthand: {@code lifecycle: created | modified | ...}. */
        private String lifecycle;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Action {
        /**
         * One of: {@code archive}, {@code delete}, {@code addLabel},
         * {@code removeLabel}, {@code alert.email}, {@code alert.slack},
         * {@code alert.webhook}, {@code setRetention}, {@code tagAsset},
         * {@code evaluateCompliance}, {@code runDiscovery}.
         */
        private String type;

        private boolean requireApproval = false;
        private boolean dryRun = false;

        /** Free-form action parameter map - per-action shape lives in the spec. */
        private Map<String, Object> parameters;
    }

    /** {@code spec.subjects} - subject clauses; a subject matches if any present clause holds. */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Subjects {
        /** ADR-0032 persona tiers (EXECUTIVE / LEADERSHIP / PRACTITIONER / READER). */
        private List<String> tiers;
        /** Keycloak realm roles. */
        private List<String> roles;
    }

    /** {@code spec.condition} - request-context clauses evaluated against AuthZEN {@code context}. */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Condition {
        private AnyOf purpose;
        private Boolean consent;
        private AnyOf region;
    }

    /** {@code anyOf} wrapper used by condition clauses. */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnyOf {
        private List<String> anyOf;
    }

    /** {@code spec.obligations[]} entry (enforcement-contract.md §3). */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Obligation {
        private String type;
        private List<String> labels;
    }
}
