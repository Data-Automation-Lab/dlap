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

package com.dlap.discovery.providers.azure.coverage;

import java.util.ArrayList;
import java.util.List;

import com.dlap.discovery.coverage.CoverageVerdict;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Decides from the diagnostic settings of a storage account's blob service whether blob reads are
 * recorded (COV-01). Pure: it reads the Azure Monitor "Diagnostic Settings - List" response
 * (api-version 2021-05-01-preview) as a JSON tree.
 *
 * <p>Blob reads are the {@code StorageRead} category of the
 * {@code Microsoft.Storage/storageAccounts/blobServices} resource (Azure Monitor, supported log
 * categories), written to {@code StorageBlobLogs}. A setting records them when it has a
 * destination (a Log Analytics workspace, a storage account, an event hub or a partner solution)
 * and enables {@code StorageRead} by category or through the {@code allLogs} category group,
 * which holds every category. The {@code audit} group is not documented to hold
 * {@code StorageRead}, so it gives at most {@code PARTIAL}.
 *
 * <ul>
 *   <li>No setting on the blob service: {@code NONE}.</li>
 *   <li>Settings, none of which records {@code StorageRead}: {@code PARTIAL}, "diagnostic setting
 *       without StorageRead" (the spec's wording; nothing of the reads is recorded, and every level
 *       but {@code FULL} counts as unknown for destructive actions).</li>
 *   <li>One setting that records {@code StorageRead} to a destination: {@code FULL}.</li>
 * </ul>
 */
public final class AzureDiagnosticSettingsEvaluator {

    /** The spec's wording for settings that leave out {@code StorageRead}. */
    public static final String WITHOUT_STORAGE_READ = "diagnostic setting without StorageRead";

    private AzureDiagnosticSettingsEvaluator() {
    }

    /**
     * The verdict for one storage account.
     *
     * @param settings the diagnostic settings of {@code <account id>/blobServices/default}
     * @return the verdict
     */
    public static CoverageVerdict evaluate(JsonNode settings) {
        JsonNode list = settings.path("value");
        if (list.isEmpty()) {
            return CoverageVerdict.none(List.of("no diagnostic setting on the blob service"));
        }
        List<String> gaps = new ArrayList<>();
        boolean withoutRead = false;
        for (JsonNode setting : list) {
            String name = setting.path("name").asText("unnamed");
            JsonNode p = setting.path("properties");
            String reads = reads(p.path("logs"));
            String destination = destination(p);
            if ("full".equals(reads) && destination != null) {
                return CoverageVerdict.full(List.of("diagnostic setting " + name + " sends StorageRead to " + destination));
            }
            if ("full".equals(reads)) {
                gaps.add("diagnostic setting " + name + " enables StorageRead but has no destination");
            } else if ("audit".equals(reads)) {
                gaps.add("diagnostic setting " + name + " enables the audit category group, which is not documented "
                        + "to hold StorageRead");
            } else {
                withoutRead = true;
            }
        }
        if (withoutRead) {
            gaps.add(0, WITHOUT_STORAGE_READ);
        }
        return CoverageVerdict.partial(gaps);
    }

    /** {@code full} when StorageRead is on, {@code audit} for the audit group only, else {@code null}. */
    private static String reads(JsonNode logs) {
        String result = null;
        for (JsonNode log : logs) {
            if (!log.path("enabled").asBoolean(false)) {
                continue;
            }
            String category = log.path("category").asText("");
            String group = log.path("categoryGroup").asText("");
            if ("StorageRead".equalsIgnoreCase(category) || "allLogs".equalsIgnoreCase(group)) {
                return "full";
            }
            if ("audit".equalsIgnoreCase(group)) {
                result = "audit";
            }
        }
        return result;
    }

    /** The kind of destination, or {@code null} when the setting has none. */
    private static String destination(JsonNode p) {
        if (!p.path("workspaceId").asText("").isBlank()) {
            return "a Log Analytics workspace";
        }
        if (!p.path("storageAccountId").asText("").isBlank()) {
            return "a storage account";
        }
        if (!p.path("eventHubAuthorizationRuleId").asText("").isBlank()) {
            return "an event hub";
        }
        if (!p.path("marketplacePartnerId").asText("").isBlank()) {
            return "a partner solution";
        }
        return null;
    }
}
