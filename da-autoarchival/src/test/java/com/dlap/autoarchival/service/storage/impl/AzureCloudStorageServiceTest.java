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

package com.dlap.autoarchival.service.storage.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.autoarchival.service.storage.ArchivalResult;
import com.dlap.autoarchival.service.storage.CostSavingsEstimate;
import com.dlap.autoarchival.service.storage.OperationStatus;
import com.dlap.autoarchival.service.storage.RestorationResult;

/**
 * A real Azure-backed {@link com.dlap.autoarchival.service.storage.ICloudStorageService} so an
 * Azure archival task dispatches to Azure Blob Storage instead of failing closed. These tests
 * exercise the default mock mode (no Azure connection string required) plus the
 * provider/tier/cost contract used by the dispatcher.
 */
class AzureCloudStorageServiceTest {

    // mock mode, blank connection string - never touches the Azure SDK.
    private final AzureCloudStorageService service = new AzureCloudStorageService(true, "");

    @Test
    void supportsCloudProvider_azureAliasesOnly() {
        assertThat(service.supportsCloudProvider("AZURE")).isTrue();
        assertThat(service.supportsCloudProvider("azure")).isTrue();
        assertThat(service.supportsCloudProvider("AZ")).isTrue();
        assertThat(service.supportsCloudProvider("AWS")).isFalse();
        assertThat(service.supportsCloudProvider("GCP")).isFalse();
    }

    @Test
    void getAvailableStorageTiers_azureTiers() {
        assertThat(service.getAvailableStorageTiers("AZURE"))
                .containsExactly("HOT", "COOL", "COLD", "ARCHIVE");
        assertThat(service.getAvailableStorageTiers("AWS")).isEmpty();
    }

    @Test
    void archiveData_mockMode_completesAndMapsGlacierTierToArchive() {
        // The shared archival config's default tier is the AWS term GLACIER; the Azure service
        // maps it to the ARCHIVE tier rather than throwing.
        ArchivalResult result = service.archiveData(
                UUID.randomUUID(), "https://acct.blob.core.windows.net/c/b", "GLACIER", Map.of("k", "v"));

        assertThat(result.getStatus()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(result.getTargetTier()).isEqualTo("ARCHIVE");
        assertThat(result.getArchivedLocation()).isEqualTo("https://acct.blob.core.windows.net/c/b");
    }

    @Test
    void restoreData_mockMode_completesToHot() {
        RestorationResult result = service.restoreData(
                UUID.randomUUID(), "https://acct.blob.core.windows.net/c/b", "STANDARD");

        assertThat(result.getStatus()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(result.getTargetTier()).isEqualTo("HOT");
    }

    @Test
    void statusLookups_mockMode_completed() {
        assertThat(service.getArchivalStatus("https://acct.blob.core.windows.net/c/b"))
                .isEqualTo(OperationStatus.COMPLETED);
        assertThat(service.getRestorationStatus("https://acct.blob.core.windows.net/c/b"))
                .isEqualTo(OperationStatus.COMPLETED);
    }

    @Test
    void calculateCostSavings_archiveIsCheaperThanHot() {
        long tenGb = 10L * 1024 * 1024 * 1024;
        CostSavingsEstimate est = service.calculateCostSavings(
                "https://acct.blob.core.windows.net/c/b", "ARCHIVE", tenGb);

        assertThat(est.getTargetStorageClass()).isEqualTo("ARCHIVE");
        assertThat(est.getArchivedMonthlyCost()).isLessThan(est.getCurrentMonthlyCost());
        assertThat(est.getMonthlySavings()).isPositive();
        assertThat(est.getSavingsPercentage()).isGreaterThan(90.0); // ARCHIVE ≈ 5% of HOT
        assertThat(est.getCurrency()).isEqualTo("USD");
    }
}
