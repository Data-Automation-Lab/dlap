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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.storage.fluent.BlobServicesClient;
import com.azure.resourcemanager.storage.fluent.ManagementPoliciesClient;
import com.azure.resourcemanager.storage.fluent.StorageManagementClient;
import com.azure.resourcemanager.storage.fluent.models.BlobServicePropertiesInner;
import com.dlap.autodelete.nativerule.BucketVersioning;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.TestEndpoints;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.assetid.AssetIdNormalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * RET-08, decision D38. Mock-tested only: Azurite has no Resource Manager API, so a live capture of an Azure
 * account with blob versioning is still needed (README).
 * <ul>
 *   <li>Q-694: the Azure applier reads the account's blob versioning (blob service properties) before a native
 *       delete rule, so the executor refuses VERSIONED_BUCKET_UNSUPPORTED when it is enabled.</li>
 *   <li>Q-713: {@code dlap.autodelete.test-endpoint.azure} points the Resource Manager calls at a stub and logs a
 *       WARN at start-up.</li>
 * </ul>
 */
class AzureBlobVersioningAndEndpointTest {

    private static final String SUB = "00000000-0000-4000-8000-000000000001";
    private static final String ACCOUNT_ID = "/subscriptions/" + SUB
            + "/resourceGroups/rg-data/providers/Microsoft.Storage/storageAccounts/dlapazuree2e";

    private static NativeRule rule() {
        return new AzureBlobLifecycleRuleGenerator(new RetireProperties(true, 30, 7, 2, 100, null, null,
                new RetireProperties.NativeRule(Duration.ofHours(1), List.of(ACCOUNT_ID), 1), null))
                .generate(AssetIdNormalizer.normalize("az://dlapazuree2e/raw").orElseThrow(),
                        UUID.fromString("abcdabcd-0000-4000-8000-0000000000c1"), Instant.parse("2026-11-01T10:15:00Z"));
    }

    private static AzureBlobLifecycleRuleApplier applierReading(BlobServicesClient blobs) {
        ManagementPoliciesClient policies = mock(ManagementPoliciesClient.class);
        return new AzureBlobLifecycleRuleApplier(sub -> policies, sub -> {
            assertThat(sub).isEqualTo(SUB);
            return blobs;
        });
    }

    /**
     * Q-694: the blob service properties of the rule's account decide: isVersioningEnabled true is ENABLED (refused
     * by the executor), false or absent is UNVERSIONED.
     */
    @Test
    void theAccountsBlobVersioningIsReadFromItsBlobServiceProperties() {
        BlobServicesClient blobs = mock(BlobServicesClient.class);
        AzureBlobLifecycleRuleApplier applier = applierReading(blobs);

        when(blobs.getServiceProperties("rg-data", "dlapazuree2e"))
                .thenReturn(new BlobServicePropertiesInner().withIsVersioningEnabled(true));
        assertThat(applier.versioning(rule())).isEqualTo(BucketVersioning.ENABLED);
        assertThat(applier.versioning(rule()).versioned()).isTrue();

        when(blobs.getServiceProperties("rg-data", "dlapazuree2e"))
                .thenReturn(new BlobServicePropertiesInner().withIsVersioningEnabled(false));
        assertThat(applier.versioning(rule())).isEqualTo(BucketVersioning.UNVERSIONED);

        when(blobs.getServiceProperties("rg-data", "dlapazuree2e")).thenReturn(new BlobServicePropertiesInner());
        assertThat(applier.versioning(rule())).as("never turned on: the platform default").isEqualTo(
                BucketVersioning.UNVERSIONED);
    }

    /** Q-694: an answer Azure did not give is UNKNOWN, never UNVERSIONED, so the executor waits instead. */
    @Test
    void anAccountThatCannotBeReadIsUnknownVersioning() {
        BlobServicesClient refused = mock(BlobServicesClient.class);
        when(refused.getServiceProperties("rg-data", "dlapazuree2e"))
                .thenThrow(new ManagementException("AuthorizationFailed", null));
        assertThat(applierReading(refused).versioning(rule())).isEqualTo(BucketVersioning.UNKNOWN);

        BlobServicesClient empty = mock(BlobServicesClient.class);
        when(empty.getServiceProperties("rg-data", "dlapazuree2e")).thenReturn(null);
        assertThat(applierReading(empty).versioning(rule())).isEqualTo(BucketVersioning.UNKNOWN);

        ManagementPoliciesClient policies = mock(ManagementPoliciesClient.class);
        assertThat(new AzureBlobLifecycleRuleApplier(sub -> policies).versioning(rule()))
                .as("an applier without a blob services client cannot tell").isEqualTo(BucketVersioning.UNKNOWN);
    }

    /** Q-694: the versioning read never touches the management policy. */
    @Test
    void theVersioningReadChangesNothing() {
        ManagementPoliciesClient policies = mock(ManagementPoliciesClient.class);
        BlobServicesClient blobs = mock(BlobServicesClient.class);
        when(blobs.getServiceProperties("rg-data", "dlapazuree2e"))
                .thenReturn(new BlobServicePropertiesInner().withIsVersioningEnabled(true));

        new AzureBlobLifecycleRuleApplier(sub -> policies, sub -> blobs).versioning(rule());

        verify(policies, never()).createOrUpdate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** Q-713: the test environment is public Azure with only the Resource Manager endpoint replaced. */
    @Test
    void theTestEnvironmentReplacesOnlyTheResourceManagerEndpoint() {
        AzureEnvironment env = AzureBlobLifecycleRuleApplier.testEnvironment("https://arm-stub:8443/");

        assertThat(env.getResourceManagerEndpoint()).isEqualTo("https://arm-stub:8443/");
        assertThat(env.getActiveDirectoryEndpoint()).isEqualTo(AzureEnvironment.AZURE.getActiveDirectoryEndpoint());
        assertThat(AzureEnvironment.AZURE.getResourceManagerEndpoint()).isNotEqualTo("https://arm-stub:8443/");
    }

    /**
     * Q-713: with the setting, the storage management client the applier uses is bound to the stub's endpoint and
     * the start-up WARN names it; without it, to Azure's Resource Manager and no warning.
     */
    @Test
    void theClientsBuiltFromTheSettingTalkToTheTestEndpointAndWarn() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger log = (Logger) LoggerFactory.getLogger(AzureBlobLifecycleRuleApplier.class);
        logs.start();
        log.addAppender(logs);
        Function<String, StorageManagementClient> stub;
        try {
            stub = AzureBlobLifecycleRuleApplier.managers(new TestEndpoints("", true, "", "https://arm-stub:8443/"));
        } finally {
            log.detachAppender(logs);
        }
        StorageManagementClient client = stub.apply(SUB);
        assertThat(client.getEndpoint()).startsWith("https://arm-stub:8443");
        assertThat(client.getSubscriptionId()).isEqualTo(SUB);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("AZURE_BLOB_LIFECYCLE").contains("TEST-ONLY")
                    .contains("https://arm-stub:8443/");
        });

        ListAppender<ILoggingEvent> quiet = new ListAppender<>();
        quiet.start();
        log.addAppender(quiet);
        try {
            new AzureBlobLifecycleRuleApplier(TestEndpoints.none());
        } finally {
            log.detachAppender(quiet);
        }
        assertThat(quiet.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("TEST-ONLY"));
    }
}
