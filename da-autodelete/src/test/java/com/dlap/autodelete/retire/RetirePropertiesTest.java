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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * RET-02 settings: dry run is the default (decision D23), floors are enforced at start-up.
 */
class RetirePropertiesTest {

    private static RetireProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("dlap.autodelete", RetireProperties.class);
    }

    @Test
    void dryRunCloudDeleteDefaultsToTrueInTheCodeAndInTheShippedProperties() throws IOException {
        assertThat(bind(Map.of()).dryRunCloudDelete()).as("binding default").isTrue();
        assertThat(RetireProperties.defaults().dryRunCloudDelete()).isTrue();
        Properties shipped = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            shipped.load(in);
        }
        assertThat(shipped.getProperty("dlap.autodelete.dry-run-cloud-delete"))
                .isEqualTo("${DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE:true}");
    }

    @Test
    void theShippedDefaultsAreTheDocumentedOnes() {
        RetireProperties p = bind(Map.of());
        assertThat(p.softDeleteWindowDays()).isEqualTo(30);
        assertThat(p.windowFloorDays()).isEqualTo(7);
        assertThat(p.hardDeleteRequiredApprovals()).isEqualTo(2);
        assertThat(p.sweep().batchSize()).isEqualTo(50);
        assertThat(p.proof().overdueAfter()).hasDays(3);
        assertThat(p.nativeRule().azureStorageAccountIds()).isEmpty();
    }

    @Test
    void valuesBelowTheirFloorsStopTheStartWithThePropertyName() {
        assertThatThrownBy(() -> bind(Map.of("dlap.autodelete.hard-delete-required-approvals", "1")))
                .hasStackTraceContaining("dlap.autodelete.hard-delete-required-approvals");
        assertThatThrownBy(() -> bind(Map.of("dlap.autodelete.window-floor-days", "0")))
                .hasStackTraceContaining("dlap.autodelete.window-floor-days");
        assertThatThrownBy(() -> bind(Map.of("dlap.autodelete.soft-delete-window-days", "3")))
                .hasStackTraceContaining("dlap.autodelete.soft-delete-window-days");
        assertThatThrownBy(() -> bind(Map.of("dlap.autodelete.sweep.batch-size", "0")))
                .hasStackTraceContaining("dlap.autodelete.sweep.batch-size");
    }

    /**
     * Q-677 (RET-06): a rule in force ends after 30 days by default (configurable); a container rule is at least one
     * day old before it deletes, and a zero-day age stops the start.
     */
    @Test
    void ruleInForceMaxAgeDefaultsTo30DaysAndAZeroDayContainerAgeStopsTheStart() {
        RetireProperties p = bind(Map.of());
        assertThat(p.ruleInForce().maxAge()).isEqualTo(java.time.Duration.ofDays(30));
        assertThat(p.ruleInForce().retryAfter()).isEqualTo(java.time.Duration.ofHours(6));
        assertThat(p.nativeRule().containerExpirationDays()).isEqualTo(1);
        assertThat(RetireProperties.defaults().ruleInForce().maxAge()).isEqualTo(java.time.Duration.ofDays(30));
        assertThat(bind(Map.of("dlap.autodelete.rule-in-force.max-age", "P7D")).ruleInForce().maxAge())
                .isEqualTo(java.time.Duration.ofDays(7));
        assertThat(bind(Map.of("dlap.autodelete.native-rule.container-expiration-days", "3")).nativeRule()
                .containerExpirationDays()).isEqualTo(3);
        assertThatThrownBy(() -> bind(Map.of("dlap.autodelete.native-rule.container-expiration-days", "0")))
                .hasStackTraceContaining("dlap.autodelete.native-rule.container-expiration-days");
        assertThatThrownBy(() -> bind(Map.of("dlap.autodelete.rule-in-force.max-age", "PT0S")))
                .hasStackTraceContaining("dlap.autodelete.rule-in-force.max-age");
    }

    @Test
    void azureStorageAccountIdsBindFromACommaSeparatedValue() {
        RetireProperties p = bind(Map.of("dlap.autodelete.native-rule.azure-storage-account-ids",
                "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a1,"
                        + "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a2"));
        assertThat(p.nativeRule().azureStorageAccountIds()).hasSize(2);
    }
}
