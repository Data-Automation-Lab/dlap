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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * YAML-to-{@link PolicyDsl} parser. Single dependency: Jackson YAML.
 *
 * <p>Validates {@code apiVersion} and {@code kind} fields per the spec
 * and throws {@link PolicyDslParseException} on any structural error.
 * The evaluator never sees a partially-valid document.
 */
@Component
public class PolicyDslParser {

    private static final String EXPECTED_API_VERSION = "dlap/v1";

    /** {@code spec.triggers[].type} of a cron trigger. */
    public static final String TRIGGER_SCHEDULE = "schedule";

    private final ObjectMapper yamlMapper;

    public PolicyDslParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Parse a YAML policy document into the in-memory model.
     *
     * <p>Two kinds are accepted (ADR-0031, ADR-0040): {@code Policy}
     * (lifecycle) and {@code AccessPolicy} (Consumed-stage access intent).
     * Each has its own required-field validation; the {@code Policy} path is
     * unchanged from v1.1.
     *
     * @throws PolicyDslParseException if the YAML is malformed, the
     *     apiVersion doesn't match {@code dlap/v1}, the kind is unknown,
     *     or kind-required fields are missing.
     */
    public PolicyDsl parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new PolicyDslParseException("policy YAML is null or empty");
        }
        PolicyDsl dsl;
        try {
            dsl = yamlMapper.readValue(yaml, PolicyDsl.class);
        } catch (Exception e) {
            throw new PolicyDslParseException("invalid YAML: " + e.getMessage(), e);
        }
        validate(dsl);
        return dsl;
    }

    /**
     * Reads a YAML policy document into the model without any validation, so a start-up check can
     * tell what kind of document an invalid one is and which of its parts is wrong (POL-02). The
     * evaluator never receives a document read this way.
     *
     * @param yaml the document
     * @return the model as written
     * @throws PolicyDslParseException when the text is not YAML of the policy shape
     */
    public PolicyDsl readUnvalidated(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new PolicyDslParseException("policy YAML is null or empty");
        }
        try {
            PolicyDsl dsl = yamlMapper.readValue(yaml, PolicyDsl.class);
            if (dsl == null) {
                throw new PolicyDslParseException("parsed document is null");
            }
            return dsl;
        } catch (PolicyDslParseException e) {
            throw e;
        } catch (Exception e) {
            throw new PolicyDslParseException("invalid YAML: " + e.getMessage(), e);
        }
    }

    /**
     * Why a schedule trigger's cron cannot fire, or {@code null} when it parses (the rule of
     * {@link #validateCron}).
     *
     * @param cron the expression; may be {@code null}
     * @return the reason, or {@code null}
     */
    public static String cronDefect(String cron) {
        try {
            validateCron(cron);
            return null;
        } catch (PolicyDslParseException e) {
            return e.getMessage();
        }
    }

    /**
     * Why a {@code kind: Policy} document may not state {@code spec.effect} (POL-05, Q-889), or
     * {@code null} when it does not. {@code effect} belongs to {@code kind: AccessPolicy}; the PDP keeps
     * only AccessPolicy documents, so a lifecycle document that says {@code effect: deny} reads as a
     * deny and denies nothing. A write path refuses it (HTTP 400); the start-up check reports a stored
     * one. An explicit null counts as absent, as in {@link UnparseableAccessPolicy}.
     *
     * @param dsl the document as read, validated or not; may be {@code null}
     * @return the reason, or {@code null} when the document is not a lifecycle Policy or states no effect
     */
    public static String lifecycleEffectDefect(PolicyDsl dsl) {
        if (dsl == null || dsl.getKind() == null || !PolicyDsl.KIND_POLICY.equalsIgnoreCase(dsl.getKind().strip())
                || dsl.getSpec() == null || dsl.getSpec().getEffect() == null) {
            return null;
        }
        return "spec.effect is not allowed on kind " + PolicyDsl.KIND_POLICY + " (found '"
                + dsl.getSpec().getEffect() + "'): a lifecycle policy has no effect and never denies access; "
                + "use kind " + PolicyDsl.KIND_ACCESS_POLICY + " for an allow or deny, or remove spec.effect";
    }

    private static void validate(PolicyDsl dsl) {
        if (dsl == null) {
            throw new PolicyDslParseException("parsed document is null");
        }
        if (!EXPECTED_API_VERSION.equals(dsl.getApiVersion())) {
            throw new PolicyDslParseException(
                    "unsupported apiVersion: " + dsl.getApiVersion()
                            + " (expected " + EXPECTED_API_VERSION + ")");
        }
        String kind = dsl.getKind();
        if (PolicyDsl.KIND_POLICY.equals(kind)) {
            validatePolicy(dsl);
        } else if (PolicyDsl.KIND_ACCESS_POLICY.equals(kind)) {
            validateAccessPolicy(dsl);
        } else {
            throw new PolicyDslParseException(
                    "unsupported kind: " + kind + " (expected "
                            + PolicyDsl.KIND_POLICY + " or " + PolicyDsl.KIND_ACCESS_POLICY + ")");
        }
    }

    /** {@code kind: Policy} validation - unchanged from v1.1. */
    private static void validatePolicy(PolicyDsl dsl) {
        requireMetadataId(dsl);
        if (dsl.getSpec() == null) {
            throw new PolicyDslParseException("policy spec is required");
        }
        if (dsl.getSpec().getAction() == null
                || dsl.getSpec().getAction().getType() == null
                || dsl.getSpec().getAction().getType().isBlank()) {
            throw new PolicyDslParseException("policy spec.action.type is required");
        }
        if (dsl.getSpec().getTriggers() == null || dsl.getSpec().getTriggers().isEmpty()) {
            throw new PolicyDslParseException("policy spec.triggers must list at least one trigger");
        }
        for (PolicyDsl.Trigger trigger : dsl.getSpec().getTriggers()) {
            if (trigger != null && TRIGGER_SCHEDULE.equalsIgnoreCase(trigger.getType())) {
                validateCron(trigger.getCron());
            }
        }
    }

    /**
     * A schedule trigger needs a cron the scheduled sweep can fire (POL-01): six fields with
     * seconds first, as Spring's {@link CronExpression} reads them ({@code ?}, {@code L},
     * {@code W} and {@code #} included). Quartz's optional seventh (year) field is not supported.
     *
     * @param cron the expression
     * @throws PolicyDslParseException when it is missing or does not parse
     */
    static void validateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new PolicyDslParseException("a schedule trigger requires a cron expression");
        }
        try {
            CronExpression.parse(cron.strip());
        } catch (IllegalArgumentException e) {
            throw new PolicyDslParseException("invalid schedule cron '" + cron + "': " + e.getMessage()
                    + " (six fields, seconds first, for example \"0 0 2 * * ?\")", e);
        }
    }

    /** {@code kind: AccessPolicy} validation (ADR-0040). */
    private static void validateAccessPolicy(PolicyDsl dsl) {
        requireMetadataId(dsl);
        if (dsl.getSpec() == null) {
            throw new PolicyDslParseException("access policy spec is required");
        }
        String effect = dsl.getSpec().getEffect();
        if (effect == null
                || !(PolicyDsl.EFFECT_ALLOW.equals(effect) || PolicyDsl.EFFECT_DENY.equals(effect))) {
            throw new PolicyDslParseException(
                    "access policy spec.effect must be '" + PolicyDsl.EFFECT_ALLOW
                            + "' or '" + PolicyDsl.EFFECT_DENY + "'");
        }
        PolicyDsl.Subjects subjects = dsl.getSpec().getSubjects();
        boolean hasRoles = subjects != null && subjects.getRoles() != null && !subjects.getRoles().isEmpty();
        boolean hasTiers = subjects != null && subjects.getTiers() != null && !subjects.getTiers().isEmpty();
        if (!hasRoles && !hasTiers) {
            throw new PolicyDslParseException(
                    "access policy spec.subjects must declare roles and/or tiers");
        }
        if (dsl.getSpec().getActions() == null || dsl.getSpec().getActions().isEmpty()) {
            throw new PolicyDslParseException(
                    "access policy spec.actions must list at least one action");
        }
    }

    /** Shared: {@code metadata.id} is mandatory for both kinds. */
    private static void requireMetadataId(PolicyDsl dsl) {
        if (dsl.getMetadata() == null || dsl.getMetadata().getId() == null
                || dsl.getMetadata().getId().isBlank()) {
            throw new PolicyDslParseException("policy metadata.id is required");
        }
    }


    /** Thrown when a YAML policy document cannot be parsed or fails validation. */
    public static class PolicyDslParseException extends RuntimeException {
        public PolicyDslParseException(String message) {
            super(message);
        }
        public PolicyDslParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
