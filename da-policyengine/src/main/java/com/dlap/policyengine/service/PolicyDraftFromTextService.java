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

package com.dlap.policyengine.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.LLMClientFactory;
import com.dlap.llm.client.config.LlmProperties;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.policyengine.dsl.PolicyDsl;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dsl.PolicyDslTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns a plain-English description into a <b>draft</b> {@code dlap/v1} policy
 * document (ADR-0044).
 *
 * <p>Draft-only by construction: this service never writes to the policy store
 * and never publishes an event. The result is handed to the visual builder so a
 * human confirms it before the existing create-policy path runs.
 *
 * <p>Three rules govern everything here:
 * <ol>
 *   <li><b>No invention.</b> An action, match field, operator or trigger the
 *       grammar does not define is a rejection with a warning, never a
 *       silently-substituted default. This mirrors
 *       {@link PolicyDslTranslator}, which treats an absent action as a hard
 *       error rather than guessing one.</li>
 *   <li><b>Destructive intent is defanged.</b> A drafted {@code delete} or
 *       {@code archive} always comes back with {@code dryRun: true} and
 *       {@code requireApproval: true}, whatever the model asked for, and the
 *       override is reported in {@code warnings}.</li>
 *   <li><b>No provider, no draft.</b> With no LLM provider configured the
 *       caller gets an explicit "unavailable" outcome, never a fabricated
 *       policy. The {@code MOCK} provider counts as no provider.</li>
 * </ol>
 *
 * <p>Drafts are always emitted with {@code metadata.enabled: false}: a document
 * that no human has reviewed must not fire even if it were applied verbatim.
 */
@Service
public class PolicyDraftFromTextService {

    private static final Logger log = LoggerFactory.getLogger(PolicyDraftFromTextService.class);

    /** Provider name that must never be used to draft a policy. */
    private static final String MOCK_PROVIDER = "MOCK";

    // ---- grammar, derived from PolicyDslEvaluator + PolicyDsl.Action + the spec ----

    /** Match fields the evaluator actually resolves (PolicyDslEvaluator clause switch). */
    static final Set<String> MATCH_FIELDS = Set.of(
            "cloudProvider", "assetType", "region", "labels",
            "lastAccessedAt", "sizeBytes", "nameMatches", "tagMatches");

    /** Canonical operators accepted by {@link PolicyDslTranslator}. */
    static final Set<String> OPERATORS = Set.of(
            "equals", "anyOf", "noneOf", "allOf",
            "olderThan", "youngerThan", "gte", "lte", "matches");

    /** Action types defined by policy-dsl.md v1.1. */
    static final Set<String> ACTION_TYPES = Set.of(
            "archive", "delete", "addLabel", "removeLabel",
            "alert", "alert.email", "alert.slack", "alert.webhook",
            "setRetention", "tagAsset", "evaluateCompliance", "runDiscovery");

    /** Action types that destroy or relocate data and must be defanged in a draft. */
    static final Set<String> DESTRUCTIVE_ACTION_TYPES = Set.of("delete", "archive");

    /** Trigger types defined by the spec. */
    static final Set<String> TRIGGER_TYPES = Set.of("schedule", "event");

    /** Lifecycle shorthands accepted on an event trigger. */
    static final Set<String> LIFECYCLES = Set.of(
            "created", "modified", "deleted", "accessed", "permission-changed", "archived");

    private static final String SYSTEM_PROMPT = """
            You translate a data-governance request written in English into a DLAP policy.
            Answer with ONE JSON object and nothing else: no prose, no markdown fence.

            Shape:
            {
              "name": string,
              "description": string,
              "conditions": [ { "field": string, "operator": string, "value": any } ],
              "action": { "type": string, "parameters": { ... } },
              "triggers": [ { "type": string, "cron"?: string, "lifecycle"?: string, "topic"?: string } ],
              "confidence": number between 0 and 1,
              "assumptions": [ string ]
            }

            Allowed condition fields (nothing else):
              cloudProvider  string, e.g. GCP | AWS | AZURE | OCI
              assetType      string, e.g. GCS_BUCKET | BIGQUERY_TABLE | S3_BUCKET
              region         string, e.g. us-central1
              labels         set of label names, e.g. PII, PHI, LEGAL_HOLD
              lastAccessedAt ISO-8601 duration, e.g. P90D
              sizeBytes      number of bytes
              nameMatches    regular expression against the asset name
              tagMatches     map of cloud-native tag key to value

            Allowed operators (nothing else):
              equals      scalar equality
              anyOf       value is a list; asset matches any member
              noneOf      value is a list; asset matches no member
              allOf       value is a list; asset matches every member
              olderThan   lastAccessedAt only; value is an ISO-8601 duration
              youngerThan lastAccessedAt only; value is an ISO-8601 duration
              gte         sizeBytes only; value is a number
              lte         sizeBytes only; value is a number
              matches     nameMatches only; value is a regular expression

            Allowed action types (nothing else):
              archive            parameters: targetStorageClass, auditNote
              delete             parameters: softDeleteWindowDays, auditNote (auditNote required)
              addLabel           parameters: label, category
              removeLabel        parameters: label, category
              alert.email        parameters: recipients, severity, message
              alert.slack        parameters: recipients, severity, message
              alert.webhook      parameters: recipients, severity, message
              setRetention       parameters: retentionDays, mode
              tagAsset           parameters: tags, replace
              evaluateCompliance parameters: per the compliance spec
              runDiscovery       parameters: per the discovery spec
            Exactly one action. A policy with no action is invalid.

            Allowed trigger types (nothing else):
              {"type":"schedule","cron":"<Quartz cron with a seconds field>"}
              {"type":"event","lifecycle":"created|modified|deleted|accessed|permission-changed|archived"}
              {"type":"event","topic":"<kafka topic>"}

            Rules:
            - Use only the fields, operators, action types and trigger types listed above.
            - If the request needs something not listed, do NOT approximate it. Return the
              closest valid policy you can and record what you could not express in
              "assumptions", lowering "confidence".
            - Never invent a label name, bucket name, project id, region or recipient that
              the request did not mention. If one is required and absent, say so in
              "assumptions".
            - Set "confidence" honestly. Vague requests deserve a low number.

            Examples:

            Request: Archive GCS buckets tagged PII that nobody has touched in 90 days, unless they are on legal hold. Run it nightly at 2am.
            {"name":"Archive stale PII buckets","description":"Archive GCS buckets labelled PII with no access for 90 days, excluding legal hold.","conditions":[{"field":"cloudProvider","operator":"equals","value":"GCP"},{"field":"assetType","operator":"equals","value":"GCS_BUCKET"},{"field":"labels","operator":"anyOf","value":["PII"]},{"field":"labels","operator":"noneOf","value":["LEGAL_HOLD"]},{"field":"lastAccessedAt","operator":"olderThan","value":"P90D"}],"action":{"type":"archive","parameters":{"targetStorageClass":"ARCHIVE","auditNote":"Stale PII auto-archive"}},"triggers":[{"type":"schedule","cron":"0 0 2 * * ?"}],"confidence":0.9,"assumptions":["Interpreted 'nobody has touched' as lastAccessedAt older than P90D."]}

            Request: Label any new table whose name starts with prod- as Production.
            {"name":"Label production tables","description":"Add the Production label to newly discovered assets named prod-*.","conditions":[{"field":"nameMatches","operator":"matches","value":"^prod-.*"}],"action":{"type":"addLabel","parameters":{"label":"Production"}},"triggers":[{"type":"event","lifecycle":"created"}],"confidence":0.86,"assumptions":["Applied to every asset type because none was named."]}

            Request: Delete anything in AWS bigger than 1GB that has not been read for two years.
            {"name":"Delete large stale AWS assets","description":"Delete AWS assets over 1GB with no access for two years.","conditions":[{"field":"cloudProvider","operator":"equals","value":"AWS"},{"field":"sizeBytes","operator":"gte","value":1073741824},{"field":"lastAccessedAt","operator":"olderThan","value":"P730D"}],"action":{"type":"delete","parameters":{"softDeleteWindowDays":30,"auditNote":"Large stale AWS asset removal"}},"triggers":[{"type":"schedule","cron":"0 0 3 * * ?"}],"confidence":0.74,"assumptions":["Read 'two years' as P730D.","Kept the default 30-day soft-delete window."]}

            Request: Email the data team when an asset in eu-west-1 gets its permissions changed.
            {"name":"Alert on eu-west-1 permission change","description":"Email the data governance team when an eu-west-1 asset's permissions change.","conditions":[{"field":"region","operator":"equals","value":"eu-west-1"}],"action":{"type":"alert.email","parameters":{"severity":"WARN","message":"Permissions changed on {{ asset.name }}"}},"triggers":[{"type":"event","lifecycle":"permission-changed"}],"confidence":0.7,"assumptions":["No recipient address was given, so 'recipients' is left unset."]}
            """;

    private final LLMClientFactory llmClientFactory;
    private final LlmProperties llmProperties;
    private final PolicyDslTranslator translator;
    private final PolicyDslParser parser;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Creates the drafting service.
     *
     * @param llmClientFactory Registry of available LLM provider clients.
     * @param llmProperties Platform LLM configuration ({@code llm.*}).
     * @param translator Structured-input to canonical-DSL translator.
     * @param parser Canonical-DSL validator.
     */
    public PolicyDraftFromTextService(LLMClientFactory llmClientFactory,
                                      LlmProperties llmProperties,
                                      PolicyDslTranslator translator,
                                      PolicyDslParser parser) {
        this.llmClientFactory = llmClientFactory;
        this.llmProperties = llmProperties;
        this.translator = translator;
        this.parser = parser;
    }

    /** Why a drafting attempt ended the way it did. */
    public enum Outcome {
        /** A valid draft was produced. */
        DRAFTED,
        /** The model's answer could not be turned into a valid policy. */
        REJECTED,
        /** No usable LLM provider is configured. */
        PROVIDER_UNAVAILABLE
    }

    /**
     * Result of a drafting attempt.
     *
     * @param outcome What happened.
     * @param dslYaml Canonical YAML, {@code null} unless {@link Outcome#DRAFTED}.
     * @param structured Parsed DSL, {@code null} unless {@link Outcome#DRAFTED}.
     * @param confidence Model-reported confidence, or {@code null}.
     * @param assumptions Assumptions the model reported.
     * @param warnings What a human must check, or why this was rejected.
     */
    public record Draft(Outcome outcome,
                        String dslYaml,
                        PolicyDsl structured,
                        Double confidence,
                        List<String> assumptions,
                        List<String> warnings) {
    }

    /**
     * Drafts a policy from a plain-English request.
     *
     * <p>Persists nothing. Callers must present the result for human
     * confirmation before creating a policy from it.
     *
     * @param text The plain-English request. Must not be blank.
     * @param owner Optional owner written into {@code metadata.owner}.
     * @return The drafting result; inspect {@link Draft#outcome()} before use.
     */
    public Draft draftFromText(String text, String owner) {
        if (text == null || text.isBlank()) {
            return rejected(null, List.of("text is required"));
        }

        Optional<MLConfigRequest> config = llmProperties.resolve();
        if (config.isEmpty()) {
            return unavailable("No LLM provider is configured. Set llm.provider "
                    + "(openai or ollama) and the matching llm.<provider>.* properties.");
        }
        MLConfigRequest mlConfig = config.get();
        if (MOCK_PROVIDER.equalsIgnoreCase(mlConfig.getProviderType())) {
            return unavailable("The MOCK LLM provider cannot draft policies. "
                    + "Configure a real provider via llm.provider.");
        }

        ILLMClient client;
        try {
            client = llmClientFactory.getClient(mlConfig);
        } catch (IllegalArgumentException e) {
            // Never log mlConfig itself - it carries the API key.
            log.warn("Policy drafting unavailable: {}", e.getMessage());
            return unavailable("LLM provider '" + mlConfig.getProviderType()
                    + "' is selected but not registered in this service.");
        }

        String raw;
        try {
            log.info("Drafting policy from text: provider={}, model={}, requestChars={}",
                    mlConfig.getProviderType(), mlConfig.getModelName(), text.length());
            raw = client.complete(SYSTEM_PROMPT + "\n\nRequest: " + text.trim() + "\n", mlConfig);
        } catch (UnsupportedOperationException e) {
            log.warn("Provider {} cannot perform free-form completion", mlConfig.getProviderType());
            return unavailable("LLM provider '" + mlConfig.getProviderType()
                    + "' does not support free-form completion.");
        } catch (Exception e) {
            log.error("LLM call failed while drafting a policy (provider={}): {}",
                    mlConfig.getProviderType(), e.getMessage());
            return rejected(null, List.of("The LLM provider call failed: " + e.getMessage()));
        }

        return buildDraft(raw, owner);
    }

    // ---- response handling ----------------------------------------------

    private Draft buildDraft(String raw, String owner) {
        JsonNode root;
        try {
            root = json.readTree(stripFence(raw));
        } catch (Exception e) {
            log.warn("LLM returned unparseable JSON while drafting a policy: {}", e.getMessage());
            return rejected(null, List.of(
                    "The model did not return valid JSON, so no policy was drafted."));
        }
        if (root == null || !root.isObject()) {
            return rejected(null, List.of(
                    "The model did not return a JSON object, so no policy was drafted."));
        }

        List<String> warnings = new ArrayList<>();
        List<String> assumptions = readStringList(root.get("assumptions"));
        Double confidence = readConfidence(root.get("confidence"), warnings);

        String name = text(root.get("name"));
        if (name == null || name.isBlank()) {
            warnings.add("The model returned no policy name.");
        }

        List<Map<String, Object>> conditions = readConditions(root.get("conditions"), warnings);
        Map<String, Object> action = readAction(root.get("action"), warnings);
        List<Map<String, Object>> triggers = readTriggers(root.get("triggers"), warnings);

        if (!warnings.isEmpty()) {
            return new Draft(Outcome.REJECTED, null, null, confidence, assumptions, List.copyOf(warnings));
        }

        // Safety override: a draft that deletes or archives never comes back armed.
        String actionType = String.valueOf(action.get("type"));
        if (DESTRUCTIVE_ACTION_TYPES.contains(actionType)) {
            action.put("dryRun", true);
            action.put("requireApproval", true);
            warnings.add("Destructive action '" + actionType
                    + "' was drafted with dryRun=true and requireApproval=true. "
                    + "Clearing either one is a deliberate human decision.");
        }

        try {
            // enabled=false: an unreviewed draft must not fire even if applied verbatim.
            PolicyDsl dsl = translator.fromStructured(
                    null, name, text(root.get("description")), owner, false,
                    conditions, action, triggers);
            String yaml = translator.toYaml(dsl);
            PolicyDsl validated = parser.parse(yaml);
            warnings.add("Draft only - nothing was saved. Review every clause before creating the policy.");
            return new Draft(Outcome.DRAFTED, yaml, validated, confidence, assumptions, List.copyOf(warnings));
        } catch (PolicyDslTranslator.PolicyTranslationException
                 | PolicyDslParser.PolicyDslParseException e) {
            log.warn("Drafted policy failed DSL validation: {}", e.getMessage());
            return new Draft(Outcome.REJECTED, null, null, confidence, assumptions,
                    List.of("The drafted policy is not a valid dlap/v1 document: " + e.getMessage()));
        }
    }

    private List<Map<String, Object>> readConditions(JsonNode node, List<String> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            warnings.add("'conditions' must be a JSON array.");
            return out;
        }
        for (JsonNode c : node) {
            String field = text(c.get("field"));
            String operator = text(c.get("operator"));
            if (field == null || !MATCH_FIELDS.contains(field)) {
                warnings.add("Unsupported match field '" + field + "'. Supported fields: "
                        + sorted(MATCH_FIELDS) + ".");
                continue;
            }
            if (operator == null || !OPERATORS.contains(operator)) {
                warnings.add("Unsupported operator '" + operator + "' on field '" + field
                        + "'. Supported operators: " + sorted(OPERATORS) + ".");
                continue;
            }
            Map<String, Object> clause = new LinkedHashMap<>();
            clause.put("field", field);
            clause.put("operator", operator);
            clause.put("value", plain(c.get("value")));
            out.add(clause);
        }
        return out;
    }

    private Map<String, Object> readAction(JsonNode node, List<String> warnings) {
        Map<String, Object> action = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            warnings.add("The model returned no action. A policy without an action is invalid.");
            return action;
        }
        String type = text(node.get("type"));
        if (type == null || !ACTION_TYPES.contains(type)) {
            warnings.add("Unsupported action type '" + type + "'. Supported actions: "
                    + sorted(ACTION_TYPES) + ".");
            return action;
        }
        action.put("type", type);
        Object params = plain(node.get("parameters"));
        if (params instanceof Map<?, ?> map && !map.isEmpty()) {
            action.put("parameters", map);
        }
        return action;
    }

    private List<Map<String, Object>> readTriggers(JsonNode node, List<String> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;   // translator supplies the documented created/modified event default
        }
        if (!node.isArray()) {
            warnings.add("'triggers' must be a JSON array.");
            return out;
        }
        for (JsonNode t : node) {
            String type = text(t.get("type"));
            if (type == null || !TRIGGER_TYPES.contains(type)) {
                warnings.add("Unsupported trigger type '" + type + "'. Supported types: "
                        + sorted(TRIGGER_TYPES) + ".");
                continue;
            }
            String lifecycle = text(t.get("lifecycle"));
            if (lifecycle != null && !LIFECYCLES.contains(lifecycle)) {
                warnings.add("Unsupported lifecycle '" + lifecycle + "'. Supported lifecycles: "
                        + sorted(LIFECYCLES) + ".");
                continue;
            }
            String cron = text(t.get("cron"));
            if ("schedule".equals(type) && (cron == null || cron.isBlank())) {
                warnings.add("A schedule trigger needs a cron expression.");
                continue;
            }
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("type", type);
            if (cron != null) {
                trigger.put("cron", cron);
            }
            if (lifecycle != null) {
                trigger.put("lifecycle", lifecycle);
            }
            String topic = text(t.get("topic"));
            if (topic != null) {
                trigger.put("topic", topic);
            }
            out.add(trigger);
        }
        return out;
    }

    private Double readConfidence(JsonNode node, List<String> warnings) {
        if (node == null || !node.isNumber()) {
            warnings.add("The model reported no usable confidence value.");
            return null;
        }
        double value = node.asDouble();
        if (value < 0.0 || value > 1.0) {
            warnings.add("The model reported a confidence outside [0,1]: " + value + ".");
            return null;
        }
        return value;
    }

    // ---- helpers ---------------------------------------------------------

    private Draft rejected(Double confidence, List<String> warnings) {
        return new Draft(Outcome.REJECTED, null, null, confidence, List.of(), warnings);
    }

    private Draft unavailable(String message) {
        return new Draft(Outcome.PROVIDER_UNAVAILABLE, null, null, null, List.of(), List.of(message));
    }

    private List<String> readStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String s = text(n);
                if (s != null && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }

    /** Strips a ```json … ``` fence when a model wraps its answer in one. */
    private static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        int lastFence = s.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return s;
        }
        return s.substring(firstNewline + 1, lastFence).trim();
    }

    private Object plain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return json.convertValue(node, Object.class);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String s = node.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Renders a grammar set as a stable, human-readable list for warning messages. */
    private static String sorted(Set<String> values) {
        return values.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.joining(", "));
    }
}
