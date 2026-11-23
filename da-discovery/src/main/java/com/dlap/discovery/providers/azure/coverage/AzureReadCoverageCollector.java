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
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.dlap.discovery.coverage.CoverageVerdict;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.dlap.discovery.coverage.ReadCoverageCollector;
import com.dlap.discovery.coverage.ReadCoverageObservation;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Azure read coverage (COV-01): per storage account of each subscription, whether the blob
 * service's diagnostic settings record {@code StorageRead} ({@link AzureDiagnosticSettingsEvaluator}).
 * One observation per account, root {@code az://<account>}, for {@code BLOB_STORAGE},
 * {@code BLOB_CONTAINER} and {@code BLOB_OBJECT}, channel {@code AZURE_STORAGE_RESOURCE_LOG}, scope
 * the subscription id.
 *
 * <p>A failure of one account's diagnostic-settings read (not a refusal: a 404 for an account
 * deleted mid-run, a 429, a 5xx, a timeout) makes only that account's coverage unknown, with the
 * status as the reason, and the run goes on (Q-830, Q-474). A failure of the storage account
 * listing is not per account: the exception ends the collector, nothing is published, and the
 * catalog keeps what it had (fail closed). A listing the identity may not read gives that
 * subscription no observation.
 */
public class AzureReadCoverageCollector implements ReadCoverageCollector {

    private static final Logger log = LoggerFactory.getLogger(AzureReadCoverageCollector.class);

    /** Collector id. */
    public static final String ID = "azure-diagnostic-settings-coverage";
    /** Collector version. */
    public static final String VERSION = "1.0.0";
    /** Source kind that carries StorageBlobLogs records (log-event.md section 5.2). */
    public static final String CHANNEL = "AZURE_STORAGE_RESOURCE_LOG";

    private final AzureCoverageApi api;
    private final List<String> subscriptions;

    /**
     * Creates the collector.
     *
     * @param api           the Resource Manager reads
     * @param subscriptions the subscription ids
     */
    public AzureReadCoverageCollector(AzureCoverageApi api, List<String> subscriptions) {
        this.api = api;
        this.subscriptions = List.copyOf(subscriptions);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<ReadCoverageObservation> collect() {
        List<ReadCoverageObservation> out = new ArrayList<>();
        for (String subscription : subscriptions) {
            List<JsonNode> accounts;
            try {
                accounts = api.listStorageAccounts(subscription);
            } catch (MissingPermissionException e) {
                log.warn("Azure read coverage: {}; the subscription's accounts get no observation", e.reason());
                continue;
            }
            for (JsonNode account : accounts) {
                String id = account.path("id").asText("");
                String name = account.path("name").asText("").toLowerCase(Locale.ROOT);
                if (id.isBlank() || name.isBlank()) {
                    continue;
                }
                CoverageVerdict verdict;
                try {
                    verdict = AzureDiagnosticSettingsEvaluator.evaluate(api.listBlobDiagnosticSettings(id));
                } catch (MissingPermissionException e) {
                    verdict = CoverageVerdict.unknown(e);
                } catch (CoverageTransportException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw e;
                    }
                    // One account's settings are unreadable (deleted mid-run, throttled, a server error):
                    // that account is unknown with the status as the reason, and the run goes on (Q-830).
                    log.warn("Azure read coverage: the diagnostic settings of az://{} could not be read: {}", name,
                            e.getMessage());
                    verdict = CoverageVerdict.unknown(List.of(e.getMessage()));
                }
                out.add(new ReadCoverageObservation("AZURE", subscription, "az://" + name,
                        List.of("BLOB_STORAGE", "BLOB_CONTAINER", "BLOB_OBJECT"), CHANNEL, verdict, ID + "@" + VERSION));
            }
        }
        return out;
    }
}
