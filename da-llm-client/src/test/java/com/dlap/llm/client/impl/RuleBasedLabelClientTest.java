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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.model.LLMResponse;
import com.dlap.llm.client.model.LabelSuggestion;
import com.dlap.llm.client.rules.DefaultLabelRules;
import com.dlap.llm.client.rules.LabelRule;

/**
 * Behavior of the deterministic {@code rules} label provider: exact outputs,
 * determinism, provenance, the rules it must never apply, and the guarantee that
 * no asset content is echoed back.
 */
class RuleBasedLabelClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC);

    /** Canonical label taxonomy seeded by 02-create-common-schema.sql and 03-insert-test-data.sql. */
    private static final Map<String, String> CANONICAL_CATEGORY = Map.ofEntries(
            Map.entry("PII", "Sensitivity"),
            Map.entry("PHI", "Compliance"),
            Map.entry("FINANCIAL", "Compliance"),
            Map.entry("CONFIDENTIAL", "Classification"),
            Map.entry("CUSTOMER_DATA", "Business Domain"),
            Map.entry("SALES_DATA", "Business Domain"),
            Map.entry("MARKETING_DATA", "Business Domain"),
            Map.entry("INVENTORY_DATA", "Business Domain"),
            Map.entry("ANALYTICS", "Business Domain"),
            Map.entry("ML_TRAINING", "Data Type"),
            Map.entry("LOG_DATA", "Data Type"));

    /** One input per shipped rule that must make exactly that rule fire when it runs alone. */
    private static final Map<String, String> SAMPLE_FOR_RULE = new LinkedHashMap<>();

    static {
        SAMPLE_FOR_RULE.put("pii.explicit", "Asset name: export_pii_2026");
        SAMPLE_FOR_RULE.put("pii.government-id", "Asset name: applicants_ssn");
        SAMPLE_FOR_RULE.put("pii.ssn-pattern", "Asset name: sample\nBusiness metadata: {example=123-45-6789}");
        SAMPLE_FOR_RULE.put("pii.contact", "Asset name: subscriber_email_list");
        SAMPLE_FOR_RULE.put("pii.hr-record", "Asset name: hr_headcount");
        SAMPLE_FOR_RULE.put("phi.explicit", "Asset name: hipaa_extract");
        SAMPLE_FOR_RULE.put("phi.clinical", "Asset name: patient_visits");
        SAMPLE_FOR_RULE.put("financial.payment-card", "Asset name: txn\nBusiness metadata: {columns=credit card, amount}");
        SAMPLE_FOR_RULE.put("financial.records", "Asset name: invoices_2026");
        SAMPLE_FOR_RULE.put("confidential.explicit", "Asset name: board_minutes\nBusiness metadata: {classification=restricted}");
        SAMPLE_FOR_RULE.put("confidential.credentials", "Asset name: service_passwords");
        SAMPLE_FOR_RULE.put("confidential.key-file", "Asset name: s3://bucket/certs/server.pem");
        SAMPLE_FOR_RULE.put("confidential.embedded-secret",
                "Asset name: config\nBusiness metadata: {note=AKIAABCDEFGHIJKLMNOP}");
        SAMPLE_FOR_RULE.put("customer.data", "Asset name: crm_accounts");
        SAMPLE_FOR_RULE.put("sales.data", "Asset name: sales_by_region");
        SAMPLE_FOR_RULE.put("marketing.data", "Asset name: spring_campaign_results");
        SAMPLE_FOR_RULE.put("inventory.data", "Asset name: warehouse_stock");
        SAMPLE_FOR_RULE.put("analytics.data", "Asset name: exec_dashboard");
        SAMPLE_FOR_RULE.put("ml.training", "Asset name: churn_training_data");
        SAMPLE_FOR_RULE.put("log.data", "Asset name: app_log_2026_10");
    }

    private final RuleBasedLabelClient client = new RuleBasedLabelClient();

    // ---- every shipped rule fires, and fires alone ---------------------

    @Test
    void everyShippedRuleHasASampleAndFiresWhenRunAlone() {
        assertThat(SAMPLE_FOR_RULE.keySet())
                .containsExactlyInAnyOrderElementsOf(
                        DefaultLabelRules.all().stream().map(LabelRule::id).collect(Collectors.toList()));

        for (LabelRule rule : DefaultLabelRules.all()) {
            RuleBasedLabelClient solo = new RuleBasedLabelClient(List.of(rule), "solo", FIXED_CLOCK);

            List<LabelSuggestion> result = solo.suggestLabelsWithConfidence(SAMPLE_FOR_RULE.get(rule.id()), null);

            assertThat(result).as("rule %s", rule.id()).hasSize(1);
            assertThat(result.get(0).getRuleId()).isEqualTo(rule.id());
            assertThat(result.get(0).getLabel()).isEqualTo(rule.label());
            assertThat(result.get(0).getConfidence()).isEqualTo(rule.confidence());
        }
    }

    // ---- exact outputs --------------------------------------------------

    @Test
    void customerPiiExport_namesBothRulesWithExactConfidences() {
        List<LabelSuggestion> result = client.suggestLabelsWithConfidence(
                "Asset name: Customer_PII_export.csv\nCloud provider: AWS\nAsset type: S3_OBJECT\nRegion: us-east-1", null);

        assertThat(result).extracting(LabelSuggestion::getLabel, LabelSuggestion::getRuleId, LabelSuggestion::getConfidence)
                .containsExactly(
                        tuple("PII", "pii.explicit", 0.95),
                        tuple("CUSTOMER_DATA", "customer.data", 0.85));
    }

    @Test
    void payrollPath_proposesPiiAndFinancial_inConfidenceOrder() {
        List<LabelSuggestion> result = client.suggestLabelsWithConfidence(
                "Asset name: payroll_2026.csv\nPath: gs://corp-hr/payroll/2026/payroll_2026.csv", null);

        assertThat(result).extracting(LabelSuggestion::getLabel, LabelSuggestion::getRuleId)
                .containsExactly(
                        tuple("FINANCIAL", "financial.records"),
                        tuple("PII", "pii.hr-record"));
    }

    @Test
    void everySuggestion_namesItsRule_itsSource_andACanonicalCategory() {
        List<LabelSuggestion> result = client.suggestLabelsWithConfidence(
                "Asset name: patient_billing_ssn_customer_logs", null);

        assertThat(result).isNotEmpty();
        for (LabelSuggestion s : result) {
            assertThat(s.getRuleId()).isNotBlank();
            assertThat(s.getReasoning()).startsWith("Rule " + s.getRuleId() + ": ");
            assertThat(s.getSource()).isEqualTo("SMART_LABEL_RULE");
            assertThat(s.getCategory()).isEqualTo(CANONICAL_CATEGORY.get(s.getLabel()));
            assertThat(s.getConfidence()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void secondMatchForSameLabel_isListedInReasoning_andOnlyOneSuggestionIsReturned() {
        List<LabelSuggestion> result = client.suggestLabelsWithConfidence("Asset name: ssn_pii_registry", null);

        List<LabelSuggestion> pii = result.stream().filter(s -> "PII".equals(s.getLabel())).toList();
        assertThat(pii).hasSize(1);
        assertThat(pii.get(0).getRuleId()).isEqualTo("pii.explicit");
        assertThat(pii.get(0).getReasoning()).contains("also matched: pii.government-id");
    }

    @Test
    void equalConfidenceTie_goesToTheEarlierRule() {
        LabelRule first = LabelRule.keywords("a.first", "PII", "Sensitivity", 0.9, LabelRule.ANY_FIELD, "first", "alpha");
        LabelRule second = LabelRule.keywords("b.second", "PII", "Sensitivity", 0.9, LabelRule.ANY_FIELD, "second", "alpha");

        List<LabelSuggestion> result = new RuleBasedLabelClient(List.of(first, second), "t", FIXED_CLOCK)
                .suggestLabelsWithConfidence("alpha", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRuleId()).isEqualTo("a.first");
        assertThat(result.get(0).getReasoning()).contains("also matched: b.second");
    }

    // ---- matching behavior ---------------------------------------------

    @Test
    void camelCaseAcronymAndSeparators_allReachTheSameKeyword() {
        for (String name : List.of("customerPII", "PIIData", "customer-pii", "customer.pii", "customer pii")) {
            assertThat(labels(client.suggestLabelsWithConfidence("Asset name: " + name, null)))
                    .as(name).contains("PII");
        }
    }

    @Test
    void keywordsMatchWholeTokensOnly() {
        // "catalog", "login", "assnot" and "classname" contain log/ssn-like substrings but no such token.
        for (String name : List.of("catalog", "login_events", "assnot", "classname", "dashboarding")) {
            assertThat(client.suggestLabelsWithConfidence("Asset name: " + name, null)).as(name).isEmpty();
        }
    }

    @Test
    void pluralKeyword_matches() {
        assertThat(labels(client.suggestLabelsWithConfidence("Asset name: invoices", null))).contains("FINANCIAL");
    }

    @Test
    void existingLabelsLine_isNotReadBack() {
        List<LabelSuggestion> result = client.suggestLabelsWithConfidence(
                "Asset name: quarterly_numbers\nExisting labels: PII, CONFIDENTIAL", null);

        assertThat(result).isEmpty();
    }

    @Test
    void providerTypeAndRegionLines_areNotContentSignals() {
        List<LabelSuggestion> result = client.suggestLabelsWithConfidence(
                "Asset name: quarterly_numbers\nCloud provider: AWS\nAsset type: LOG_GROUP\nRegion: log-east", null);

        assertThat(result).isEmpty();
    }

    @Test
    void contactKeywords_readNamingOnly_notOwnerMetadata() {
        // An owner e-mail in business metadata must not make every asset PII.
        List<LabelSuggestion> result = client.suggestLabelsWithConfidence(
                "Asset name: quarterly_numbers\nBusiness metadata: {owner_email=team@example.com}", null);

        assertThat(result).isEmpty();
    }

    @Test
    void explicitMarkerInMetadata_isHonored() {
        List<LabelSuggestion> result = client.suggestLabelsWithConfidence(
                "Asset name: quarterly_numbers\nBusiness metadata: {classification=PII}", null);

        assertThat(labels(result)).containsExactly("PII");
    }

    @Test
    void bareText_isTreatedAsAnAssetName() {
        assertThat(labels(client.suggestLabelsWithConfidence("gs://bucket/customer_pii.csv", null)))
                .containsExactly("PII", "CUSTOMER_DATA");
    }

    @Test
    void keyFileByExtensionAndName() {
        for (String name : List.of("s3://b/certs/server.pem", "/home/app/.env", "/home/app/.env.production",
                "/root/.ssh/id_rsa", "stores/client.JKS")) {
            assertThat(client.suggestLabelsWithConfidence("Asset name: " + name, null))
                    .as(name).extracting(LabelSuggestion::getRuleId).contains("confidential.key-file");
        }
        assertThat(client.suggestLabelsWithConfidence("Asset name: notes/pem_overview.md", null)).isEmpty();
    }

    @Test
    void regexEvidence_neverEchoesAssetContent() {
        List<LabelSuggestion> result = client.suggestLabelsWithConfidence(
                "Asset name: sample\nBusiness metadata: {a=123-45-6789, b=AKIAABCDEFGHIJKLMNOP}", null);

        assertThat(result).isNotEmpty();
        for (LabelSuggestion s : result) {
            assertThat(s.getReasoning()).doesNotContain("123-45-6789").doesNotContain("AKIAABCDEFGHIJKLMNOP");
        }
    }

    // ---- what the shipped set must never say ---------------------------

    @Test
    void shippedRules_neverProposePermissiveOrLifecycleLabels() {
        Set<String> labels = DefaultLabelRules.all().stream().map(LabelRule::label).collect(Collectors.toSet());

        assertThat(labels).doesNotContain("PUBLIC", "INTERNAL", "ARCHIVED");
        assertThat(CANONICAL_CATEGORY.keySet()).containsAll(labels);
    }

    @Test
    void publicOrArchiveInAName_proposesNothing() {
        assertThat(client.suggestLabelsWithConfidence("Asset name: public_archive_2019", null)).isEmpty();
    }

    @Test
    void shippedRules_haveUniqueIdsAndCanonicalCategories() {
        List<LabelRule> rules = DefaultLabelRules.all();

        assertThat(rules.stream().map(LabelRule::id).distinct().count()).isEqualTo(rules.size());
        for (LabelRule rule : rules) {
            assertThat(rule.category()).as(rule.id()).isEqualTo(CANONICAL_CATEGORY.get(rule.label()));
            assertThat(rule.confidence()).as(rule.id()).isBetween(0.0, 1.0);
        }
    }

    // ---- determinism ----------------------------------------------------

    @Test
    void sameInput_sameOutput_acrossCallsAndInstances() {
        String input = "Asset name: patient_billing_ssn_customer_logs\nPath: s3://hospital/phi/2026/";
        List<LabelSuggestion> expected = client.suggestLabelsWithConfidence(input, null);

        for (int i = 0; i < 50; i++) {
            assertThat(client.suggestLabelsWithConfidence(input, null)).isEqualTo(expected);
            assertThat(new RuleBasedLabelClient().suggestLabelsWithConfidence(input, null)).isEqualTo(expected);
        }
    }

    @Test
    void seededGeneratedNames_areStableAndWellFormed() {
        String[] vocabulary = {"customer", "pii", "log", "ssn", "payroll", "patient", "sales", "temp", "x1", "2026",
                "backup", "inventory", "model", "training", "data", "csv", "parquet", "Q3", "export", "ledger"};
        String[] separators = {"_", "-", ".", "/", " "};
        Random random = new Random(20261019L);

        for (int i = 0; i < 500; i++) {
            StringBuilder name = new StringBuilder();
            int parts = 1 + random.nextInt(5);
            for (int p = 0; p < parts; p++) {
                if (p > 0) {
                    name.append(separators[random.nextInt(separators.length)]);
                }
                name.append(vocabulary[random.nextInt(vocabulary.length)]);
            }
            String input = "Asset name: " + name;

            List<LabelSuggestion> first = client.suggestLabelsWithConfidence(input, null);
            List<LabelSuggestion> second = new RuleBasedLabelClient().suggestLabelsWithConfidence(input, null);

            assertThat(second).as(input).isEqualTo(first);
            assertThat(first.stream().map(LabelSuggestion::getLabel).distinct().count())
                    .as(input).isEqualTo(first.size());
            double previous = 1.0;
            for (LabelSuggestion s : first) {
                assertThat(s.getConfidence()).as(input).isLessThanOrEqualTo(previous);
                previous = s.getConfidence();
            }
        }
    }

    // ---- ILLMClient contract -------------------------------------------

    @Test
    void emptyAndNullContent_proposeNothing() {
        assertThat(client.suggestLabelsWithConfidence(null, null)).isEmpty();
        assertThat(client.suggestLabelsWithConfidence("", null)).isEmpty();
        assertThat(client.suggestLabelsWithConfidence("   \n  ", null)).isEmpty();
        assertThat(client.suggestLabels(null, null)).isEmpty();
    }

    @Test
    void suggestLabels_appliesMinThreshold_butConfidenceVariantDoesNot() {
        MLConfigRequest cfg = MLConfigRequest.builder()
                .confidenceConfig(MLConfigRequest.ConfidenceConfig.builder().minThreshold(0.70).build())
                .build();
        String input = "Asset name: customer_dashboard";

        assertThat(client.suggestLabels(input, cfg)).containsExactly("CUSTOMER_DATA");
        assertThat(labels(client.suggestLabelsWithConfidence(input, cfg))).containsExactly("CUSTOMER_DATA", "ANALYTICS");
        assertThat(client.suggestLabels(input, null)).containsExactly("CUSTOMER_DATA", "ANALYTICS");
    }

    @Test
    void weakSignal_sitsBelowTheDefaultAutoApplyThreshold() {
        LabelSuggestion analytics = client.suggestLabelsWithConfidence("Asset name: exec_dashboard", null).get(0);

        assertThat(analytics.getLabel()).isEqualTo("ANALYTICS");
        assertThat(analytics.getConfidence()).isLessThan(0.70);
    }

    @Test
    void maxSuggestions_capsTheStrongestFirst() {
        MLConfigRequest cfg = MLConfigRequest.builder()
                .suggestionConfig(MLConfigRequest.SuggestionConfig.builder().maxSuggestions(1).build())
                .build();

        List<LabelSuggestion> result = client.suggestLabelsWithConfidence("Asset name: customer_pii_log", cfg);

        assertThat(labels(result)).containsExactly("PII");
    }

    @Test
    void batch_returnsOneResponsePerInput_withProviderModelAndFixedTimestamp() {
        RuleBasedLabelClient fixed = new RuleBasedLabelClient(DefaultLabelRules.all(), DefaultLabelRules.VERSION, FIXED_CLOCK);

        List<LLMResponse> responses = fixed.batchSuggestLabels(
                List.of("Asset name: customer_pii", "Asset name: nothing_here"), null);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getSuccessful()).isTrue();
        assertThat(responses.get(0).getProvider()).isEqualTo("RULES");
        assertThat(responses.get(0).getModelName()).isEqualTo("rules-v1");
        assertThat(responses.get(0).getTimestamp()).isEqualTo(LocalDateTime.of(2026, 10, 19, 8, 0, 0));
        assertThat(responses.get(0).getProcessingTimeMs()).isZero();
        assertThat(responses.get(0).getTokensUsed()).isZero();
        assertThat(labels(responses.get(0).getSuggestions())).containsExactly("PII", "CUSTOMER_DATA");
        assertThat(responses.get(0).getOverallConfidence()).isEqualTo((0.95 + 0.85) / 2);
        assertThat(responses.get(1).getSuggestions()).isEmpty();
        assertThat(responses.get(1).getOverallConfidence()).isZero();
        assertThat(fixed.batchSuggestLabels(null, null)).isEmpty();
    }

    @Test
    void complete_isUnsupported() {
        assertThatThrownBy(() -> client.complete("draft a policy", null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("RULES");
    }

    @Test
    void validateConfiguration_acceptsAnythingButBadBounds() {
        assertThat(client.validateConfiguration(null)).isTrue();
        assertThat(client.validateConfiguration(MLConfigRequest.builder().providerType("rules").build())).isTrue();
        assertThat(client.validateConfiguration(MLConfigRequest.builder()
                .confidenceConfig(MLConfigRequest.ConfidenceConfig.builder().minThreshold(1.5).build()).build())).isFalse();
        assertThat(client.validateConfiguration(MLConfigRequest.builder()
                .confidenceConfig(MLConfigRequest.ConfidenceConfig.builder().minThreshold(-0.1).build()).build())).isFalse();
        assertThat(client.validateConfiguration(MLConfigRequest.builder()
                .suggestionConfig(MLConfigRequest.SuggestionConfig.builder().maxSuggestions(0).build()).build())).isFalse();
    }

    @Test
    void testConnection_needsNoEndpoint() {
        assertThat(client.testConnection(null)).isTrue();
    }

    @Test
    void capabilities_describeADeterministicOfflineProvider() {
        Map<String, Object> caps = client.getCapabilities();

        assertThat(caps).containsEntry("provider", "RULES")
                .containsEntry("deterministic", true)
                .containsEntry("requiresNetwork", false)
                .containsEntry("requiresCredentials", false)
                .containsEntry("supportsCompletion", false)
                .containsEntry("assignmentSource", "SMART_LABEL_RULE")
                .containsEntry("ruleSetVersion", "rules-v1")
                .containsEntry("ruleCount", DefaultLabelRules.all().size());
        assertThat(caps.get("labels")).isEqualTo(new ArrayList<>(new TreeSet<>(CANONICAL_CATEGORY.keySet())));
    }

    // ---- construction ---------------------------------------------------

    @Test
    void constructor_rejectsDuplicateRuleIdsAndEmptySets() {
        LabelRule rule = LabelRule.keywords("dup", "PII", "Sensitivity", 0.9, LabelRule.ANY_FIELD, "d", "x");

        assertThatThrownBy(() -> new RuleBasedLabelClient(List.of(rule, rule), "v", FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dup");
        assertThatThrownBy(() -> new RuleBasedLabelClient(List.of(), "v", FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleBasedLabelClient(null, "v", FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identity() {
        assertThat(client.getProviderName()).isEqualTo("RULES");
        assertThat(RuleBasedLabelClient.ASSIGNMENT_SOURCE).isEqualTo("SMART_LABEL_RULE");
        assertThat(client.getRules()).isEqualTo(DefaultLabelRules.all());
    }

    private static List<String> labels(List<LabelSuggestion> suggestions) {
        return suggestions.stream().map(LabelSuggestion::getLabel).collect(Collectors.toList());
    }
}
