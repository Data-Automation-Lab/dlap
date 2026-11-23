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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.model.LLMResponse;
import com.dlap.llm.client.model.LabelSuggestion;

/**
 * The mock provider is a placeholder, but a reproducible one: the same input must
 * give the same output on every run so demos and tests are repeatable.
 */
class MockLLMClientTest {

    private final MockLLMClient mock = new MockLLMClient();
    private final MLConfigRequest config = MLConfigRequest.builder().providerType("MOCK").modelName("mock-model").build();

    @Test
    void suggestLabels_isPinnedToFixedValues() {
        // Golden values: Random(seed), String.hashCode and Collections.shuffle are all specified,
        // so these must hold on every JVM. A change here means the mock stopped being reproducible.
        assertThat(mock.suggestLabels("Asset name: customer_pii.csv", config))
                .containsExactly("Temporary", "Archive", "HealthRecord");
        assertThat(mock.suggestLabels("", config)).containsExactly("FinancialData", "Archive", "Temporary");
    }

    @Test
    void suggestLabelsWithConfidence_isPinnedToFixedValues() {
        List<LabelSuggestion> result = mock.suggestLabelsWithConfidence("Asset name: customer_pii.csv", config);

        assertThat(result.stream().map(s -> s.getLabel() + "@" + s.getConfidence()).toList())
                .containsExactly("Temporary@0.8", "Archive@0.66", "HealthRecord@0.64", "Public@0.63");
    }

    @Test
    void sameInput_sameOutput_acrossCallsAndInstances() {
        String input = "Asset name: orders_2026\nCloud provider: GCP";
        List<LabelSuggestion> expected = mock.suggestLabelsWithConfidence(input, config);

        for (int i = 0; i < 25; i++) {
            assertThat(mock.suggestLabelsWithConfidence(input, config)).isEqualTo(expected);
            assertThat(new MockLLMClient().suggestLabelsWithConfidence(input, null)).isEqualTo(expected);
            assertThat(new MockLLMClient().suggestLabels(input, null)).isEqualTo(mock.suggestLabels(input, config));
        }
    }

    @Test
    void differentInputs_canGiveDifferentOutputs() {
        Set<List<String>> distinct = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            distinct.add(mock.suggestLabels("asset-" + i, config));
        }

        assertThat(distinct.size()).isGreaterThan(5);
    }

    @Test
    void suggestions_areDistinct_boundedAndHonestAboutBeingPlaceholders() {
        for (int i = 0; i < 100; i++) {
            List<LabelSuggestion> result = mock.suggestLabelsWithConfidence("asset-" + i, config);

            assertThat(result.size()).isBetween(2, 5);
            assertThat(result.stream().map(LabelSuggestion::getLabel).distinct().count()).isEqualTo(result.size());
            for (LabelSuggestion s : result) {
                assertThat(s.getConfidence()).isBetween(0.5, 1.0);
                assertThat(s.getReasoning()).contains("MOCK placeholder").contains("not derived from the asset");
                assertThat(s.getCategory()).isEqualTo("MOCK_CATEGORY");
                assertThat(s.getSource()).isNull();
                assertThat(s.getRuleId()).isNull();
            }
            List<String> plain = mock.suggestLabels("asset-" + i, config);
            assertThat(plain.size()).isBetween(1, 3);
            assertThat(new HashSet<>(plain)).hasSameSizeAs(plain);
        }
    }

    @Test
    void nullInputs_areTolerated() {
        assertThat(mock.suggestLabels(null, null)).isNotEmpty();
        assertThat(mock.suggestLabelsWithConfidence(null, null)).isNotEmpty();
        assertThat(mock.suggestLabels(null, null)).isEqualTo(mock.suggestLabels("", null));
        assertThat(mock.validateConfiguration(null)).isTrue();
        assertThat(mock.testConnection(null)).isTrue();
        assertThat(mock.batchSuggestLabels(null, null)).isEmpty();
    }

    @Test
    void batch_isDeterministicAndStampsTheInjectedClock() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC);
        MockLLMClient pinned = new MockLLMClient(fixed);
        List<String> contents = new ArrayList<>(List.of("a", "b", "c"));

        List<LLMResponse> first = pinned.batchSuggestLabels(contents, config);
        List<LLMResponse> second = pinned.batchSuggestLabels(contents, config);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(3);
        assertThat(first.get(0).getProvider()).isEqualTo("MOCK");
        assertThat(first.get(0).getModelName()).isEqualTo("mock-model");
        assertThat(first.get(0).getTimestamp()).isEqualTo(LocalDateTime.of(2026, 10, 19, 8, 0, 0));
        assertThat(first.get(0).getSuccessful()).isTrue();
    }

    @Test
    void doesNotSleep() {
        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            mock.suggestLabels("asset-" + i, config);
            mock.suggestLabelsWithConfidence("asset-" + i, config);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The previous implementation slept 50-300 ms per call, so 400 calls took over 20 s.
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void capabilities_doNotClaimReasoningOrAssetDerivedLabels() {
        assertThat(mock.getCapabilities())
                .containsEntry("provider", "MOCK")
                .containsEntry("deterministic", true)
                .containsEntry("derivedFromAsset", false)
                .containsEntry("supportsReasoning", false);
    }

    @Test
    void complete_isUnsupported() {
        assertThatThrownBy(() -> mock.complete("anything", config))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("MOCK");
    }
}
