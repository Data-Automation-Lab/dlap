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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.AccessTier;
import com.azure.storage.blob.models.BlobProperties;
import com.dlap.autoarchival.providers.azure.AzureBlobClients;
import com.dlap.autoarchival.service.storage.ArchivalResult;
import com.dlap.autoarchival.service.storage.OperationStatus;
import com.dlap.autoarchival.service.storage.RestorationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * EXEC-01 (Q-724): the Azure storage service asks {@link AzureBlobClients} for the client of the account its
 * location names, so tiering, restore and the status checks go through the account endpoint and
 * {@code DefaultAzureCredential}, never a connection string it builds itself.
 */
class AzureCloudStorageServiceClientsTest {

    /** Hands out one mock client and records the accounts asked for. */
    private static final class RecordingClients extends AzureBlobClients {
        final List<String> accounts = new ArrayList<>();
        final BlobServiceClient service = mock(BlobServiceClient.class);

        RecordingClients() {
            super("https://myaccount.blob.core.windows.net", "", "");
        }

        @Override
        public BlobServiceClient serviceClient(String account) {
            accounts.add(account);
            return service;
        }
    }

    private static BlobClient blobWithTier(RecordingClients clients, AccessTier tier) {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobClient blob = mock(BlobClient.class);
        BlobProperties properties = mock(BlobProperties.class);
        when(clients.service.getBlobContainerClient("data")).thenReturn(container);
        when(container.getBlobClient("a/b.csv")).thenReturn(blob);
        when(blob.getProperties()).thenReturn(properties);
        when(properties.getBlobSize()).thenReturn(42L);
        when(properties.getAccessTier()).thenReturn(tier);
        return blob;
    }

    /** Q-724: the tier change uses the client of the account in the location. */
    @Test
    void archivingSetsTheTierThroughTheClientOfTheLocationsAccount() {
        RecordingClients clients = new RecordingClients();
        BlobClient blob = blobWithTier(clients, AccessTier.COOL);
        AzureCloudStorageService service = new AzureCloudStorageService(false, clients);

        ArchivalResult result = service.archiveData(UUID.randomUUID(), "azure://myaccount/data/a/b.csv", "COOL",
                Map.of());

        assertThat(result.getStatus()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(result.getDataSizeBytes()).isEqualTo(42L);
        assertThat(clients.accounts).containsExactly("myaccount");
        verify(blob).setAccessTier(AccessTier.COOL);
    }

    /** Q-724: the restore sets the Hot tier through the same client. */
    @Test
    void restoringSetsHotThroughTheClientOfTheLocationsAccount() {
        RecordingClients clients = new RecordingClients();
        BlobClient blob = blobWithTier(clients, AccessTier.HOT);
        AzureCloudStorageService service = new AzureCloudStorageService(false, clients);

        RestorationResult result = service.restoreData(UUID.randomUUID(), "azure://otheraccount/data/a/b.csv", "HOT");

        assertThat(result.getStatus()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(clients.accounts).containsExactly("otheraccount");
        verify(blob).setAccessTier(AccessTier.HOT);
    }

    /** Q-724: the status checks read the blob's tier through the account's client. */
    @Test
    void theStatusChecksReadTheTierThroughTheAccountsClient() {
        RecordingClients clients = new RecordingClients();
        blobWithTier(clients, AccessTier.ARCHIVE);
        AzureCloudStorageService service = new AzureCloudStorageService(false, clients);

        assertThat(service.getArchivalStatus("azure://myaccount/data/a/b.csv")).isEqualTo(OperationStatus.COMPLETED);
        assertThat(service.getRestorationStatus("azure://myaccount/data/a/b.csv"))
                .isEqualTo(OperationStatus.NOT_RESTORED);
        assertThat(clients.accounts).containsExactly("myaccount", "myaccount");
    }

    /** Q-724: a client injected by a test serves every account and the factory is not asked. */
    @Test
    void anInjectedClientServesEveryAccount() {
        RecordingClients clients = new RecordingClients();
        AzureCloudStorageService service = new AzureCloudStorageService(false, clients);
        BlobServiceClient injected = mock(BlobServiceClient.class);
        service.setBlobServiceClient(injected);

        assertThat(service.blobServiceClient("any")).isSameAs(injected);
        assertThat(clients.accounts).isEmpty();
        verify(clients.service, org.mockito.Mockito.never()).getBlobContainerClient(any());
    }
}
