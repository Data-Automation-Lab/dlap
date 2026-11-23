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

package com.dlap.autodelete.retire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.dto.WouldDeleteRowDTO;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.providers.gcp.GcsLifecycleRuleGenerator;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.service.storage.CloudDeletionServiceFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * RET-02: the would-delete list shows what is held for deletion and the rule that would delete it.
 */
class WouldDeleteServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private final SoftDeleteRecordRepository records = mock(SoftDeleteRecordRepository.class);
    private final RetireDispositionRepository dispositions = mock(RetireDispositionRepository.class);

    private WouldDeleteService service() {
        NativeRuleApplier gcs = mock(NativeRuleApplier.class);
        when(gcs.platform()).thenReturn(NativeRulePlatform.GCS_LIFECYCLE);
        DispositionPlanner planner = new DispositionPlanner(List.of(new GcsLifecycleRuleGenerator()), List.of(gcs),
                new CloudDeletionServiceFactory(List.of()));
        return new WouldDeleteService(records, dispositions, planner, RetireProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void eachHeldAssetShowsTheRuleThatWouldDeleteItAtTheEndOfItsWindow() {
        SoftDeleteRecordEntity waiting = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("gs://dlap-gcp-e2e").cloudProvider("GCP").status(SoftDeleteRecordEntity.SOFT_DELETED)
                .scheduledHardDeleteAt(NOW.plus(Duration.ofDays(20))).windowDays(30).build();
        SoftDeleteRecordEntity nothing = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("bq://dlap-gcp-e2e/sales").cloudProvider("GCP").status(SoftDeleteRecordEntity.SOFT_DELETED)
                .scheduledHardDeleteAt(NOW.plus(Duration.ofDays(25))).build();
        SoftDeleteRecordEntity dry = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("gs://other").cloudProvider("GCP").status(SoftDeleteRecordEntity.DRY_RUN_COMPLETE)
                .scheduledHardDeleteAt(NOW.minus(Duration.ofDays(1))).build();
        when(records.findByStatusInOrderByScheduledHardDeleteAtAsc(eq(WouldDeleteService.STATES), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(dry, waiting, nothing), inv.getArgument(1), 3));
        when(dispositions.findBySoftDeleteRecordId(any())).thenReturn(Optional.empty());
        when(dispositions.findBySoftDeleteRecordId(dry.getId())).thenReturn(Optional.of(RetireDispositionEntity.builder()
                .mode("NATIVE_RULE").platform("GCS_LIFECYCLE").ruleId("dlap-retire-x").ruleDocument("{\"api\":\"x\"}")
                .status(RetireDispositionEntity.DRY_RUN).build()));

        Page<WouldDeleteRowDTO> page = service().list(0, 500);

        assertThat(page.getSize()).as("page size is capped").isEqualTo(200);
        WouldDeleteRowDTO dryRow = page.getContent().get(0);
        assertThat(dryRow.dispositionStatus()).isEqualTo("DRY_RUN");
        assertThat(dryRow.ruleDocument()).isEqualTo("{\"api\":\"x\"}");
        WouldDeleteRowDTO waitingRow = page.getContent().get(1);
        assertThat(waitingRow.plannedMode()).isEqualTo("NATIVE_RULE");
        assertThat(waitingRow.plannedPlatform()).isEqualTo("GCS_LIFECYCLE");
        assertThat(waitingRow.ruleDocument()).as("RET-06: age-based").contains("\"age\":1").doesNotContain("createdBefore");
        assertThat(waitingRow.dryRun()).isTrue();
        assertThat(page.getContent().get(2).plannedMode()).isEqualTo("NONE");
    }

    @Test
    void theListShowsOnlyStatesThatStillHoldTheAsset() {
        assertThat(WouldDeleteService.STATES).containsExactly(SoftDeleteRecordEntity.SOFT_DELETED,
                SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, SoftDeleteRecordEntity.DRY_RUN_COMPLETE,
                SoftDeleteRecordEntity.AWAITING_PROOF, SoftDeleteRecordEntity.RULE_IN_FORCE);
        when(records.findByStatusInOrderByScheduledHardDeleteAtAsc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        assertThat(service().list(-3, 50).getNumber()).isZero();
    }
}
