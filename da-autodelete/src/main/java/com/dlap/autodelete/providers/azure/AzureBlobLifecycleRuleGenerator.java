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

import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleGenerator;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.nativerule.RuleDocuments;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.CanonicalAssetId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Generates an Azure Blob Storage lifecycle management rule that deletes one container's blobs
 * ({@code BLOB_CONTAINER}).
 *
 * <p>The rule is one member of {@code properties.policy.rules} of the storage account's
 * management policy ({@code Microsoft.Storage/storageAccounts/managementPolicies}, policy name
 * {@code default}), with the ARM member names:
 * <pre>
 * {"enabled": true, "name": "dlapretireKEYHEX", "type": "Lifecycle",
 *  "definition": {
 *    "actions": {"baseBlob": {"delete": {"daysAfterModificationGreaterThan": N}},
 *                "snapshot": {"delete": {"daysAfterCreationGreaterThan": N}},
 *                "version":  {"delete": {"daysAfterCreationGreaterThan": N}}},
 *    "filters": {"blobTypes": ["blockBlob"], "prefixMatch": ["CONTAINER/"]}}}
 * </pre>
 * The rule is age-based (RET-06, Q-677): {@code N} is
 * {@code dlap.autodelete.native-rule.container-expiration-days}, at least 1, so a blob written after the
 * delete is not removed the same day (a zero-day rule is refused, {@code DATE_RULE_UNSUPPORTED}).
 * Snapshots and versions only know their creation time. The rule stays in force until a restore or
 * {@code dlap.autodelete.rule-in-force.max-age} removes it.
 * Rule names are alphanumeric, so the name is {@code dlapretire} and the key without dashes.
 * Lifecycle management runs about once a day. Append and page blobs are not covered by a
 * {@code blockBlob} filter and stay; the engine does not delete containers either.
 *
 * <p>The management policy belongs to the storage account's ARM resource, which the canonical URI
 * ({@code az://account/container}) does not name. The account must be listed in
 * {@code dlap.autodelete.native-rule.azure-storage-account-ids}; a container of an account not
 * listed has no native rule.
 */
@Component
public class AzureBlobLifecycleRuleGenerator implements NativeRuleGenerator {

    /** The only management policy name Azure accepts. */
    public static final String POLICY_NAME = "default";

    /** Prefix of DLAP's Azure rule names. */
    public static final String NAME_PREFIX = "dlapretire";

    private static final Pattern ACCOUNT_ID = Pattern.compile(
            "^/subscriptions/([^/]+)/resourceGroups/([^/]+)/providers/Microsoft\\.Storage/storageAccounts/([^/]+)$",
            Pattern.CASE_INSENSITIVE);

    private final RetireProperties properties;

    /**
     * Creates the generator.
     *
     * @param properties the executor settings, for the storage account ids
     */
    public AzureBlobLifecycleRuleGenerator(RetireProperties properties) {
        this.properties = properties;
    }

    @Override
    public NativeRulePlatform platform() {
        return NativeRulePlatform.AZURE_BLOB_LIFECYCLE;
    }

    @Override
    public boolean supports(CanonicalAssetId asset) {
        return asset != null && asset.kind() == AssetKind.BLOB_CONTAINER
                && storageAccountId(asset.parts().get(0)).isPresent();
    }

    @Override
    public NativeRule generate(CanonicalAssetId asset, UUID ruleKey, Instant now) {
        if (!supports(asset)) {
            throw new IllegalArgumentException("no Azure lifecycle rule for " + (asset == null ? null : asset.uri())
                    + " (is the storage account listed in dlap.autodelete.native-rule.azure-storage-account-ids?)");
        }
        String account = asset.parts().get(0);
        String container = asset.parts().get(1);
        String name = NAME_PREFIX + ruleKey.toString().replace("-", "");

        int days = properties.nativeRule().containerExpirationDays();
        Map<String, Object> byCreation = Map.of("delete", Map.of("daysAfterCreationGreaterThan", days));
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("baseBlob", Map.of("delete", Map.of("daysAfterModificationGreaterThan", days)));
        actions.put("snapshot", byCreation);
        actions.put("version", byCreation);
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("blobTypes", List.of("blockBlob"));
        filters.put("prefixMatch", List.of(container + "/"));
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("actions", actions);
        definition.put("filters", filters);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("enabled", true);
        rule.put("name", name);
        rule.put("type", "Lifecycle");
        rule.put("definition", definition);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("api", "Microsoft.Storage/storageAccounts/managementPolicies:createOrUpdate");
        doc.put("storageAccountId", storageAccountId(account).orElseThrow());
        doc.put("policyName", POLICY_NAME);
        doc.put("merge", "keep every existing rule; replace only a rule with the same name");
        doc.put("rules", List.of(rule));
        return new NativeRule(platform(), asset.uri(), name, now, RuleDocuments.write(doc));
    }

    /**
     * The configured ARM id of a storage account.
     *
     * @param account the account name
     * @return the ARM id, or empty
     */
    Optional<String> storageAccountId(String account) {
        for (String id : properties.nativeRule().azureStorageAccountIds()) {
            Matcher m = ACCOUNT_ID.matcher(id == null ? "" : id.trim());
            if (m.matches() && m.group(3).toLowerCase(Locale.ROOT).equals(account.toLowerCase(Locale.ROOT))) {
                return Optional.of(id.trim());
            }
        }
        return Optional.empty();
    }

    /**
     * Splits an ARM storage account id.
     *
     * @param id the id
     * @return subscription, resource group and account name
     * @throws IllegalArgumentException when the id is not a storage account id
     */
    static String[] parseAccountId(String id) {
        Matcher m = ACCOUNT_ID.matcher(id == null ? "" : id.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a storage account ARM id: " + id);
        }
        return new String[] {m.group(1), m.group(2), m.group(3)};
    }
}
