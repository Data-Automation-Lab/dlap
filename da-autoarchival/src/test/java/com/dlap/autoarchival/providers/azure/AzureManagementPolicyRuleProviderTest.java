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

package com.dlap.autoarchival.providers.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.dlap.autoarchival.rule.NativeRuleProvider.RuleSpec;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RuleScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class AzureManagementPolicyRuleProviderTest {

    private final AzureManagementPolicyRuleProvider provider = new AzureManagementPolicyRuleProvider("", false);

    private static RuleSpec spec(String nativeClass, String prefix, int days) {
        return new RuleSpec("dlap-archive-0123456789ab", new RuleScope("AZURE", "acct", "raw", prefix, false),
                nativeClass, days);
    }

    @Test
    void rendersTheManagementPolicyRuleForEachTier() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String[] t : new String[][] {{"Cool", "tierToCool"}, {"Cold", "tierToCold"}, {"Archive", "tierToArchive"}}) {
            JsonNode rule = json.readTree(provider.render(spec(t[0], "2019/", 30))).path("rules").get(0);
            assertThat(rule.path("name").asText()).isEqualTo("dlap-archive-0123456789ab");
            assertThat(rule.path("enabled").asBoolean()).isTrue();
            assertThat(rule.path("type").asText()).isEqualTo("Lifecycle");
            JsonNode base = rule.path("definition").path("actions").path("baseBlob");
            assertThat(base.fieldNames().next()).isEqualTo(t[1]);
            assertThat(base.path(t[1]).path("daysAfterModificationGreaterThan").asInt()).isEqualTo(30);
            assertThat(rule.path("definition").path("filters").path("prefixMatch").get(0).asText())
                    .isEqualTo("raw/2019/");
            assertThat(rule.path("definition").path("filters").path("blobTypes").get(0).asText()).isEqualTo("blockBlob");
        }
    }

    @Test
    void aWholeContainerRuleMatchesTheContainerName() throws Exception {
        JsonNode rule = new ObjectMapper().readTree(provider.render(spec("Cool", "", 0))).path("rules").get(0);
        assertThat(rule.path("definition").path("filters").path("prefixMatch").get(0).asText()).isEqualTo("raw/");
    }

    @Test
    void refusesToRenderAClassThatIsNotAnAzureTier() {
        assertThatThrownBy(() -> provider.render(spec("COLDLINE", "", 0))).isInstanceOfSatisfying(RuleException.class,
                e -> assertThat(e.reason()).isEqualTo(RuleException.Reason.INVALID_TIER));
    }

    @Test
    void doesNotPretendToApplyTheRule() {
        assertThat(provider.canApply()).isFalse();
        assertThatThrownBy(() -> provider.apply(spec("Cool", "", 0))).isInstanceOfSatisfying(RuleException.class,
                e -> assertThat(e.reason()).isEqualTo(RuleException.Reason.APPLY_NOT_SUPPORTED));
        assertThatThrownBy(() -> provider.remove(spec("Cool", "", 0))).isInstanceOf(RuleException.class);
    }

    @Test
    void theRuleCountIsUnknownBecauseThePolicyIsNotReadable() {
        assertThat(provider.inventory(spec("Cool", "", 0)).totalRules()).isEqualTo(-1);
        assertThat(provider.inventory(spec("Cool", "", 0)).providerMax()).isEqualTo(100);
    }

    @Test
    void theListingIsReportedUnavailableWithoutAConnectionString() {
        var listing = provider.list(new RuleScope("AZURE", "acct", "raw", "", false), 10);
        assertThat(listing.available()).isFalse();
        assertThat(listing.entries()).isEmpty();
        assertThat(listing.note()).contains("unavailable");
    }

    @Test
    void mockModeIsReported() {
        assertThat(new AzureManagementPolicyRuleProvider("", true).isMock()).isTrue();
        assertThat(provider.isMock()).isFalse();
    }
}
