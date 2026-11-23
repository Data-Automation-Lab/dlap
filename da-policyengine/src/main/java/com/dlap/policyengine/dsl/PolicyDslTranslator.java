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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

/**
 * Translates the visual builder's structured policy input into a canonical
 * {@code dlap/v1} DSL document (ADR-0031), and serializes {@link PolicyDsl}
 * back to YAML.
 *
 * <p>The DSL is the single source of truth the {@link PolicyDslEvaluator}
 * runs. Keeping this translation in the backend (rather than the frontend)
 * co-locates DSL knowledge with the parser, evaluator, and spec, and makes it
 * unit-testable. The output always satisfies {@link PolicyDslParser#parse}
 * (apiVersion / kind / metadata.id / spec.action.type / ≥1 trigger) - an
 * absent or unresolvable action is a hard error, not an invented default.
 *
 * <p>Structured input shapes:
 * <ul>
 *   <li><b>conditions</b>: {@code [{field, operator, value}, …]} - each becomes
 *       a {@code spec.match} clause; multiple clauses on the same field merge
 *       (e.g. {@code labels anyOf} + {@code labels noneOf}).</li>
 *   <li><b>action</b>: either the direct DSL shape
 *       {@code {type, requireApproval, dryRun, parameters}} or the keyed shape
 *       {@code {archive: {…}}} / {@code {addLabel: "PII"}}. A single-element
 *       {@code List} is tolerated (first entry used).</li>
 *   <li><b>triggers</b>: {@code [{type, cron?, topic?, lifecycle?}, …]};
 *       defaults to event triggers on {@code created} + {@code modified} when
 *       omitted, so newly discovered / changed assets are evaluated.</li>
 * </ul>
 */
@Component
public class PolicyDslTranslator {

    private static final String API_VERSION = "dlap/v1";
    private static final String KIND = "Policy";

    private final ObjectMapper yaml;

    public PolicyDslTranslator() {
        YAMLFactory factory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        this.yaml = new ObjectMapper(factory);
        this.yaml.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /** Thrown when structured input cannot form a valid policy (e.g. no action). */
    public static class PolicyTranslationException extends RuntimeException {
        public PolicyTranslationException(String message) {
            super(message);
        }
    }

    /** Serialize a {@link PolicyDsl} to canonical YAML. */
    public String toYaml(PolicyDsl dsl) {
        try {
            return yaml.writeValueAsString(dsl);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize policy DSL", e);
        }
    }

    /** Build a structured policy and serialize it to YAML in one step. */
    public String toYaml(String id, String name, String description, String owner,
                         boolean enabled, List<Map<String, Object>> conditions,
                         Object action, List<Map<String, Object>> triggers) {
        return toYaml(fromStructured(id, name, description, owner, enabled, conditions, action, triggers));
    }

    /** Build a canonical {@link PolicyDsl} from structured builder input. */
    public PolicyDsl fromStructured(String id, String name, String description, String owner,
                                    boolean enabled, List<Map<String, Object>> conditions,
                                    Object action, List<Map<String, Object>> triggers) {
        PolicyDsl dsl = new PolicyDsl();
        dsl.setApiVersion(API_VERSION);
        dsl.setKind(KIND);

        PolicyDsl.Metadata md = new PolicyDsl.Metadata();
        md.setId(hasText(id) ? id : slugify(name));
        md.setName(name);
        md.setDescription(description);
        md.setOwner(owner);
        md.setEnabled(enabled);
        dsl.setMetadata(md);

        PolicyDsl.Spec spec = new PolicyDsl.Spec();
        spec.setTriggers(buildTriggers(triggers));
        spec.setMatch(buildMatch(conditions));
        spec.setAction(buildAction(action));
        dsl.setSpec(spec);
        return dsl;
    }

    // ---- AccessPolicy (ADR-0040, kind: AccessPolicy) --------------------

    /** Build an AccessPolicy structured input and serialize it to YAML in one step. */
    public String toYamlAccessPolicy(String id, String name, String description, String owner,
                                     boolean enabled, List<Map<String, Object>> matchConditions,
                                     Map<String, Object> subjects, List<String> actions,
                                     Map<String, Object> condition, String effect,
                                     List<Map<String, Object>> obligations) {
        return toYaml(fromStructuredAccessPolicy(id, name, description, owner, enabled,
                matchConditions, subjects, actions, condition, effect, obligations));
    }

    /**
     * Build a canonical {@code kind: AccessPolicy} {@link PolicyDsl} from the
     * visual builder's structured access input (ADR-0040, enforcement-contract.md §4).
     *
     * <p>The asset selector reuses {@link #buildMatch} - labels are the join point
     * lifecycle policies and access policies share. The output always satisfies
     * {@link PolicyDslParser#parse}'s AccessPolicy validation (metadata.id, ≥1
     * subject clause, ≥1 action, effect allow|deny) - a missing effect or an
     * empty subject/action set is a hard error, not an invented default.
     *
     * @param subjects  {@code {tiers: [...], roles: [...]}} - at least one clause present
     * @param actions   AuthZEN action names (read/query/export/write/delete)
     * @param condition {@code {purpose: [...], consent: Boolean, region: [...]}} - optional
     * @param effect    {@code allow} or {@code deny}
     * @param obligations rows of {@code {type, labels: [...]}} - optional
     */
    public PolicyDsl fromStructuredAccessPolicy(String id, String name, String description, String owner,
                                                boolean enabled, List<Map<String, Object>> matchConditions,
                                                Map<String, Object> subjects, List<String> actions,
                                                Map<String, Object> condition, String effect,
                                                List<Map<String, Object>> obligations) {
        PolicyDsl dsl = new PolicyDsl();
        dsl.setApiVersion(API_VERSION);
        dsl.setKind(PolicyDsl.KIND_ACCESS_POLICY);

        PolicyDsl.Metadata md = new PolicyDsl.Metadata();
        md.setId(hasText(id) ? id : slugify(name));
        md.setName(name);
        md.setDescription(description);
        md.setOwner(owner);
        md.setEnabled(enabled);
        dsl.setMetadata(md);

        PolicyDsl.Spec spec = new PolicyDsl.Spec();
        spec.setSubjects(buildSubjects(subjects));
        spec.setActions(toStringList(actions));
        spec.setMatch(buildMatch(matchConditions));   // REUSE the shared match builder
        spec.setCondition(buildCondition(condition));
        spec.setEffect(normalizeEffect(effect));
        spec.setObligations(buildObligations(obligations));
        dsl.setSpec(spec);

        validateAccessPolicy(spec);
        return dsl;
    }

    /** Fail early with the same intent the parser enforces, but with builder-friendly messages. */
    private void validateAccessPolicy(PolicyDsl.Spec spec) {
        PolicyDsl.Subjects s = spec.getSubjects();
        boolean hasRoles = s != null && s.getRoles() != null && !s.getRoles().isEmpty();
        boolean hasTiers = s != null && s.getTiers() != null && !s.getTiers().isEmpty();
        if (!hasRoles && !hasTiers) {
            throw new PolicyTranslationException(
                    "access policy requires at least one subject (tiers and/or roles)");
        }
        if (spec.getActions() == null || spec.getActions().isEmpty()) {
            throw new PolicyTranslationException(
                    "access policy requires at least one action (read/query/export/write/delete)");
        }
        String effect = spec.getEffect();
        if (!(PolicyDsl.EFFECT_ALLOW.equals(effect) || PolicyDsl.EFFECT_DENY.equals(effect))) {
            throw new PolicyTranslationException(
                    "access policy effect must be '" + PolicyDsl.EFFECT_ALLOW
                            + "' or '" + PolicyDsl.EFFECT_DENY + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private PolicyDsl.Subjects buildSubjects(Map<String, Object> in) {
        if (in == null) {
            return null;
        }
        PolicyDsl.Subjects out = new PolicyDsl.Subjects();
        List<String> tiers = toList(in.get("tiers"));
        List<String> roles = toList(in.get("roles"));
        out.setTiers(tiers.isEmpty() ? null : tiers);
        out.setRoles(roles.isEmpty() ? null : roles);
        return out;
    }

    private PolicyDsl.Condition buildCondition(Map<String, Object> in) {
        if (in == null || in.isEmpty()) {
            return null;
        }
        PolicyDsl.Condition out = new PolicyDsl.Condition();
        List<String> purpose = toList(in.get("purpose"));
        if (!purpose.isEmpty()) {
            out.setPurpose(anyOf(purpose));
        }
        List<String> region = toList(in.get("region"));
        if (!region.isEmpty()) {
            out.setRegion(anyOf(region));
        }
        Object consent = in.get("consent");
        if (consent != null) {
            out.setConsent(bool(consent));
        }
        // All clauses absent → emit no condition block at all.
        if (out.getPurpose() == null && out.getRegion() == null && out.getConsent() == null) {
            return null;
        }
        return out;
    }

    private PolicyDsl.AnyOf anyOf(List<String> vals) {
        PolicyDsl.AnyOf a = new PolicyDsl.AnyOf();
        a.setAnyOf(vals);
        return a;
    }

    private List<PolicyDsl.Obligation> buildObligations(List<Map<String, Object>> in) {
        if (in == null || in.isEmpty()) {
            return null;
        }
        List<PolicyDsl.Obligation> out = new ArrayList<>();
        for (Map<String, Object> m : in) {
            if (m == null) {
                continue;
            }
            String type = str(m.get("type"));
            if (!hasText(type)) {
                continue;
            }
            PolicyDsl.Obligation o = new PolicyDsl.Obligation();
            o.setType(type.trim());
            List<String> labels = toList(m.get("labels"));
            o.setLabels(labels.isEmpty() ? null : labels);
            out.add(o);
        }
        return out.isEmpty() ? null : out;
    }

    private String normalizeEffect(String effect) {
        if (!hasText(effect)) {
            return null;   // let validateAccessPolicy reject it with a clear message
        }
        return effect.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> toStringList(List<String> in) {
        if (in == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (hasText(s)) {
                out.add(s.trim());
            }
        }
        return out;
    }

    // ---- triggers -------------------------------------------------------

    private List<PolicyDsl.Trigger> buildTriggers(List<Map<String, Object>> in) {
        List<PolicyDsl.Trigger> out = new ArrayList<>();
        if (in != null) {
            for (Map<String, Object> m : in) {
                if (m == null) {
                    continue;
                }
                PolicyDsl.Trigger t = new PolicyDsl.Trigger();
                t.setType(str(m.get("type")));
                t.setCron(str(m.get("cron")));
                t.setTopic(str(m.get("topic")));
                t.setLifecycle(str(m.get("lifecycle")));
                if (hasText(t.getType())) {
                    out.add(t);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(eventTrigger("created"));
            out.add(eventTrigger("modified"));
        }
        return out;
    }

    private PolicyDsl.Trigger eventTrigger(String lifecycle) {
        PolicyDsl.Trigger t = new PolicyDsl.Trigger();
        t.setType("event");
        t.setLifecycle(lifecycle);
        return t;
    }

    // ---- match ----------------------------------------------------------

    private Map<String, Object> buildMatch(List<Map<String, Object>> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return null;
        }
        Map<String, Object> match = new LinkedHashMap<>();
        for (Map<String, Object> c : conditions) {
            if (c == null) {
                continue;
            }
            String field = str(c.get("field"));
            if (!hasText(field)) {
                continue;
            }
            applyClause(match, field, normalizeOp(str(c.get("operator")), field), c.get("value"));
        }
        return match.isEmpty() ? null : match;
    }

    private void applyClause(Map<String, Object> match, String field, String op, Object rawVal) {
        switch (op) {
            case "anyOf" -> mergeSetClause(match, field, "anyOf", toList(rawVal));
            case "noneOf" -> mergeSetClause(match, field, "noneOf", toList(rawVal));
            case "allOf" -> mergeSetClause(match, field, "allOf", toList(rawVal));
            // Comparator/duration bounds merge into one clause map so a range
            // (gte + lte) or a window (olderThan + youngerThan) survives instead
            // of the second bound overwriting the first.
            case "olderThan" -> mergeMapClause(match, field, "olderThan", str(rawVal));
            case "youngerThan" -> mergeMapClause(match, field, "youngerThan", str(rawVal));
            case "gte" -> mergeMapClause(match, field, "gte", toNumber(rawVal));
            case "lte" -> mergeMapClause(match, field, "lte", toNumber(rawVal));
            case "numEq" -> mergeMapClause(match, field, "eq", toNumber(rawVal));
            case "matches" -> match.put(field, str(rawVal));
            default -> {   // "equals" and any unknown operator
                if (isSetField(field)) {
                    mergeSetClause(match, field, "anyOf", toList(rawVal));
                } else {
                    match.put(field, str(rawVal));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeSetClause(Map<String, Object> match, String field, String key, List<String> vals) {
        Object existing = match.get(field);
        Map<String, Object> clause;
        if (existing instanceof Map) {
            clause = (Map<String, Object>) existing;
        } else {
            clause = new LinkedHashMap<>();
            match.put(field, clause);
        }
        clause.put(key, vals);
    }

    /** Add a single-valued sub-clause (gte/lte/olderThan/…) to a field, preserving
     *  any bound already set on that field so ranges/windows aren't overwritten. */
    @SuppressWarnings("unchecked")
    private void mergeMapClause(Map<String, Object> match, String field, String key, Object value) {
        Object existing = match.get(field);
        Map<String, Object> clause;
        if (existing instanceof Map) {
            clause = (Map<String, Object>) existing;
        } else {
            clause = new LinkedHashMap<>();
            match.put(field, clause);
        }
        clause.put(key, value);
    }

    // ---- action ---------------------------------------------------------

    @SuppressWarnings("unchecked")
    private PolicyDsl.Action buildAction(Object action) {
        Object a = action;
        if (a instanceof List<?> list) {
            a = list.isEmpty() ? null : list.get(0);   // v1: one action per policy
        }
        PolicyDsl.Action out = new PolicyDsl.Action();
        if (a instanceof Map<?, ?> raw) {
            Map<String, Object> m = (Map<String, Object>) raw;
            if (hasText(str(m.get("type")))) {
                // direct DSL action shape
                out.setType(str(m.get("type")));
                out.setRequireApproval(bool(m.get("requireApproval")));
                out.setDryRun(bool(m.get("dryRun")));
                Object params = m.get("parameters");
                if (params instanceof Map) {
                    out.setParameters((Map<String, Object>) params);
                } else {
                    Map<String, Object> rest = new LinkedHashMap<>(m);
                    rest.remove("type");
                    rest.remove("requireApproval");
                    rest.remove("dryRun");
                    if (!rest.isEmpty()) {
                        out.setParameters(rest);
                    }
                }
            } else if (!m.isEmpty()) {
                // keyed-by-type shape: {archive: {...}} or {addLabel: "PII"}
                Map.Entry<String, Object> e = m.entrySet().iterator().next();
                out.setType(e.getKey());
                Object v = e.getValue();
                if (v instanceof Map) {
                    out.setParameters((Map<String, Object>) v);
                } else if (v != null) {
                    out.setParameters(mapOf(defaultParamKey(e.getKey()), v));
                }
            }
        }
        if (!hasText(out.getType())) {
            throw new PolicyTranslationException(
                    "policy requires an action (spec.action.type) - none was provided");
        }
        return out;
    }

    /** Best-effort parameter key when a keyed action carries a bare scalar. */
    private String defaultParamKey(String actionType) {
        return switch (actionType) {
            case "addLabel", "removeLabel" -> "label";
            case "setRetention" -> "retentionDays";
            default -> "value";
        };
    }

    // ---- helpers --------------------------------------------------------

    /** Fields whose values are sets (membership semantics), not scalar equality. */
    private boolean isSetField(String field) {
        return "labels".equals(field);
    }

    private boolean isNumericField(String field) {
        return "sizeBytes".equals(field);
    }

    /** Collapse the many synonyms a builder might emit to a canonical operator. */
    private String normalizeOp(String op, String field) {
        if (!hasText(op)) {
            return "equals";
        }
        String o = op.trim().toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
        return switch (o) {
            case "anyof", "in", "oneof", "includesany", "containsany" -> "anyOf";
            case "noneof", "notin", "excludes", "notanyof" -> "noneOf";
            case "allof", "includesall", "hasall", "containsall" -> "allOf";
            case "olderthan", "notaccessedsince", "stalefor" -> "olderThan";
            case "youngerthan", "accessedsince", "newerthan" -> "youngerThan";
            case "gte", ">=", "greaterthanorequal", "greaterthan", ">", "atleast" -> "gte";
            case "lte", "<=", "lessthanorequal", "lessthan", "<", "atmost" -> "lte";
            case "matches", "regex", "like", "matchesregex" -> "matches";
            case "eq", "equals", "equal", "is", "=", "==" ->
                    isNumericField(field) ? "numEq" : "equals";
            default -> "equals";
        };
    }

    private static String slugify(String name) {
        if (!hasText(name)) {
            return "policy";
        }
        String s = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "policy" : s;
    }

    private static List<String> toList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && hasText(o.toString())) {
                    out.add(o.toString().trim());
                }
            }
        } else if (v != null) {
            for (String part : v.toString().split(",")) {
                if (hasText(part)) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    private static Object toNumber(Object v) {
        if (v instanceof Number n) {
            return n;
        }
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        try {
            if (s.contains(".")) {
                return Double.parseDouble(s);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s;   // defensive: let the evaluator deal with a non-numeric bound
        }
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b ? b : Boolean.parseBoolean(str(v));
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
