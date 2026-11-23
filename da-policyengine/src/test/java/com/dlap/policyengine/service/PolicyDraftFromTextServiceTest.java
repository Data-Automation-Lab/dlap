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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.LLMClientFactory;
import com.dlap.llm.client.config.LlmProperties;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.model.LLMResponse;
import com.dlap.llm.client.model.LabelSuggestion;
import com.dlap.policyengine.dsl.PolicyDsl;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dsl.PolicyDslTranslator;
import com.dlap.policyengine.service.PolicyDraftFromTextService.Draft;
import com.dlap.policyengine.service.PolicyDraftFromTextService.Outcome;

/**
 * Unit tests for {@link PolicyDraftFromTextService} (ADR-0044). No network:
 * the LLM is a {@link StubLlmClient} that replays a canned completion.
 *
 * <p>The contract under test: a draft is only ever produced from grammar the
 * DSL actually defines, destructive drafts come back disarmed, and an absent
 * provider yields an explicit "unavailable" instead of an invented policy.
 */
class PolicyDraftFromTextServiceTest {

    private final PolicyDslTranslator translator = new PolicyDslTranslator();
    private final PolicyDslParser parser = new PolicyDslParser();

    // ---- (a) valid text produces a valid DSL document --------------------

    @Test
    void validResponse_producesParsableDslYaml() {
        String completion = """
                {"name":"Archive stale PII buckets",
                 "description":"Archive GCS buckets labelled PII untouched for 90 days.",
                 "conditions":[{"field":"cloudProvider","operator":"equals","value":"GCP"},
                               {"field":"labels","operator":"anyOf","value":["PII"]},
                               {"field":"lastAccessedAt","operator":"olderThan","value":"P90D"}],
                 "action":{"type":"addLabel","parameters":{"label":"StaleReview"}},
                 "triggers":[{"type":"schedule","cron":"0 0 2 * * ?"}],
                 "confidence":0.88,
                 "assumptions":["Read 'untouched' as lastAccessedAt."]}
                """;

        Draft draft = service(completion, openAiProperties())
                .draftFromText("Archive stale PII buckets", "governance@example.com");

        assertEquals(Outcome.DRAFTED, draft.outcome());
        assertNotNull(draft.dslYaml());
        assertEquals(0.88, draft.confidence());
        assertEquals(List.of("Read 'untouched' as lastAccessedAt."), draft.assumptions());

        // The YAML must survive the same parser the evaluator uses.
        PolicyDsl parsed = parser.parse(draft.dslYaml());
        assertEquals("dlap/v1", parsed.getApiVersion());
        assertEquals("Policy", parsed.getKind());
        assertEquals("archive-stale-pii-buckets", parsed.getMetadata().getId());
        assertEquals("governance@example.com", parsed.getMetadata().getOwner());
        assertEquals("addLabel", parsed.getSpec().getAction().getType());
        assertEquals(1, parsed.getSpec().getTriggers().size());
        assertEquals("GCP", parsed.getSpec().getMatch().get("cloudProvider"));

        // An unreviewed draft must not be armed to fire.
        assertFalse(parsed.getMetadata().isEnabled());
        assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("Draft only")));
    }

    // ---- (b) destructive intent is disarmed ------------------------------

    @Test
    void deleteIntent_forcesDryRunAndRequireApproval() {
        String completion = """
                {"name":"Delete stale AWS data",
                 "description":"Delete large AWS assets nobody reads.",
                 "conditions":[{"field":"cloudProvider","operator":"equals","value":"AWS"},
                               {"field":"sizeBytes","operator":"gte","value":1073741824}],
                 "action":{"type":"delete","dryRun":false,"requireApproval":false,
                           "parameters":{"auditNote":"stale data"}},
                 "triggers":[{"type":"schedule","cron":"0 0 3 * * ?"}],
                 "confidence":0.7,
                 "assumptions":[]}
                """;

        Draft draft = service(completion, openAiProperties())
                .draftFromText("Delete large stale AWS data", null);

        assertEquals(Outcome.DRAFTED, draft.outcome());
        PolicyDsl parsed = parser.parse(draft.dslYaml());
        assertEquals("delete", parsed.getSpec().getAction().getType());
        assertTrue(parsed.getSpec().getAction().isDryRun(), "delete draft must be dry-run");
        assertTrue(parsed.getSpec().getAction().isRequireApproval(), "delete draft must require approval");
        assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("dryRun=true")));
    }

    @Test
    void archiveIntent_forcesDryRunAndRequireApproval() {
        String completion = """
                {"name":"Archive cold buckets","description":"Archive cold GCS buckets.",
                 "conditions":[{"field":"assetType","operator":"equals","value":"GCS_BUCKET"}],
                 "action":{"type":"archive","parameters":{"targetStorageClass":"ARCHIVE"}},
                 "triggers":[{"type":"event","lifecycle":"created"}],
                 "confidence":0.6,"assumptions":[]}
                """;

        Draft draft = service(completion, openAiProperties()).draftFromText("Archive cold buckets", null);

        assertEquals(Outcome.DRAFTED, draft.outcome());
        PolicyDsl parsed = parser.parse(draft.dslYaml());
        assertTrue(parsed.getSpec().getAction().isDryRun());
        assertTrue(parsed.getSpec().getAction().isRequireApproval());
    }

    // ---- (c) unsupported grammar is refused, never approximated ----------

    @Test
    void unsupportedAction_isRejectedWithWarningsAndNoDsl() {
        String completion = """
                {"name":"Encrypt everything","description":"Encrypt all assets.",
                 "conditions":[{"field":"cloudProvider","operator":"equals","value":"AWS"}],
                 "action":{"type":"encryptAsset","parameters":{"kms":"default"}},
                 "triggers":[{"type":"event","lifecycle":"created"}],
                 "confidence":0.9,"assumptions":[]}
                """;

        Draft draft = service(completion, openAiProperties()).draftFromText("Encrypt everything", null);

        assertEquals(Outcome.REJECTED, draft.outcome());
        assertNull(draft.dslYaml());
        assertNull(draft.structured());
        assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("encryptAsset")));
        assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("Supported actions")));
    }

    @Test
    void unsupportedMatchField_isRejectedWithWarningsAndNoDsl() {
        String completion = """
                {"name":"Owner policy","description":"Match on an unknown field.",
                 "conditions":[{"field":"ownerEmail","operator":"equals","value":"a@b.com"}],
                 "action":{"type":"addLabel","parameters":{"label":"Owned"}},
                 "triggers":[{"type":"event","lifecycle":"created"}],
                 "confidence":0.5,"assumptions":[]}
                """;

        Draft draft = service(completion, openAiProperties()).draftFromText("Label by owner", null);

        assertEquals(Outcome.REJECTED, draft.outcome());
        assertNull(draft.dslYaml());
        assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("ownerEmail")));
    }

    @Test
    void missingAction_isRejected() {
        String completion = """
                {"name":"No action","description":"Nothing to do.",
                 "conditions":[],"triggers":[{"type":"event","lifecycle":"created"}],
                 "confidence":0.4,"assumptions":[]}
                """;

        Draft draft = service(completion, openAiProperties()).draftFromText("Do something", null);

        assertEquals(Outcome.REJECTED, draft.outcome());
        assertNull(draft.dslYaml());
        assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("no action")));
    }

    // ---- (d) malformed model output fails cleanly ------------------------

    @Test
    void malformedJson_isRejectedCleanly() {
        Draft draft = service("I'm sorry, I can't help with that.", openAiProperties())
                .draftFromText("Archive things", null);

        assertEquals(Outcome.REJECTED, draft.outcome());
        assertNull(draft.dslYaml());
        assertEquals(1, draft.warnings().size());
        assertTrue(draft.warnings().get(0).contains("valid JSON"));
    }

    @Test
    void fencedJson_isUnwrappedAndAccepted() {
        String completion = """
                ```json
                {"name":"Label prod tables","description":"Label prod-* assets.",
                 "conditions":[{"field":"nameMatches","operator":"matches","value":"^prod-.*"}],
                 "action":{"type":"addLabel","parameters":{"label":"Production"}},
                 "triggers":[{"type":"event","lifecycle":"created"}],
                 "confidence":0.8,"assumptions":[]}
                ```
                """;

        Draft draft = service(completion, openAiProperties()).draftFromText("Label prod tables", null);

        assertEquals(Outcome.DRAFTED, draft.outcome());
        assertNotNull(draft.dslYaml());
    }

    // ---- (e) no provider configured --------------------------------------

    @Test
    void noProviderConfigured_isUnavailableNotFabricated() {
        Draft draft = service("{}", new LlmProperties()).draftFromText("Archive stale data", null);

        assertEquals(Outcome.PROVIDER_UNAVAILABLE, draft.outcome());
        assertNull(draft.dslYaml());
        assertNull(draft.structured());
        assertTrue(draft.warnings().get(0).contains("llm.provider"));
    }

    @Test
    void openAiSelectedWithoutApiKey_isUnavailable() {
        LlmProperties props = new LlmProperties();
        props.setProvider("openai");   // no api key

        Draft draft = service("{}", props).draftFromText("Archive stale data", null);

        assertEquals(Outcome.PROVIDER_UNAVAILABLE, draft.outcome());
    }

    @Test
    void providerSelectedButNotRegistered_isUnavailable() {
        LLMClientFactory emptyFactory = new LLMClientFactory(List.of());
        PolicyDraftFromTextService svc = new PolicyDraftFromTextService(
                emptyFactory, openAiProperties(), translator, parser);

        Draft draft = svc.draftFromText("Archive stale data", null);

        assertEquals(Outcome.PROVIDER_UNAVAILABLE, draft.outcome());
        assertTrue(draft.warnings().get(0).contains("not registered"));
    }

    @Test
    void providerRefusingCompletion_isUnavailable() {
        StubLlmClient refusing = new StubLlmClient("OPENAI", null,
                new UnsupportedOperationException("no free-form completion"));
        PolicyDraftFromTextService svc = new PolicyDraftFromTextService(
                new LLMClientFactory(List.of(refusing)), openAiProperties(), translator, parser);

        Draft draft = svc.draftFromText("Archive stale data", null);

        assertEquals(Outcome.PROVIDER_UNAVAILABLE, draft.outcome());
    }

    @Test
    void providerCallFailure_isRejectedNotFabricated() {
        StubLlmClient failing = new StubLlmClient("OPENAI", null,
                new IllegalStateException("connection reset"));
        PolicyDraftFromTextService svc = new PolicyDraftFromTextService(
                new LLMClientFactory(List.of(failing)), openAiProperties(), translator, parser);

        Draft draft = svc.draftFromText("Archive stale data", null);

        assertEquals(Outcome.REJECTED, draft.outcome());
        assertNull(draft.dslYaml());
        assertTrue(draft.warnings().get(0).contains("connection reset"));
    }

    // ---- input guard ------------------------------------------------------

    @Test
    void blankText_isRejectedBeforeAnyLlmCall() {
        Draft draft = service("{}", openAiProperties()).draftFromText("   ", null);

        assertEquals(Outcome.REJECTED, draft.outcome());
        assertNull(draft.dslYaml());
    }

    // ---- fixtures ---------------------------------------------------------

    private PolicyDraftFromTextService service(String completion, LlmProperties props) {
        StubLlmClient stub = new StubLlmClient("OPENAI", completion, null);
        return new PolicyDraftFromTextService(
                new LLMClientFactory(List.of(stub)), props, translator, parser);
    }

    private static LlmProperties openAiProperties() {
        LlmProperties props = new LlmProperties();
        props.setProvider("openai");
        props.getOpenai().setApiKey("unit-test-placeholder-key");
        props.getOpenai().setModel("gpt-4o");
        return props;
    }

    /** Offline {@link ILLMClient} that replays one canned completion or throws. */
    private static final class StubLlmClient implements ILLMClient {

        private final String providerName;
        private final String completion;
        private final RuntimeException failure;

        StubLlmClient(String providerName, String completion, RuntimeException failure) {
            this.providerName = providerName;
            this.completion = completion;
            this.failure = failure;
        }

        @Override
        public String getProviderName() {
            return providerName;
        }

        @Override
        public String complete(String prompt, MLConfigRequest mlConfig) {
            if (failure != null) {
                throw failure;
            }
            return completion;
        }

        @Override
        public List<String> suggestLabels(String assetContent, MLConfigRequest mlConfig) {
            return List.of();
        }

        @Override
        public List<LabelSuggestion> suggestLabelsWithConfidence(String assetContent, MLConfigRequest mlConfig) {
            return List.of();
        }

        @Override
        public List<LLMResponse> batchSuggestLabels(List<String> assetContents, MLConfigRequest mlConfig) {
            return List.of();
        }

        @Override
        public boolean validateConfiguration(MLConfigRequest mlConfig) {
            return true;
        }

        @Override
        public Map<String, Object> getCapabilities() {
            return Map.of();
        }

        @Override
        public boolean testConnection(MLConfigRequest mlConfig) {
            return true;
        }
    }
}
