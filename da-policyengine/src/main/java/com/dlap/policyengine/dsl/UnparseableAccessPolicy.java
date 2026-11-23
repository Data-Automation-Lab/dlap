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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * What the PDP keeps of a stored document that {@link PolicyDslParser#parse} rejects (POL-03,
 * decision D43 on Q-842). Before POL-03 such a document was skipped, so a deny that did not parse
 * failed open. The document is read again as a plain YAML tree, part by part, and classified:
 *
 * <ul>
 *   <li>{@link Disposition#NOT_ACCESS_POLICY}: its {@code kind} reads {@code Policy} (any case), a
 *       lifecycle document, and {@code spec.effect} is absent. The PDP ignores it; the lifecycle
 *       paths and the start-up check report it. A document of that kind that states an effect is
 *       judged by the effect, so a deny whose kind alone is wrong still denies (POL-04, Q-870).</li>
 *   <li>{@link Disposition#SKIPPED_ALLOW}: its {@code spec.effect} reads {@code allow} (any case,
 *       trimmed). It is skipped: skipping an allow can only deny more, never less.</li>
 *   <li>{@link Disposition#INVALID_DENY}: every other document, that is one whose effect reads
 *       {@code deny} or cannot be read at all (missing, not text, another value, or a document that
 *       is not YAML), with the one exception above. It is kept as an invalid deny with the scope
 *       that can be read.</li>
 * </ul>
 *
 * <p><b>Scope.</b> The scope of an invalid deny is its subject selector and its resource selector.
 * The subject selector is readable when {@code spec.subjects} is a map whose {@code roles} and
 * {@code tiers} (each optional) are lists of non-blank plain values and at least one is non-empty;
 * a selector of any other shape is unreadable as a whole, because a malformed half could name
 * anyone. The resource selector is readable when {@code spec.match} is a non-empty map; its clauses
 * are then evaluated as in a deny AccessPolicy, so a malformed clause or an unknown value applies.
 * An unreadable selector covers everything. The actions, the condition and
 * {@code metadata.enabled} are not read: they could only narrow a deny whose document is broken.
 * With neither selector readable, the invalid deny denies every request (reason
 * {@value #REASON_POLICY_STORE_INVALID}) until the document is fixed.
 *
 * <p>A blank or null document is passed here by the PDP for an ENABLED {@code DSL} row (POL-04,
 * Q-871); it classifies as an invalid deny with no readable scope, named by its row id.
 *
 * <p>The policy id is the document's {@code metadata.id} when it reads as non-blank text, else the
 * stored row id.
 */
public final class UnparseableAccessPolicy {

    /** Reason prefix of a request denied by an invalid deny inside its readable scope. */
    public static final String REASON_POLICY_INVALID = "policy-invalid";
    /** Reason prefix of a request denied by an invalid deny whose scope cannot be read. */
    public static final String REASON_POLICY_STORE_INVALID = "policy-store-invalid";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };

    /** What the PDP does with the document. */
    public enum Disposition {
        /** A lifecycle {@code kind: Policy} document; not the PDP's concern. */
        NOT_ACCESS_POLICY,
        /** An access document whose effect reads allow; skipped. */
        SKIPPED_ALLOW,
        /** Kept as an invalid deny. */
        INVALID_DENY
    }

    /**
     * The classification of one stored document.
     *
     * @param disposition      what the PDP does with it
     * @param policyId         {@code metadata.id} when readable, else the row id
     * @param scope            for an invalid deny, an AccessPolicy with effect deny holding the
     *                         readable selectors (subjects and match, each {@code null} when
     *                         unreadable); else {@code null}
     * @param subjectsReadable whether the subject selector could be read
     * @param matchReadable    whether the resource selector could be read
     */
    public record Classification(Disposition disposition, String policyId, PolicyDsl scope,
            boolean subjectsReadable, boolean matchReadable) {

        /**
         * Whether any selector narrows the invalid deny.
         *
         * @return true when the subject or the resource selector was read
         */
        public boolean scopeReadable() {
            return subjectsReadable || matchReadable;
        }

        /**
         * A short description of the readable scope, for logs.
         *
         * @return {@code subjects+resources}, {@code subjects}, {@code resources} or {@code unreadable}
         */
        public String scopeText() {
            if (subjectsReadable && matchReadable) {
                return "subjects+resources";
            }
            if (subjectsReadable) {
                return "subjects";
            }
            return matchReadable ? "resources" : "unreadable";
        }
    }

    private UnparseableAccessPolicy() {
    }

    /**
     * Classifies a stored document the strict parser rejected.
     *
     * @param rowId the stored row id; used as the policy id when {@code metadata.id} cannot be read
     * @param yaml  the stored document; may be anything, including text that is not YAML
     * @return the classification; never {@code null}
     */
    public static Classification classify(UUID rowId, String yaml) {
        JsonNode root = readTree(yaml);
        String fallbackId = rowId != null ? rowId.toString() : "(no row id)";
        if (root == null || !root.isObject()) {
            return new Classification(Disposition.INVALID_DENY, fallbackId, scope(fallbackId, null, null),
                    false, false);
        }
        String id = text(root.path("metadata").path("id"));
        String policyId = id != null ? id : fallbackId;
        String kind = text(root.get("kind"));
        JsonNode spec = root.get("spec");
        boolean specIsMap = spec != null && spec.isObject();
        JsonNode effectNode = specIsMap ? spec.get("effect") : null;
        // Q-870: a lifecycle kind is ignored only when the document states no effect. Any effect
        // (deny, allow, a blank or unreadable value) means the author meant an access decision, so
        // the document is judged by its effect whatever its kind says.
        boolean effectAbsent = effectNode == null || effectNode.isNull();
        if (effectAbsent && kind != null && PolicyDsl.KIND_POLICY.equalsIgnoreCase(kind)) {
            return new Classification(Disposition.NOT_ACCESS_POLICY, policyId, null, false, false);
        }
        String effect = text(effectNode);
        if (effect != null && PolicyDsl.EFFECT_ALLOW.equals(effect.toLowerCase(Locale.ROOT))) {
            return new Classification(Disposition.SKIPPED_ALLOW, policyId, null, false, false);
        }
        PolicyDsl.Subjects subjects = specIsMap ? subjects(spec.get("subjects")) : null;
        Map<String, Object> match = specIsMap ? match(spec.get("match")) : null;
        return new Classification(Disposition.INVALID_DENY, policyId, scope(policyId, subjects, match),
                subjects != null, match != null);
    }

    private static JsonNode readTree(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return null;
        }
        try {
            return YAML.readTree(yaml);
        } catch (IOException | RuntimeException e) {
            // Not YAML at all: nothing of the document can be read, so its scope is unreadable.
            return null;
        }
    }

    private static PolicyDsl scope(String policyId, PolicyDsl.Subjects subjects, Map<String, Object> match) {
        PolicyDsl dsl = new PolicyDsl();
        dsl.setApiVersion("dlap/v1");
        dsl.setKind(PolicyDsl.KIND_ACCESS_POLICY);
        PolicyDsl.Metadata metadata = new PolicyDsl.Metadata();
        metadata.setId(policyId);
        dsl.setMetadata(metadata);
        PolicyDsl.Spec spec = new PolicyDsl.Spec();
        spec.setEffect(PolicyDsl.EFFECT_DENY);
        spec.setSubjects(subjects);
        spec.setMatch(match);
        dsl.setSpec(spec);
        return dsl;
    }

    /** The subject selector, or {@code null} when it cannot be read as a whole. */
    private static PolicyDsl.Subjects subjects(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<String> roles = values(node.get("roles"));
        List<String> tiers = values(node.get("tiers"));
        if (roles == null || tiers == null || (roles.isEmpty() && tiers.isEmpty())) {
            return null;
        }
        PolicyDsl.Subjects s = new PolicyDsl.Subjects();
        s.setRoles(roles);
        s.setTiers(tiers);
        return s;
    }

    /** A list of non-blank plain values; empty when absent; {@code null} when it has another shape. */
    private static List<String> values(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : node) {
            if (!e.isValueNode() || e.isNull() || e.asText().isBlank()) {
                return null;
            }
            out.add(e.asText());
        }
        return out;
    }

    /** The resource selector, or {@code null} when it is absent, empty or not a map. */
    private static Map<String, Object> match(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return null;
        }
        return YAML.convertValue(node, MAP);
    }

    /** The node's text when it is a non-blank text value, else {@code null}. */
    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String t = node.asText().strip();
        return t.isEmpty() ? null : t;
    }
}
