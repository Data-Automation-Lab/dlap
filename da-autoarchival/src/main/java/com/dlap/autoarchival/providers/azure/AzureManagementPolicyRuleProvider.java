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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RuleException.Reason;
import com.dlap.autoarchival.rule.RuleScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Azure Blob lifecycle management policy rules (tier to Cool, Cold or Archive).
 *
 * <p>A management policy belongs to the storage account and is written through Azure Resource
 * Manager, a control-plane API with its own permission ({@code Storage Account Contributor}) that
 * this service does not hold and has no SDK for. DLAP therefore <b>renders</b> the rule document
 * ({@link #render}); the task ends in {@code RULE_RENDERED} and a person or a pipeline applies the
 * document (for example {@code az storage account management-policy create}). That is an honest
 * "not applied", never a false "done". The listing reads the container through the blob data
 * plane with {@code DefaultAzureCredential} and the account endpoint ({@link AzureBlobClients}).
 *
 * <p>Azure allows 100 rules per account policy; DLAP cannot read the policy, so the rule count is
 * unknown ({@code -1}) and only DLAP's own cap applies.
 */
@Component
public class AzureManagementPolicyRuleProvider implements NativeRuleProvider {

    /** Azure allows at most this many rules in an account's management policy. */
    static final int MAX_RULES = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AzureBlobClients clients;
    private final boolean mock;

    /**
     * Creates the provider on a deprecated connection string (tests); an empty string means no explicit endpoint.
     *
     * @param connectionString the data-plane connection string for listings (deprecated); empty for none
     * @param mock             {@code dlap.autoarchival.azure.mock-mode}: the direct tiering of one blob is mocked
     */
    public AzureManagementPolicyRuleProvider(String connectionString, boolean mock) {
        this(new AzureBlobClients("", connectionString, ""), mock);
    }

    /**
     * Creates the provider the way Spring does: listings go through {@code DefaultAzureCredential} (EXEC-01, Q-724).
     *
     * @param clients the Azure blob clients
     * @param mock    {@code dlap.autoarchival.azure.mock-mode}: the direct tiering of one blob is mocked
     */
    @Autowired
    public AzureManagementPolicyRuleProvider(AzureBlobClients clients,
            @Value("${dlap.autoarchival.azure.mock-mode:false}") boolean mock) {
        this.clients = clients;
        this.mock = mock;
    }

    @Override
    public boolean isMock() {
        return mock;
    }

    @Override
    public String provider() {
        return "AZURE";
    }

    @Override
    public boolean canApply() {
        return false;
    }

    @Override
    public Inventory inventory(RuleSpec spec) {
        return new Inventory(-1, MAX_RULES, false);
    }

    @Override
    public Listing list(RuleScope scope, int maxKeys) {
        if (!clients.configured()) {
            return new Listing(List.of(), false, maxKeys, 0L, false,
                    "no Azure account endpoint (dlap.autoarchival.azure.account-endpoint) is configured; "
                            + "the listing is unavailable");
        }
        try {
            BlobContainerClient container = clients.serviceClient(scope.account())
                    .getBlobContainerClient(scope.bucket());
            ListBlobsOptions options = new ListBlobsOptions().setPrefix(scope.prefix().isEmpty() ? null : scope.prefix())
                    .setMaxResultsPerPage(Math.min(maxKeys + 1, 5000));
            List<ListingEntry> entries = new ArrayList<>();
            long bytes = 0;
            boolean more = false;
            for (BlobItem item : container.listBlobs(options, java.time.Duration.ofSeconds(30))) {
                if (entries.size() == maxKeys) {
                    more = true;
                    break;
                }
                long size = item.getProperties() == null || item.getProperties().getContentLength() == null
                        ? 0L : item.getProperties().getContentLength();
                bytes += size;
                String tier = item.getProperties() == null || item.getProperties().getAccessTier() == null
                        ? "" : item.getProperties().getAccessTier().toString();
                entries.add(new ListingEntry(item.getName(), size, tier));
            }
            return new Listing(entries, more, maxKeys, bytes, true, "listBlobs, first " + maxKeys + " blobs");
        } catch (RuntimeException e) {
            throw new RuleException(Reason.PROVIDER_ERROR, "Azure blob listing failed: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public String render(RuleSpec spec) {
        String action = switch (spec.nativeClass()) {
            case "Cool" -> "tierToCool";
            case "Cold" -> "tierToCold";
            case "Archive" -> "tierToArchive";
            default -> throw new RuleException(Reason.INVALID_TIER, "not an Azure tier: " + spec.nativeClass());
        };
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("blobTypes", List.of("blockBlob"));
        filters.put("prefixMatch", List.of(spec.scope().bucket() + "/" + spec.scope().prefix()));
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("enabled", true);
        rule.put("name", spec.ruleId());
        rule.put("type", "Lifecycle");
        rule.put("definition", Map.of(
                "actions", Map.of("baseBlob", Map.of(action, Map.of("daysAfterModificationGreaterThan", spec.afterDays()))),
                "filters", filters));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("rules", List.of(rule)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot render the Azure rule", e);
        }
    }

    @Override
    public ApplyResult apply(RuleSpec spec) {
        throw new RuleException(Reason.APPLY_NOT_SUPPORTED,
                "an Azure management policy is written through Azure Resource Manager; apply the rendered document");
    }

    @Override
    public boolean remove(RuleSpec spec) {
        throw new RuleException(Reason.APPLY_NOT_SUPPORTED,
                "an Azure management policy is changed through Azure Resource Manager; remove the rule there");
    }
}
