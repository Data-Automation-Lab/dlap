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
 * Choke-point #4 (Part B): a real GCP-backed {@link com.dlap.autoarchival.service.storage.ICloudStorageService}
 * so a {@code gs://} archival task dispatches to GCS instead of failing closed. These tests
 * exercise the default mock mode (no GCS credentials required) plus the provider/tier/cost
 * contract used by the dispatcher.
 */
class GcsCloudStorageServiceTest {

    private final GcsCloudStorageService service = new GcsCloudStorageService(true); // mock mode

    @Test
    void supportsCloudProvider_gcpAliasesOnly() {
        assertThat(service.supportsCloudProvider("GCP")).isTrue();
        assertThat(service.supportsCloudProvider("gcp")).isTrue();
        assertThat(service.supportsCloudProvider("GOOGLE")).isTrue();
        assertThat(service.supportsCloudProvider("GCS")).isTrue();
        assertThat(service.supportsCloudProvider("AWS")).isFalse();
        assertThat(service.supportsCloudProvider("AZURE")).isFalse();
    }

    @Test
    void getAvailableStorageTiers_gcpTiers() {
        assertThat(service.getAvailableStorageTiers("GCP"))
                .containsExactly("STANDARD", "NEARLINE", "COLDLINE", "ARCHIVE");
        assertThat(service.getAvailableStorageTiers("AWS")).isEmpty();
    }

    @Test
    void archiveData_mockMode_completesAndMapsGlacierTierToArchive() {
        // The shared archival config's default tier is the AWS term GLACIER; the GCS service
        // maps it to the ARCHIVE class rather than throwing.
        ArchivalResult result = service.archiveData(
                UUID.randomUUID(), "gs://bucket/object", "GLACIER", Map.of("k", "v"));

        assertThat(result.getStatus()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(result.getTargetTier()).isEqualTo("ARCHIVE");
        assertThat(result.getArchivedLocation()).isEqualTo("gs://bucket/object");
    }

    @Test
    void restoreData_mockMode_completes() {
        RestorationResult result = service.restoreData(
                UUID.randomUUID(), "gs://bucket/object", "STANDARD");

        assertThat(result.getStatus()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(result.getTargetTier()).isEqualTo("STANDARD");
    }

    @Test
    void statusLookups_mockMode_completed() {
        assertThat(service.getArchivalStatus("gs://bucket/object")).isEqualTo(OperationStatus.COMPLETED);
        assertThat(service.getRestorationStatus("gs://bucket/object")).isEqualTo(OperationStatus.COMPLETED);
    }

    @Test
    void calculateCostSavings_archiveIsCheaperThanStandard() {
        long tenGb = 10L * 1024 * 1024 * 1024;
        CostSavingsEstimate est = service.calculateCostSavings("gs://bucket/object", "ARCHIVE", tenGb);

        assertThat(est.getTargetStorageClass()).isEqualTo("ARCHIVE");
        assertThat(est.getArchivedMonthlyCost()).isLessThan(est.getCurrentMonthlyCost());
        assertThat(est.getMonthlySavings()).isPositive();
        assertThat(est.getSavingsPercentage()).isGreaterThan(90.0); // ARCHIVE ≈ 6% of STANDARD
        assertThat(est.getCurrency()).isEqualTo("USD");
    }
}
