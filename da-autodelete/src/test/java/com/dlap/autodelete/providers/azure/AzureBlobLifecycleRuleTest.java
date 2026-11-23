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

package com.dlap.autodelete.providers.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpResponse;
import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.storage.fluent.ManagementPoliciesClient;
import com.azure.resourcemanager.storage.fluent.models.ManagementPolicyInner;
import com.azure.resourcemanager.storage.models.ManagementPolicyName;
import com.azure.resourcemanager.storage.models.ManagementPolicyRule;
import com.azure.resourcemanager.storage.models.ManagementPolicySchema;
import com.azure.resourcemanager.storage.models.RuleType;
import com.dlap.autodelete.nativerule.ContainerRuleAge;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.RuleDocuments;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.CanonicalAssetId;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Azure Blob lifecycle management rule generation and application, checked against the ARM shape
 * as the Azure SDK's management serializer reads and writes it.
 */
class AzureBlobLifecycleRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:15:00Z");
    private static final UUID KEY = UUID.fromString("abcdabcd-0000-4000-8000-0000000000c1");
    private static final String ACCOUNT_ID =
            "/subscriptions/00000000-0000-4000-8000-000000000001/resourceGroups/rg-data/providers/Microsoft.Storage/storageAccounts/dlapazuree2e";

    private static AzureBlobLifecycleRuleGenerator generator(List<String> accounts) {
        return new AzureBlobLifecycleRuleGenerator(new RetireProperties(true, 30, 7, 2, 100, null, null,
                new RetireProperties.NativeRule(Duration.ofHours(1), accounts, 1), null));
    }

    private static CanonicalAssetId container() {
        return AssetIdNormalizer.normalize("az://dlapazuree2e/raw").orElseThrow();
    }

    private static NativeRule rule() {
        return generator(List.of(ACCOUNT_ID)).generate(container(), KEY, NOW);
    }

    /** Q-677 (RET-06): the container rule is age-based (daysAfterModificationGreaterThan of at least 1). */
    @Test
    void theRuleRoundTripsThroughTheArmModelUnchanged() {
        JsonNode doc = RuleDocuments.read(rule().document());
        JsonNode generated = doc.path("rules").get(0);
        ManagementPolicyRule sdk = AzureBlobLifecycleRuleApplier.toSdk(generated);

        JsonNode roundTrip = RuleDocuments.read(AzureBlobLifecycleRuleApplier.toJson(sdk));
        assertThat(roundTrip.equals(AzureBlobLifecycleRuleTest::sameValue, generated))
                .as("every member is an ARM member: a round trip through the SDK model drops nothing; got %s",
                        roundTrip)
                .isTrue();
        assertThat(sdk.name()).isEqualTo("dlapretire" + KEY.toString().replace("-", "")).matches("[A-Za-z0-9]+");
        assertThat(sdk.type()).isEqualTo(RuleType.LIFECYCLE);
        assertThat(sdk.enabled()).isTrue();
        assertThat(sdk.definition().filters().prefixMatch()).containsExactly("raw/");
        assertThat(sdk.definition().filters().blobTypes()).containsExactly("blockBlob");
        // Q-677 (RET-06): age-based, at least one day; the base blob by its modification time.
        assertThat(sdk.definition().actions().baseBlob().delete().daysAfterModificationGreaterThan()).isEqualTo(1f);
        assertThat(sdk.definition().actions().baseBlob().delete().daysAfterCreationGreaterThan()).isNull();
        assertThat(sdk.definition().actions().snapshot().delete().daysAfterCreationGreaterThan()).isEqualTo(1f);
        assertThat(sdk.definition().actions().version().delete().daysAfterCreationGreaterThan()).isEqualTo(1f);
        assertThat(ContainerRuleAge.violation(rule())).isEmpty();
        assertThat(doc.path("storageAccountId").asText()).isEqualTo(ACCOUNT_ID);
        assertThat(doc.path("policyName").asText()).isEqualTo("default");
    }

    @Test
    void aContainerOfAnUnlistedAccountOrABlobHasNoRule() {
        assertThat(generator(List.of()).supports(container())).isFalse();
        assertThat(generator(List.of(ACCOUNT_ID)).supports(
                AssetIdNormalizer.normalize("az://dlapazuree2e/raw/a.csv").orElseThrow())).isFalse();
        assertThatThrownBy(() -> generator(List.of()).generate(container(), KEY, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("azure-storage-account-ids");
    }

    @Test
    void theApplierKeepsTheOwnersRulesAndReplacesOnlyItsOwn() {
        ManagementPoliciesClient client = mock(ManagementPoliciesClient.class);
        ManagementPolicyRule owners = new ManagementPolicyRule().withName("ownerTiering").withEnabled(true)
                .withType(RuleType.LIFECYCLE);
        ManagementPolicyRule oldOurs = new ManagementPolicyRule().withName("dlapretire" + KEY.toString().replace("-", ""))
                .withEnabled(false).withType(RuleType.LIFECYCLE);
        when(client.get("rg-data", "dlapazuree2e", ManagementPolicyName.DEFAULT)).thenReturn(new ManagementPolicyInner()
                .withPolicy(new ManagementPolicySchema().withRules(List.of(owners, oldOurs))));

        new AzureBlobLifecycleRuleApplier(sub -> {
            assertThat(sub).isEqualTo("00000000-0000-4000-8000-000000000001");
            return client;
        }).apply(rule());

        ArgumentCaptor<ManagementPolicyInner> put = ArgumentCaptor.forClass(ManagementPolicyInner.class);
        verify(client).createOrUpdate(eq("rg-data"), eq("dlapazuree2e"), eq(ManagementPolicyName.DEFAULT), put.capture());
        List<ManagementPolicyRule> written = put.getValue().policy().rules();
        assertThat(written).hasSize(2);
        assertThat(written.get(0)).isSameAs(owners);
        assertThat(written.get(1).enabled()).isTrue();
    }

    /** Q-631: a restore of a rule in force takes out DLAP's rule only. */
    @Test
    void removeTakesOutOnlyDlapsRuleAndKeepsTheOwnersRules() {
        ManagementPoliciesClient client = mock(ManagementPoliciesClient.class);
        ManagementPolicyRule owners = new ManagementPolicyRule().withName("ownerTiering").withEnabled(true)
                .withType(RuleType.LIFECYCLE);
        ManagementPolicyRule ours = new ManagementPolicyRule().withName("dlapretire" + KEY.toString().replace("-", ""))
                .withEnabled(true).withType(RuleType.LIFECYCLE);
        when(client.get("rg-data", "dlapazuree2e", ManagementPolicyName.DEFAULT)).thenReturn(new ManagementPolicyInner()
                .withPolicy(new ManagementPolicySchema().withRules(List.of(owners, ours))));

        assertThat(new AzureBlobLifecycleRuleApplier(sub -> client).remove(rule())).isTrue();

        ArgumentCaptor<ManagementPolicyInner> put = ArgumentCaptor.forClass(ManagementPolicyInner.class);
        verify(client).createOrUpdate(eq("rg-data"), eq("dlapazuree2e"), eq(ManagementPolicyName.DEFAULT), put.capture());
        assertThat(put.getValue().policy().rules()).containsExactly(owners);
        verify(client, never()).delete(any(), any(), any());
    }

    @Test
    void removeOfTheOnlyRuleDeletesThePolicyAndOfAMissingRuleChangesNothing() {
        ManagementPoliciesClient client = mock(ManagementPoliciesClient.class);
        ManagementPolicyRule ours = new ManagementPolicyRule().withName("dlapretire" + KEY.toString().replace("-", ""))
                .withEnabled(true).withType(RuleType.LIFECYCLE);
        when(client.get("rg-data", "dlapazuree2e", ManagementPolicyName.DEFAULT)).thenReturn(new ManagementPolicyInner()
                .withPolicy(new ManagementPolicySchema().withRules(List.of(ours))));
        assertThat(new AzureBlobLifecycleRuleApplier(sub -> client).remove(rule())).isTrue();
        verify(client).delete("rg-data", "dlapazuree2e", ManagementPolicyName.DEFAULT);

        ManagementPoliciesClient gone = mock(ManagementPoliciesClient.class);
        HttpResponse notFound = mock(HttpResponse.class);
        when(notFound.getStatusCode()).thenReturn(404);
        when(gone.get(any(), any(), any())).thenThrow(new ManagementException("not found", notFound));
        assertThat(new AzureBlobLifecycleRuleApplier(sub -> gone).remove(rule()))
                .as("Q-682: a missing rule is reported as not found").isFalse();
        verify(gone, never()).delete(any(), any(), any());
        verify(gone, never()).createOrUpdate(any(), any(), any(), any());
    }

    @Test
    void anArmRefusalOfTheRemovalBecomesANativeRuleException() {
        ManagementPoliciesClient client = mock(ManagementPoliciesClient.class);
        HttpResponse forbidden = mock(HttpResponse.class);
        when(forbidden.getStatusCode()).thenReturn(403);
        when(client.get(any(), any(), any())).thenThrow(new ManagementException("forbidden", forbidden));

        assertThatThrownBy(() -> new AzureBlobLifecycleRuleApplier(sub -> client).remove(rule()))
                .isInstanceOf(NativeRuleException.class);
    }

    @Test
    void anAccountWithoutAPolicyGetsJustTheRule() {
        ManagementPoliciesClient client = mock(ManagementPoliciesClient.class);
        HttpResponse notFound = mock(HttpResponse.class);
        when(notFound.getStatusCode()).thenReturn(404);
        when(client.get(any(), any(), any())).thenThrow(new ManagementException("not found", notFound));

        new AzureBlobLifecycleRuleApplier(sub -> client).apply(rule());

        ArgumentCaptor<ManagementPolicyInner> put = ArgumentCaptor.forClass(ManagementPolicyInner.class);
        verify(client).createOrUpdate(any(), any(), any(), put.capture());
        assertThat(put.getValue().policy().rules()).hasSize(1);
    }

    @Test
    void anArmRefusalBecomesANativeRuleExceptionAndNothingIsWritten() {
        ManagementPoliciesClient client = mock(ManagementPoliciesClient.class);
        HttpResponse forbidden = mock(HttpResponse.class);
        when(forbidden.getStatusCode()).thenReturn(403);
        when(client.get(any(), any(), any())).thenThrow(new ManagementException("forbidden", forbidden));

        assertThatThrownBy(() -> new AzureBlobLifecycleRuleApplier(sub -> client).apply(rule()))
                .isInstanceOf(NativeRuleException.class);
        verify(client, never()).createOrUpdate(any(), any(), any(), any());
    }

    /** JSON equality where 0 and 0.0 are the same number (the SDK models day counts as floats). */
    private static int sameValue(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return a.equals(b) ? 0 : 1;
    }
}
