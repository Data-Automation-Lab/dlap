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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.providers.aws.S3LifecycleRuleGenerator;
import com.dlap.autodelete.providers.azure.AzureBlobLifecycleRuleGenerator;
import com.dlap.autodelete.providers.gcp.BigQueryTableExpirationGenerator;
import com.dlap.autodelete.providers.gcp.GcsLifecycleRuleGenerator;
import com.dlap.autodelete.service.storage.CloudDeletionServiceFactory;
import com.dlap.autodelete.service.storage.ICloudDeletionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Decision D20: native rule first, the engine only for kinds with no exact native rule, nothing for
 * the rest.
 */
class DispositionPlannerTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final UUID KEY = UUID.fromString("abcdabcd-0000-4000-8000-0000000000c1");
    private static final String ACCOUNT_ID =
            "/subscriptions/00000000-0000-4000-8000-000000000001/resourceGroups/rg-data/providers/Microsoft.Storage/storageAccounts/dlapazuree2e";

    private static NativeRuleApplier applier(NativeRulePlatform p) {
        NativeRuleApplier a = mock(NativeRuleApplier.class);
        when(a.platform()).thenReturn(p);
        return a;
    }

    private static DispositionPlanner planner(List<String> azureAccounts) {
        RetireProperties props = new RetireProperties(true, 30, 7, 2, 100, null, null,
                new RetireProperties.NativeRule(Duration.ofHours(1), azureAccounts, 1), null);
        CloudDeletionServiceFactory engines = new CloudDeletionServiceFactory(List.of(engine("AWS"), engine("GCP"),
                engine("AZURE")));
        return new DispositionPlanner(List.of(new S3LifecycleRuleGenerator(), new GcsLifecycleRuleGenerator(),
                new AzureBlobLifecycleRuleGenerator(props), new BigQueryTableExpirationGenerator(props)),
                List.of(applier(NativeRulePlatform.S3_LIFECYCLE), applier(NativeRulePlatform.GCS_LIFECYCLE),
                        applier(NativeRulePlatform.AZURE_BLOB_LIFECYCLE),
                        applier(NativeRulePlatform.BIGQUERY_TABLE_EXPIRATION)), engines);
    }

    private static ICloudDeletionService engine(String provider) {
        ICloudDeletionService e = mock(ICloudDeletionService.class);
        when(e.supportsCloudProvider(provider)).thenReturn(true);
        return e;
    }

    private static Optional<DispositionPlanner.Plan> plan(DispositionPlanner p, String uri, String provider) {
        return CanonicalTarget.parse(uri).flatMap(id -> p.plan(id, provider, KEY, NOW));
    }

    /** Q-631: a restore of a rule in force finds the applier of the stored platform, and only of a native one. */
    @Test
    void theApplierOfAStoredPlatformIsFoundByName() {
        DispositionPlanner p = planner(List.of(ACCOUNT_ID));

        assertThat(p.applierFor("S3_LIFECYCLE")).isPresent().get().extracting(NativeRuleApplier::platform)
                .isEqualTo(NativeRulePlatform.S3_LIFECYCLE);
        assertThat(p.applierFor("AZURE_BLOB_LIFECYCLE")).isPresent();
        assertThat(p.applierFor("ENGINE_AWS")).as("an engine delete has no rule to remove").isEmpty();
        assertThat(p.applierFor("NOPE")).isEmpty();
        assertThat(p.applierFor(null)).isEmpty();
    }

    @Test
    void containersAndTablesGetTheirPlatformsNativeRule() {
        DispositionPlanner p = planner(List.of(ACCOUNT_ID));
        assertThat(plan(p, "s3://dlap-aws-e2e", "AWS")).get().extracting(DispositionPlanner.Plan::platform)
                .isEqualTo("S3_LIFECYCLE");
        assertThat(plan(p, "gs://dlap-gcp-e2e", "GCP")).get().extracting(DispositionPlanner.Plan::platform)
                .isEqualTo("GCS_LIFECYCLE");
        assertThat(plan(p, "az://dlapazuree2e/raw", "AZURE")).get().extracting(DispositionPlanner.Plan::platform)
                .isEqualTo("AZURE_BLOB_LIFECYCLE");
        assertThat(plan(p, "bq://dlap-gcp-e2e/sales/orders", "GCP")).get().extracting(DispositionPlanner.Plan::platform)
                .isEqualTo("BIGQUERY_TABLE_EXPIRATION");
        assertThat(plan(p, "gs://dlap-gcp-e2e", "GCP")).get().satisfies(plan -> {
            assertThat(plan.nativeRule()).isTrue();
            assertThat(plan.ruleId()).isEqualTo("dlap-retire-" + KEY);
        });
    }

    @Test
    void singleObjectsGoToTheEngineInTheEnginesLocationForm() {
        DispositionPlanner p = planner(List.of());
        assertThat(plan(p, "s3://dlap-aws-e2e/k/obj.parquet", "AWS")).get().satisfies(plan -> {
            assertThat(plan.mode()).isEqualTo("DIRECT");
            assertThat(plan.platform()).isEqualTo("ENGINE_AWS");
            assertThat(plan.engineLocation()).isEqualTo("s3://dlap-aws-e2e/k/obj.parquet");
        });
        assertThat(plan(p, "az://dlapazuree2e/raw/a/b.csv", "AZURE")).get()
                .extracting(DispositionPlanner.Plan::engineLocation).isEqualTo("azure://dlapazuree2e/raw/a/b.csv");
    }

    @Test
    void kindsWithoutAnExactRuleOrEngineHaveNoPlan() {
        DispositionPlanner p = planner(List.of());
        assertThat(plan(p, "bq://dlap-gcp-e2e/sales", "GCP")).as("a dataset").isEmpty();
        assertThat(plan(p, "az://dlapazuree2e/raw", "AZURE")).as("an unconfigured storage account").isEmpty();
    }
}
