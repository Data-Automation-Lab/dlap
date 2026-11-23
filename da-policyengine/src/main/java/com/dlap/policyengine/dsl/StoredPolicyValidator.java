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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.repository.PolicyRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Re-validates the stored policies once the service is ready (POL-02). Nothing is rewritten.
 *
 * <ul>
 *   <li><b>AccessPolicies (Q-441).</b> Every stored {@code kind: AccessPolicy}, enabled or not, is
 *       parsed and its match block checked with {@link PolicyDslEvaluator#defects}. Each invalid one
 *       is logged at WARN with its row id, its {@code metadata.id}, its effect and the reasons. A
 *       malformed clause of a deny policy evaluates {@code UNKNOWN}, so the deny fails closed. One
 *       whose document does not parse at all is, when ENABLED, kept by the PDP as an invalid deny
 *       of its readable scope, or skipped when its effect reads allow ({@link UnparseableAccessPolicy},
 *       POL-03); the log line says which.</li>
 *   <li><b>Lifecycle policies that state an effect (POL-05, Q-889).</b> A stored {@code kind: Policy}
 *       document with {@code spec.effect} is counted under the same {@code access-policy} check and
 *       logged: the write paths refuse it, and the PDP ignores it.</li>
 *   <li><b>Schedule crons (Q-436).</b> Every stored document whose schedule trigger has a cron that
 *       does not parse (for example Quartz's seven-field form) is logged at WARN with its row id.
 *       Such a policy is inert on every path (fail closed); the operator fixes the cron and saves
 *       the policy again.</li>
 * </ul>
 *
 * <p>The counts are the gauge {@value #GAUGE}{@code {check}} with {@code check} =
 * {@value #CHECK_ACCESS_POLICY}, {@value #CHECK_SCHEDULE_CRON} or {@value #CHECK_UNREADABLE} (a
 * document that is not policy YAML at all, whose kind is therefore unknown, or, since POL-03, whose
 * kind is neither {@code Policy} nor {@code AccessPolicy}). The gauge holds the
 * counts of the latest check, which runs at start-up.
 */
@Component
@Slf4j
public class StoredPolicyValidator {

    /** Gauge of invalid stored policies, tagged by {@code check}. */
    public static final String GAUGE = "policyengine.policies.invalid";
    /** {@code check} tag: AccessPolicies that do not parse or have malformed match clauses. */
    public static final String CHECK_ACCESS_POLICY = "access-policy";
    /** {@code check} tag: documents with a schedule cron that does not parse. */
    public static final String CHECK_SCHEDULE_CRON = "schedule-cron";
    /** {@code check} tag: documents that are not policy YAML or have an unknown kind. */
    public static final String CHECK_UNREADABLE = "unreadable";

    private final PolicyRepository policyRepository;
    private final PolicyDslParser parser;
    private final AtomicLong invalidAccessPolicies = new AtomicLong();
    private final AtomicLong invalidCrons = new AtomicLong();
    private final AtomicLong unreadable = new AtomicLong();

    /**
     * Creates the validator and registers its gauges.
     *
     * @param policyRepository policy store
     * @param parser           DSL parser
     * @param meterRegistry    metrics
     */
    public StoredPolicyValidator(PolicyRepository policyRepository, PolicyDslParser parser,
            MeterRegistry meterRegistry) {
        this.policyRepository = policyRepository;
        this.parser = parser;
        register(meterRegistry, CHECK_ACCESS_POLICY, invalidAccessPolicies,
                "Stored AccessPolicies that do not parse or have malformed match clauses");
        register(meterRegistry, CHECK_SCHEDULE_CRON, invalidCrons,
                "Stored policies with a schedule cron that does not parse");
        register(meterRegistry, CHECK_UNREADABLE, unreadable, "Stored policy documents that are not policy YAML");
    }

    private static void register(MeterRegistry registry, String check, AtomicLong value, String description) {
        Gauge.builder(GAUGE, value, AtomicLong::doubleValue)
                .tag("check", check)
                .description(description)
                .register(registry);
    }

    /**
     * Counts of one check.
     *
     * @param invalidAccessPolicies row ids of AccessPolicies that do not parse or have malformed clauses
     * @param invalidCrons          row ids of documents with a schedule cron that does not parse
     * @param unreadable            row ids of documents that are not policy YAML
     */
    public record Report(List<UUID> invalidAccessPolicies, List<UUID> invalidCrons, List<UUID> unreadable) {

        /**
         * Copies the lists.
         */
        public Report {
            invalidAccessPolicies = List.copyOf(invalidAccessPolicies);
            invalidCrons = List.copyOf(invalidCrons);
            unreadable = List.copyOf(unreadable);
        }
    }

    /**
     * Runs the check when the application is ready. A database that does not answer is logged;
     * start-up goes on.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            Report report = validate();
            log.info("Stored policy check: {} invalid AccessPolicies, {} policies with an invalid schedule cron, "
                    + "{} unreadable documents", report.invalidAccessPolicies().size(), report.invalidCrons().size(),
                    report.unreadable().size());
        } catch (DataAccessException e) {
            log.error("Stored policy check skipped: the policy store did not answer: {}", e.getMessage());
        }
    }

    /**
     * Checks every stored policy, logs each invalid one and updates the gauges.
     *
     * @return what was found
     */
    public Report validate() {
        List<UUID> access = new ArrayList<>();
        List<UUID> crons = new ArrayList<>();
        List<UUID> broken = new ArrayList<>();
        for (Policy p : policyRepository.findAll()) {
            String yaml = p.getPolicyDocument();
            if (yaml == null || yaml.isBlank()) {
                continue;
            }
            PolicyDsl raw;
            try {
                raw = parser.readUnvalidated(yaml);
            } catch (PolicyDslParser.PolicyDslParseException e) {
                log.warn("Stored policy {} ({}, {}) is not policy YAML; it never runs as a lifecycle policy, and "
                        + "while ENABLED the PDP {}: {}", p.getId(), p.getName(), p.getStatus(),
                        pdpConsequence(p, yaml), e.getMessage());
                broken.add(p.getId());
                continue;
            }
            if (checkCrons(p, raw)) {
                crons.add(p.getId());
            }
            if (PolicyDsl.KIND_ACCESS_POLICY.equals(raw.getKind()) && checkAccessPolicy(p, raw, yaml)) {
                access.add(p.getId());
            } else if (checkLifecycleEffect(p, raw, yaml)) {
                access.add(p.getId());
            } else if (!PolicyDsl.KIND_ACCESS_POLICY.equals(raw.getKind())
                    && !PolicyDsl.KIND_POLICY.equalsIgnoreCase(String.valueOf(raw.getKind()).strip())) {
                // An unknown kind could be a misspelt AccessPolicy; the PDP treats it as one (POL-03).
                log.warn("Stored policy {} ({}, {}) has kind '{}', neither {} nor {}; it never runs as a lifecycle "
                        + "policy, and while ENABLED the PDP {}", p.getId(), p.getName(), p.getStatus(), raw.getKind(),
                        PolicyDsl.KIND_POLICY, PolicyDsl.KIND_ACCESS_POLICY, pdpConsequence(p, yaml));
                broken.add(p.getId());
            }
        }
        invalidAccessPolicies.set(access.size());
        invalidCrons.set(crons.size());
        unreadable.set(broken.size());
        return new Report(access, crons, broken);
    }

    /** Logs the crons of a document that do not parse; true when there is one. */
    private boolean checkCrons(Policy p, PolicyDsl raw) {
        if (raw.getSpec() == null || raw.getSpec().getTriggers() == null) {
            return false;
        }
        List<String> reasons = new ArrayList<>();
        for (PolicyDsl.Trigger t : raw.getSpec().getTriggers()) {
            if (t != null && PolicyDslParser.TRIGGER_SCHEDULE.equalsIgnoreCase(t.getType())) {
                String why = PolicyDslParser.cronDefect(t.getCron());
                if (why != null) {
                    reasons.add(why);
                }
            }
        }
        if (reasons.isEmpty()) {
            return false;
        }
        log.warn("Stored policy {} ({}, metadata.id {}, {}) has a schedule cron that does not parse, so it is inert "
                + "on every path until it is saved with a valid cron: {}", p.getId(), p.getName(), metadataId(raw),
                p.getStatus(), reasons);
        return true;
    }

    /**
     * Logs a {@code kind: Policy} document that states {@code spec.effect} (POL-05, Q-889); true when
     * it does. The write paths now refuse it, so a stored one predates the check or came in by a direct
     * edit. It is counted under {@value #CHECK_ACCESS_POLICY}, the check an operator already watches
     * for AccessPolicies that do not do what they say.
     */
    private boolean checkLifecycleEffect(Policy p, PolicyDsl raw, String yaml) {
        String defect = PolicyDslParser.lifecycleEffectDefect(raw);
        if (defect == null) {
            return false;
        }
        String consequence;
        try {
            parser.parse(yaml);
            consequence = "the PDP ignores it, so it never denies or allows anything";
        } catch (PolicyDslParser.PolicyDslParseException e) {
            consequence = "it does not parse, and while ENABLED the PDP " + pdpConsequence(p, yaml);
        }
        log.warn("Stored policy {} ({}, metadata.id {}, effect {}, {}) is a lifecycle Policy that states an effect; "
                + "{}: {}", p.getId(), p.getName(), metadataId(raw), raw.getSpec().getEffect(), p.getStatus(),
                consequence, defect);
        return true;
    }

    /** Logs an AccessPolicy that does not parse or has malformed clauses; true when it is invalid. */
    private boolean checkAccessPolicy(Policy p, PolicyDsl raw, String yaml) {
        String effect = raw.getSpec() != null ? raw.getSpec().getEffect() : null;
        List<String> reasons = new ArrayList<>();
        try {
            reasons.addAll(PolicyDslEvaluator.defects(parser.parse(yaml)));
        } catch (PolicyDslParser.PolicyDslParseException e) {
            log.warn("Stored AccessPolicy {} ({}, metadata.id {}, effect {}, {}) does not parse; while ENABLED the "
                    + "PDP {}: {}", p.getId(), p.getName(), metadataId(raw), effect, p.getStatus(),
                    pdpConsequence(p, yaml), e.getMessage());
            return true;
        }
        if (reasons.isEmpty()) {
            return false;
        }
        String consequence = PolicyDsl.EFFECT_DENY.equals(effect)
                ? "the malformed clauses evaluate UNKNOWN, so the deny applies (fails closed)"
                : "the malformed clauses evaluate FALSE, so the policy never applies";
        log.warn("Stored AccessPolicy {} ({}, metadata.id {}, effect {}, {}) has malformed match clauses; {}: {}",
                p.getId(), p.getName(), metadataId(raw), effect, p.getStatus(), consequence, reasons);
        return true;
    }

    /**
     * What the PDP does with an ENABLED document that does not parse (POL-03, Q-842), for the log.
     *
     * @param p    the stored policy
     * @param yaml its document
     * @return the consequence, as a phrase
     */
    static String pdpConsequence(Policy p, String yaml) {
        UnparseableAccessPolicy.Classification c = UnparseableAccessPolicy.classify(p.getId(), yaml);
        return switch (c.disposition()) {
            case NOT_ACCESS_POLICY -> "ignores it (a lifecycle document)";
            case SKIPPED_ALLOW -> "skips it (an unparseable allow can only deny more)";
            case INVALID_DENY -> c.scopeReadable()
                    ? "denies every request in its readable scope (" + c.scopeText() + ") as "
                            + UnparseableAccessPolicy.REASON_POLICY_INVALID + " " + c.policyId()
                    : "denies every request as " + UnparseableAccessPolicy.REASON_POLICY_STORE_INVALID + " "
                            + c.policyId() + " until it is fixed";
        };
    }

    private static String metadataId(PolicyDsl raw) {
        return raw.getMetadata() != null ? raw.getMetadata().getId() : null;
    }
}
