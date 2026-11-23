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

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.dsl.PolicyDsl;
import com.dlap.policyengine.dsl.PolicyDslEvaluator;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dsl.UnparseableAccessPolicy;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyKind;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * Deterministic AuthZEN Policy Decision Point (ADR-0040,
 * enforcement-contract.md §4/§5). Evaluates an access request against the set of
 * ENABLED {@code kind: AccessPolicy} documents entirely in memory - no LLM, no
 * network call in the verdict path.
 *
 * <p><b>Policy loading / caching.</b> Mirrors {@link
 * com.dlap.policyengine.dsl.LifecyclePolicyConsumer}'s repository read: ENABLED
 * policies are read from {@link PolicyRepository}, their {@code policyDocument}
 * YAML parsed by {@link PolicyDslParser}, and only {@code kind: AccessPolicy}
 * documents kept. Because the verdict hot path (p99 ≤10ms) cannot afford a DB
 * round-trip per request, the parsed set is cached in memory and refreshed by
 * {@link AccessPolicyRefreshListener} (on startup, then periodically) plus lazily
 * on first use. {@link #refresh()} is idempotent and thread-safe.
 *
 * <p><b>Combining algorithm</b> (fixed): a request matches a policy when its
 * subject, action, asset ({@link PolicyDslEvaluator#matches}) and condition
 * clauses all hold. Explicit {@code deny} overrides any {@code allow}; the
 * obligations of all contributing allows union (most restrictive); if nothing
 * matches, the configured {@code dlap.pdp.default-decision} applies (ships
 * {@code deny}).
 *
 * <p><b>Unparseable documents fail closed</b> (POL-03, decision D43 on Q-842). An ENABLED document
 * the parser rejects is classified by {@link UnparseableAccessPolicy}: a lifecycle
 * {@code kind: Policy} is ignored, an access document whose effect reads {@code allow} is skipped
 * (skipping an allow can only deny more), and every other one is kept as an <i>invalid deny</i>. An
 * invalid deny whose subject or resource selector can be read denies every request inside that
 * scope, with reason {@code policy-invalid} and its policy id; one whose scope cannot be read denies
 * every request, with reason {@code policy-store-invalid}, until the document is fixed. Both apply
 * in observe mode. The gauge {@value #INVALID_DENY_GAUGE}{@code {scope}} counts them and each
 * refresh logs them in one WARN.
 *
 * <p><b>Coverage freshness</b> (POL-03, Q-837). The snapshot carries the catalog's
 * {@code readCoverageObservedAt}, so a stale {@code FULL} counts as unknown coverage here as on the
 * sweep. A {@code FULL} without an observation time is taken at face value and warned about once
 * per asset per refresh, for at most {@value #COVERAGE_WARN_CAP} assets per refresh.
 */
@Service
@Slf4j
public class AccessDecisionService {

    private static final String DECISION_ID_PREFIX = "dec-";
    /** The parse error shown for an ENABLED DSL row whose document is null or blank (Q-871). */
    private static final String BLANK_DOCUMENT = "the policy document is missing or blank";
    private static final String REASON_DEFAULT_ALLOW = "no matching policy; default-decision allow";
    private static final String REASON_DEFAULT_DENY = "no matching policy; default-decision deny";

    /** Observation / metric name for the PDP verdict hot path. */
    private static final String OBSERVATION_NAME = "dlap.pdp.evaluation";
    /** Counter incremented once per verdict, tagged by outcome. */
    private static final String DECISIONS_COUNTER = "dlap.pdp.decisions.total";
    /** Timer wrapping the in-memory evaluation. */
    private static final String EVALUATION_TIMER = "dlap.pdp.evaluation.duration";
    /** Gauge of the invalid deny policies of the latest refresh, tagged {@code scope}. */
    public static final String INVALID_DENY_GAUGE = "dlap.pdp.policies.invalid.deny";
    /** {@code scope} tag: an invalid deny whose subject or resource selector was read. */
    public static final String SCOPE_READABLE = "readable";
    /** {@code scope} tag: an invalid deny whose scope could not be read (denies everything). */
    public static final String SCOPE_UNREADABLE = "unreadable";
    /** Most assets warned about per refresh for a FULL coverage without an observation time. */
    static final int COVERAGE_WARN_CAP = 1000;

    private final PolicyRepository policyRepository;
    private final PolicyDslParser parser;
    private final PolicyDslEvaluator evaluator;
    private final IAssetSnapshotResolver resolver;

    /** {@code dlap.pdp.default-decision}: {@code deny} (shipped) or {@code allow}. */
    private final boolean defaultAllow;

    /** Micrometer registry for verdict counters/timers (actuator starter). */
    private final MeterRegistry meterRegistry;
    /** Observation registry - yields spans + log context once a tracer is wired. */
    private final ObservationRegistry observationRegistry;
    /** Timer over the pure in-memory evaluation (no I/O added to the hot path). */
    private final Timer evaluationTimer;

    /** Cached parsed AccessPolicy set; refreshed on policy-change events. */
    private final AtomicReference<PolicySet> cache = new AtomicReference<>();
    /** Invalid deny policies with a readable scope, as of the latest refresh. */
    private final AtomicLong invalidScoped = new AtomicLong();
    /** Invalid deny policies whose scope cannot be read, as of the latest refresh. */
    private final AtomicLong invalidUnscoped = new AtomicLong();
    /** Assets warned about since the latest refresh for a FULL without an observation time. */
    private final AtomicReference<Set<String>> coverageWarned =
            new AtomicReference<>(new BoundedKeySet(COVERAGE_WARN_CAP));

    public AccessDecisionService(PolicyRepository policyRepository,
                                 PolicyDslParser parser,
                                 PolicyDslEvaluator evaluator,
                                 IAssetSnapshotResolver resolver,
                                 MeterRegistry meterRegistry,
                                 ObservationRegistry observationRegistry,
                                 @Value("${dlap.pdp.default-decision:deny}") String defaultDecision) {
        this.policyRepository = policyRepository;
        this.parser = parser;
        this.evaluator = evaluator;
        this.resolver = resolver;
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
        this.evaluationTimer = Timer.builder(EVALUATION_TIMER)
                .description("PDP in-memory access evaluation duration")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.defaultAllow = AccessDecision.EFFECT_ALLOW.equalsIgnoreCase(defaultDecision);
        Gauge.builder(INVALID_DENY_GAUGE, invalidScoped, AtomicLong::doubleValue)
                .tag("scope", SCOPE_READABLE)
                .description("ENABLED AccessPolicies that do not parse, kept as a deny of their readable scope")
                .register(meterRegistry);
        Gauge.builder(INVALID_DENY_GAUGE, invalidUnscoped, AtomicLong::doubleValue)
                .tag("scope", SCOPE_UNREADABLE)
                .description("ENABLED policy documents that do not parse and whose scope cannot be read; "
                        + "each one denies every request")
                .register(meterRegistry);
        if (this.defaultAllow) {
            log.warn("PDP default-decision is 'allow' (observe/brownfield mode) - "
                    + "unmatched requests are permitted");
        }
    }

    /**
     * Re-parse the ENABLED AccessPolicy set from the repository (idempotent). Documents that do not
     * parse are classified ({@link UnparseableAccessPolicy}); the invalid denies are kept, counted in
     * the gauge and, with the skipped allows, logged in one WARN per refresh. An ENABLED row of kind
     * {@link PolicyKind#DSL} with a null or blank document is an invalid deny with no readable scope
     * (POL-04, Q-871); a {@link PolicyKind#LEGACY_RULE} row without a document is skipped.
     */
    public void refresh() {
        List<CachedAccessPolicy> loaded = new ArrayList<>();
        List<InvalidDeny> invalid = new ArrayList<>();
        List<String> skippedAllows = new ArrayList<>();
        for (Policy p : policyRepository.findByStatus(PolicyStatus.ENABLED)) {
            String yaml = p.getPolicyDocument();
            if (yaml == null || yaml.isBlank()) {
                if (p.getPolicyKind() == PolicyKind.DSL) {
                    // Q-871: an ENABLED DSL row must have a document. One that lost it (a direct
                    // edit) cannot be judged, so it denies every request until it is fixed.
                    invalid.add(new InvalidDeny(p.getId(),
                            UnparseableAccessPolicy.classify(p.getId(), null), BLANK_DOCUMENT));
                }
                // A LEGACY_RULE row never had a document: it is not an access policy.
                continue;
            }
            try {
                PolicyDsl dsl = parser.parse(yaml);
                if (PolicyDsl.KIND_ACCESS_POLICY.equals(dsl.getKind())) {
                    loaded.add(new CachedAccessPolicy(p.getId(), dsl));
                }
            } catch (PolicyDslParser.PolicyDslParseException e) {
                UnparseableAccessPolicy.Classification c = UnparseableAccessPolicy.classify(p.getId(), yaml);
                switch (c.disposition()) {
                    case INVALID_DENY -> invalid.add(new InvalidDeny(p.getId(), c, e.getMessage()));
                    case SKIPPED_ALLOW -> skippedAllows.add(c.policyId() + " (row " + p.getId() + "): "
                            + e.getMessage());
                    case NOT_ACCESS_POLICY -> log.debug("Policy {} is a lifecycle document that does not "
                            + "parse; the PDP ignores it", p.getId());
                }
            }
        }
        cache.set(new PolicySet(List.copyOf(loaded), List.copyOf(invalid)));
        coverageWarned.set(new BoundedKeySet(COVERAGE_WARN_CAP));
        long unscoped = invalid.stream().filter(i -> !i.classification().scopeReadable()).count();
        invalidUnscoped.set(unscoped);
        invalidScoped.set(invalid.size() - unscoped);
        log.info("Loaded {} ENABLED AccessPolicy documents into the PDP", loaded.size());
        if (!invalid.isEmpty() || !skippedAllows.isEmpty()) {
            List<String> denies = new ArrayList<>();
            for (InvalidDeny i : invalid) {
                denies.add(i.classification().policyId() + " (row " + i.rowId() + ", scope "
                        + i.classification().scopeText() + "): " + i.parseError());
            }
            log.warn("PDP policy store has documents that do not parse: {} invalid deny policies fail closed "
                    + "({} with an unreadable scope deny every request): {}; {} unparseable allow policies "
                    + "are skipped: {}", invalid.size(), unscoped, denies, skippedAllows.size(), skippedAllows);
        }
    }

    private PolicySet policies() {
        PolicySet current = cache.get();
        if (current == null) {
            refresh();
            current = cache.get();
        }
        return current;
    }

    /**
     * Evaluate one access request. Returns the deterministic decision together
     * with the resolved resource (so the caller can audit without re-resolving).
     * Never throws for a well-formed request; an unresolvable resource is
     * evaluated against its supplied properties.
     */
    public EvaluationResult evaluate(AuthZenEvaluationRequest request) {
        // Observation yields a Micrometer timer sample now and a trace span
        // automatically once a tracer bridge is wired in a deployment. The verdict
        // itself is computed by doEvaluate; we only observe it - no I/O, no change
        // to latency semantics or the cache path.
        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry);
        return observation.observe(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            EvaluationResult result = doEvaluate(request);
            sample.stop(evaluationTimer);

            AccessDecision d = result.decision();
            String decision = d.allow() ? AccessDecision.EFFECT_ALLOW : AccessDecision.EFFECT_DENY;
            String defaultApplied = Boolean.toString(d.defaultApplied());
            observation.lowCardinalityKeyValue("decision", decision)
                    .lowCardinalityKeyValue("defaultApplied", defaultApplied);
            meterRegistry.counter(DECISIONS_COUNTER, "decision", decision, "default", defaultApplied)
                    .increment();
            return result;
        });
    }

    private EvaluationResult doEvaluate(AuthZenEvaluationRequest request) {
        long start = System.nanoTime();
        String decisionId = DECISION_ID_PREFIX + UUID.randomUUID();

        Set<String> subjectRoles = extractRoles(request);
        Set<String> subjectTiers = tiersForRoles(subjectRoles, extractDeclaredTier(request));
        String action = request.getAction() != null ? request.getAction().getName() : null;
        Map<String, Object> context = request.getContext();
        IAssetSnapshotResolver.ResolvedResource resolved =
                resolver.resolve(request.getResource());
        PolicySet set = policies();
        Set<String> warned = coverageWarned.get();

        boolean anyAllow = false;
        boolean anyDeny = false;
        List<String> allowPolicyIds = new ArrayList<>();
        List<String> denyPolicyIds = new ArrayList<>();
        Set<AccessDecision.PolicyDslObligation> obligations = new LinkedHashSet<>();

        for (CachedAccessPolicy cached : set.valid()) {
            PolicyDsl.Spec spec = cached.dsl().getSpec();
            if (!subjectMatches(spec, subjectRoles, subjectTiers)) {
                continue;
            }
            if (!actionMatches(spec, action)) {
                continue;
            }
            if (!resourceMatches(cached.dsl(), resolved.snapshot(), warned)) {
                continue;
            }
            if (!conditionMatches(spec.getCondition(), context)) {
                continue;
            }
            String policyId = cached.dsl().getMetadata().getId();
            if (AccessDecision.EFFECT_DENY.equals(spec.getEffect())) {
                anyDeny = true;
                denyPolicyIds.add(policyId);
            } else {
                anyAllow = true;
                allowPolicyIds.add(policyId);
                obligations.addAll(toObligations(spec.getObligations()));
            }
        }

        // Invalid deny policies (Q-842): an unreadable scope covers every request; a readable one
        // covers the requests its subject and resource selectors match.
        List<String> storeInvalidIds = new ArrayList<>();
        List<String> invalidIds = new ArrayList<>();
        for (InvalidDeny invalid : set.invalidDenies()) {
            UnparseableAccessPolicy.Classification c = invalid.classification();
            if (!c.scopeReadable()) {
                storeInvalidIds.add(c.policyId());
            } else if ((!c.subjectsReadable() || subjectMatches(c.scope().getSpec(), subjectRoles, subjectTiers))
                    && (!c.matchReadable() || resourceMatches(c.scope(), resolved.snapshot(), warned))) {
                invalidIds.add(c.policyId());
            }
        }

        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        AccessDecision decision;
        if (!storeInvalidIds.isEmpty() || !invalidIds.isEmpty() || anyDeny) {
            // Explicit deny overrides any allow; an invalid deny is a deny.
            List<String> reasons = new ArrayList<>();
            if (!storeInvalidIds.isEmpty()) {
                reasons.add(UnparseableAccessPolicy.REASON_POLICY_STORE_INVALID + ": "
                        + String.join(", ", storeInvalidIds));
            }
            if (!invalidIds.isEmpty()) {
                reasons.add(UnparseableAccessPolicy.REASON_POLICY_INVALID + ": " + String.join(", ", invalidIds));
            }
            if (anyDeny) {
                reasons.add("denied by " + String.join(", ", denyPolicyIds));
            }
            List<String> ids = new ArrayList<>(storeInvalidIds);
            ids.addAll(invalidIds);
            ids.addAll(denyPolicyIds);
            decision = new AccessDecision(decisionId, false, String.join("; ", reasons),
                    ids, List.of(), false, latencyMs);
        } else if (anyAllow) {
            decision = new AccessDecision(decisionId, true,
                    "allowed by " + String.join(", ", allowPolicyIds),
                    allowPolicyIds, new ArrayList<>(obligations), false, latencyMs);
        } else {
            // No match -> configured default.
            decision = new AccessDecision(decisionId, defaultAllow,
                    defaultAllow ? REASON_DEFAULT_ALLOW : REASON_DEFAULT_DENY,
                    List.of(), List.of(), true, latencyMs);
        }
        return new EvaluationResult(decision, resolved);
    }

    /**
     * Decision plus the resolved resource that produced it - so the caller can
     * emit the decision-log event (§6) without a second resource resolution.
     */
    public record EvaluationResult(
            AccessDecision decision,
            IAssetSnapshotResolver.ResolvedResource resolved) { }

    /** Whether the policy's match block applies to the resource, warning with the refresh's set. */
    private boolean resourceMatches(PolicyDsl dsl, Map<String, Object> snapshot, Set<String> warned) {
        return PolicyDslEvaluator.applies(dsl, evaluator.evaluate(dsl, snapshot, evaluator.optionsFor(dsl, warned)));
    }

    /* ----- subject matching ----- */

    /**
     * Subject-match. Within {@code roles} the members are OR'd; within
     * {@code tiers} the members are OR'd; the two clauses stand alone (a subject
     * matches if it satisfies EITHER present clause) - they are two alternative
     * ways to name the same principal set (contract §4 example).
     */
    private boolean subjectMatches(PolicyDsl.Spec spec, Set<String> subjectRoles,
                                   Set<String> subjectTiers) {
        PolicyDsl.Subjects subjects = spec.getSubjects();
        if (subjects == null) {
            return false;
        }
        boolean roleClausePresent = subjects.getRoles() != null && !subjects.getRoles().isEmpty();
        boolean tierClausePresent = subjects.getTiers() != null && !subjects.getTiers().isEmpty();
        if (roleClausePresent && anyMatch(subjects.getRoles(), subjectRoles)) {
            return true;
        }
        if (tierClausePresent && anyMatch(subjects.getTiers(), subjectTiers)) {
            return true;
        }
        return false;
    }

    private boolean actionMatches(PolicyDsl.Spec spec, String action) {
        if (action == null || spec.getActions() == null) {
            return false;
        }
        return spec.getActions().stream().anyMatch(a -> a.equalsIgnoreCase(action));
    }

    /* ----- condition matching ----- */

    /** All present condition clauses must hold. A null condition matches. */
    private boolean conditionMatches(PolicyDsl.Condition condition, Map<String, Object> context) {
        if (condition == null) {
            return true;
        }
        Map<String, Object> ctx = context != null ? context : Map.of();
        if (condition.getPurpose() != null && condition.getPurpose().getAnyOf() != null) {
            if (!anyOfContains(condition.getPurpose().getAnyOf(), asString(ctx.get("purpose")))) {
                return false;
            }
        }
        if (condition.getConsent() != null) {
            if (!condition.getConsent().equals(asBoolean(ctx.get("consent")))) {
                return false;
            }
        }
        if (condition.getRegion() != null && condition.getRegion().getAnyOf() != null) {
            if (!anyOfContains(condition.getRegion().getAnyOf(), asString(ctx.get("region")))) {
                return false;
            }
        }
        return true;
    }

    /* ----- helpers ----- */

    @SuppressWarnings("unchecked")
    private static Set<String> extractRoles(AuthZenEvaluationRequest request) {
        Set<String> roles = new LinkedHashSet<>();
        if (request.getSubject() != null && request.getSubject().getProperties() != null) {
            Object r = request.getSubject().getProperties().get("roles");
            if (r instanceof Collection<?> c) {
                for (Object o : c) {
                    if (o != null) {
                        roles.add(o.toString());
                    }
                }
            } else if (r instanceof String s && !s.isBlank()) {
                roles.add(s);
            }
        }
        return roles;
    }

    private static String extractDeclaredTier(AuthZenEvaluationRequest request) {
        if (request.getSubject() != null && request.getSubject().getProperties() != null) {
            return asString(request.getSubject().getProperties().get("tier"));
        }
        return null;
    }

    /**
     * Derive the ADR-0032 tier set: the declared {@code tier} plus every tier
     * implied by the subject's realm roles ({@link DlapConstants.RoleTier}).
     */
    private static Set<String> tiersForRoles(Set<String> roles, String declaredTier) {
        Set<String> tiers = new LinkedHashSet<>();
        if (declaredTier != null && !declaredTier.isBlank()) {
            tiers.add(declaredTier.toUpperCase(Locale.ROOT));
        }
        for (String role : roles) {
            if (DlapConstants.RoleTier.EXECUTIVE.contains(role)) {
                tiers.add("EXECUTIVE");
            }
            if (DlapConstants.RoleTier.LEADERSHIP.contains(role)) {
                tiers.add("LEADERSHIP");
            }
            if (DlapConstants.RoleTier.PRACTITIONER.contains(role)) {
                tiers.add("PRACTITIONER");
            }
            if (DlapConstants.RoleTier.READER.contains(role)) {
                tiers.add("READER");
            }
        }
        return tiers;
    }

    private static boolean anyMatch(Collection<String> declared, Set<String> subjectValues) {
        for (String d : declared) {
            if (d != null && subjectValues.contains(d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyOfContains(Collection<String> anyOf, String actual) {
        if (actual == null) {
            return false;
        }
        return anyOf.stream().anyMatch(actual::equals);
    }

    private static List<AccessDecision.PolicyDslObligation> toObligations(
            List<PolicyDsl.Obligation> obligations) {
        if (obligations == null) {
            return List.of();
        }
        List<AccessDecision.PolicyDslObligation> out = new ArrayList<>();
        for (PolicyDsl.Obligation o : obligations) {
            if (o != null && o.getType() != null) {
                out.add(new AccessDecision.PolicyDslObligation(
                        o.getType(),
                        o.getLabels() == null ? List.of() : List.copyOf(o.getLabels())));
            }
        }
        return out;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return null;
    }

    /**
     * One ENABLED {@code kind: AccessPolicy} document, parsed once at cache
     * refresh so the verdict path never re-parses YAML.
     */
    private record CachedAccessPolicy(java.util.UUID id, PolicyDsl dsl) {}

    /**
     * One ENABLED document that does not parse and is kept as an invalid deny (Q-842).
     *
     * @param rowId          stored row id
     * @param classification what could be read of it
     * @param parseError     why the parser rejected it
     */
    private record InvalidDeny(UUID rowId, UnparseableAccessPolicy.Classification classification,
            String parseError) {}

    /**
     * The cached policy set of one refresh.
     *
     * @param valid         the parsed AccessPolicies
     * @param invalidDenies the documents kept as invalid denies
     */
    private record PolicySet(List<CachedAccessPolicy> valid, List<InvalidDeny> invalidDenies) {}

    /**
     * A concurrent set that takes at most {@code cap} keys; an add beyond the cap is refused, so the
     * evaluator logs nothing for it. Bounds the PDP's per-refresh coverage warnings.
     */
    static final class BoundedKeySet extends AbstractSet<String> {
        private final Set<String> keys = ConcurrentHashMap.newKeySet();
        private final int cap;

        /**
         * Creates an empty set.
         *
         * @param cap the most keys it takes
         */
        BoundedKeySet(int cap) {
            this.cap = cap;
        }

        @Override
        public boolean add(String key) {
            return keys.size() < cap && keys.add(key);
        }

        @Override
        public boolean contains(Object key) {
            return keys.contains(key);
        }

        @Override
        public Iterator<String> iterator() {
            return keys.iterator();
        }

        @Override
        public int size() {
            return keys.size();
        }
    }
}
