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

package com.dlap.llm.client.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.model.LLMResponse;
import com.dlap.llm.client.model.LabelSuggestion;
import com.dlap.llm.client.rules.AssetText;
import com.dlap.llm.client.rules.DefaultLabelRules;
import com.dlap.llm.client.rules.LabelRule;
import com.dlap.llm.client.rules.LabelRule.Field;

/**
 * Deterministic label provider: keyword and regular-expression rules over an
 * asset's name, path and metadata. No model, no network, no credentials, no
 * randomness.
 *
 * <p>Selected with provider type {@code rules}. It is the honest offline option
 * for a demo or an air-gapped install: every suggestion names the rule that
 * produced it ({@link LabelSuggestion#getRuleId()}), carries the provenance
 * {@value #ASSIGNMENT_SOURCE} ({@link LabelSuggestion#getSource()}), and the same
 * input always yields the same output in the same order.
 *
 * <p>Evaluation:
 * <ol>
 *   <li>Parse the input into name, path and metadata ({@link AssetText}).</li>
 *   <li>Run every rule over the fields it declares.</li>
 *   <li>Keep one suggestion per label: the matching rule with the highest
 *       confidence, earliest rule winning ties. Other matching rules for the same
 *       label are listed in the reasoning.</li>
 *   <li>Order by confidence descending, then by rule order, and apply
 *       {@code suggestionConfig.maxSuggestions} when set.</li>
 * </ol>
 *
 * <p>The reasoning text never echoes asset content: a keyword match reports the
 * rule's own keyword, a pattern match reports only that the pattern matched.
 *
 * <p>Free-form completion is not supported; {@link #complete} throws, so callers
 * that need generated text (for example policy drafting) cannot mistake this
 * provider for a language model.
 *
 * <p>Registered as the {@code rulesLLMClient} bean by
 * {@link com.dlap.llm.client.LlmClientAutoConfiguration}. Instances are immutable
 * and thread safe.
 */
public class RuleBasedLabelClient implements ILLMClient {

    /** Provider name; selected with {@code providerType=rules} (case-insensitive). */
    public static final String PROVIDER_NAME = "RULES";

    /**
     * Provenance reported on every suggestion. Equals
     * {@code DlapConstants.AssignmentSource.SMART_LABEL_RULE}; the llm-client module
     * does not depend on da-protos, and a test in da-autolabel pins the two together.
     */
    public static final String ASSIGNMENT_SOURCE = "SMART_LABEL_RULE";

    private static final Logger log = LoggerFactory.getLogger(RuleBasedLabelClient.class);

    private final List<LabelRule> rules;
    private final String ruleSetVersion;
    private final Clock clock;

    /** Creates a client over the shipped {@link DefaultLabelRules} using the system default clock. */
    public RuleBasedLabelClient() {
        this(DefaultLabelRules.all(), DefaultLabelRules.VERSION, Clock.systemDefaultZone());
    }

    /**
     * Creates a client over a custom rule set.
     *
     * @param rules the rules, in evaluation order; ids must be unique
     * @param ruleSetVersion label for this rule set, reported as the model name
     * @param clock time source for response timestamps and timings
     * @throws IllegalArgumentException if a rule id is duplicated or no rule is given
     */
    public RuleBasedLabelClient(List<LabelRule> rules, String ruleSetVersion, Clock clock) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule is required");
        }
        Set<String> ids = new HashSet<>();
        for (LabelRule rule : rules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate rule id: " + rule.id());
            }
        }
        this.rules = List.copyOf(rules);
        this.ruleSetVersion = ruleSetVersion == null || ruleSetVersion.isBlank() ? "custom" : ruleSetVersion;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unsupported by design: a rule set cannot complete free-form prompts.
     *
     * @throws UnsupportedOperationException always.
     */
    @Override
    public String complete(String prompt, MLConfigRequest mlConfig) {
        throw new UnsupportedOperationException(
                "RULES provider does not support free-form completion; configure a real LLM provider");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applies {@code confidenceConfig.minThreshold} when set: rules below it are
     * dropped. {@link #suggestLabelsWithConfidence} does not filter by threshold, so
     * a caller with its own review queue can route weak suggestions to humans.
     */
    @Override
    public List<String> suggestLabels(String assetContent, MLConfigRequest mlConfig) {
        double minThreshold = minThreshold(mlConfig);
        List<String> labels = new ArrayList<>();
        for (LabelSuggestion suggestion : suggestLabelsWithConfidence(assetContent, mlConfig)) {
            if (suggestion.getConfidence() >= minThreshold) {
                labels.add(suggestion.getLabel());
            }
        }
        return labels;
    }

    @Override
    public List<LabelSuggestion> suggestLabelsWithConfidence(String assetContent, MLConfigRequest mlConfig) {
        AssetText text = AssetText.parse(assetContent);

        // One winning match per label, plus the ids of the other rules that agreed.
        Map<String, Match> winners = new LinkedHashMap<>();
        for (int index = 0; index < rules.size(); index++) {
            LabelRule rule = rules.get(index);
            String evidence = firstEvidence(rule, text);
            if (evidence == null) {
                continue;
            }
            Match candidate = new Match(rule, index, evidence);
            Match current = winners.get(rule.label());
            if (current == null) {
                winners.put(rule.label(), candidate);
            } else if (candidate.rule.confidence() > current.rule.confidence()) {
                candidate.alsoMatched.add(current.rule.id());
                candidate.alsoMatched.addAll(current.alsoMatched);
                winners.put(rule.label(), candidate);
            } else {
                current.alsoMatched.add(rule.id());
            }
        }

        List<Match> ordered = new ArrayList<>(winners.values());
        ordered.sort(Comparator.<Match>comparingDouble(m -> m.rule.confidence()).reversed()
                .thenComparingInt(m -> m.index));

        int limit = maxSuggestions(mlConfig);
        List<LabelSuggestion> suggestions = new ArrayList<>();
        for (Match match : ordered) {
            if (suggestions.size() >= limit) {
                break;
            }
            suggestions.add(match.toSuggestion());
        }
        log.debug("RULES provider produced {} suggestion(s) for {} input chars (rule set {})",
                suggestions.size(), assetContent == null ? 0 : assetContent.length(), ruleSetVersion);
        return suggestions;
    }

    @Override
    public List<LLMResponse> batchSuggestLabels(List<String> assetContents, MLConfigRequest mlConfig) {
        List<LLMResponse> responses = new ArrayList<>();
        if (assetContents == null) {
            return responses;
        }
        String model = mlConfig != null && mlConfig.getModelName() != null && !mlConfig.getModelName().isBlank()
                ? mlConfig.getModelName() : ruleSetVersion;
        for (String content : assetContents) {
            long startMs = clock.millis();
            List<LabelSuggestion> suggestions = suggestLabelsWithConfidence(content, mlConfig);
            responses.add(LLMResponse.builder()
                    .inputContent(content)
                    .suggestions(suggestions)
                    .overallConfidence(average(suggestions))
                    .processingTimeMs(clock.millis() - startMs)
                    .timestamp(LocalDateTime.now(clock))
                    .provider(PROVIDER_NAME)
                    .modelName(model)
                    .tokensUsed(0)
                    .estimatedCost(0.0)
                    .successful(true)
                    .build());
        }
        return responses;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The rule provider needs no credentials or model, so any configuration is
     * valid except an out-of-range confidence threshold or a non-positive
     * suggestion cap.
     */
    @Override
    public boolean validateConfiguration(MLConfigRequest mlConfig) {
        if (mlConfig == null) {
            return true;
        }
        MLConfigRequest.ConfidenceConfig confidence = mlConfig.getConfidenceConfig();
        if (confidence != null && confidence.getMinThreshold() != null
                && (confidence.getMinThreshold() < 0.0 || confidence.getMinThreshold() > 1.0)) {
            return false;
        }
        MLConfigRequest.SuggestionConfig suggestion = mlConfig.getSuggestionConfig();
        return suggestion == null || suggestion.getMaxSuggestions() == null || suggestion.getMaxSuggestions() > 0;
    }

    @Override
    public Map<String, Object> getCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("provider", PROVIDER_NAME);
        capabilities.put("deterministic", true);
        capabilities.put("supportsBatch", true);
        capabilities.put("supportsConfidence", true);
        capabilities.put("supportsReasoning", true);
        capabilities.put("supportsCompletion", false);
        capabilities.put("requiresNetwork", false);
        capabilities.put("requiresCredentials", false);
        capabilities.put("assignmentSource", ASSIGNMENT_SOURCE);
        capabilities.put("maxTokens", 0);
        capabilities.put("cost", 0.0);
        capabilities.put("ruleSetVersion", ruleSetVersion);
        capabilities.put("ruleCount", rules.size());
        Set<String> labels = new TreeSet<>();
        for (LabelRule rule : rules) {
            labels.add(rule.label());
        }
        capabilities.put("labels", List.copyOf(labels));
        return capabilities;
    }

    /** {@inheritDoc} A rule set has no remote endpoint, so the check always passes. */
    @Override
    public boolean testConnection(MLConfigRequest mlConfig) {
        return true;
    }

    /**
     * Returns the rules this client evaluates.
     *
     * @return an immutable list in evaluation order
     */
    public List<LabelRule> getRules() {
        return rules;
    }

    private static String firstEvidence(LabelRule rule, AssetText text) {
        for (Field field : Field.values()) {
            if (!rule.fields().contains(field)) {
                continue;
            }
            String hit = rule.match(text.normalized(field), text.raw(field));
            if (hit != null) {
                return hit + " in " + field.name().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static double minThreshold(MLConfigRequest mlConfig) {
        if (mlConfig == null || mlConfig.getConfidenceConfig() == null
                || mlConfig.getConfidenceConfig().getMinThreshold() == null) {
            return 0.0;
        }
        return mlConfig.getConfidenceConfig().getMinThreshold();
    }

    private static int maxSuggestions(MLConfigRequest mlConfig) {
        if (mlConfig == null || mlConfig.getSuggestionConfig() == null
                || mlConfig.getSuggestionConfig().getMaxSuggestions() == null
                || mlConfig.getSuggestionConfig().getMaxSuggestions() <= 0) {
            return Integer.MAX_VALUE;
        }
        return mlConfig.getSuggestionConfig().getMaxSuggestions();
    }

    private static double average(List<LabelSuggestion> suggestions) {
        if (suggestions.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (LabelSuggestion suggestion : suggestions) {
            sum += suggestion.getConfidence();
        }
        return sum / suggestions.size();
    }

    /** A rule that matched, with its position in the rule list and its evidence. */
    private static final class Match {
        private final LabelRule rule;
        private final int index;
        private final String evidence;
        private final List<String> alsoMatched = new ArrayList<>();

        private Match(LabelRule rule, int index, String evidence) {
            this.rule = rule;
            this.index = index;
            this.evidence = evidence;
        }

        private LabelSuggestion toSuggestion() {
            StringBuilder reasoning = new StringBuilder("Rule ")
                    .append(rule.id()).append(": ").append(rule.description())
                    .append(" (").append(evidence).append(")");
            if (!alsoMatched.isEmpty()) {
                reasoning.append("; also matched: ").append(String.join(", ", alsoMatched));
            }
            return LabelSuggestion.builder()
                    .label(rule.label())
                    .confidence(rule.confidence())
                    .reasoning(reasoning.toString())
                    .category(rule.category())
                    .source(ASSIGNMENT_SOURCE)
                    .ruleId(rule.id())
                    .build();
        }
    }
}
