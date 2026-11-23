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

package com.dlap.autodelete.service.storage.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.dlap.autodelete.service.storage.BulkDeletionResult;
import com.dlap.autodelete.service.storage.DeletionResult;
import com.dlap.autodelete.service.storage.DeletionStatus;
import com.dlap.autodelete.service.storage.DeletionType;

/**
 * The blob delete path of {@link AzureCloudDeletionService}, driven through a
 * stand-in blob client so no network or credentials are needed: a 404 on the
 * size probe or on the delete is an idempotent success, any other probe failure
 * does not stop the delete, and a mixed bulk run is partial.
 */
class AzureCloudDeletionServiceBlobPathsTest {

    private static final String BLOB = "azure://acct/container/dir/file.csv";

    private static BlobStorageException status(int code) {
        BlobStorageException e = mock(BlobStorageException.class);
        when(e.getStatusCode()).thenReturn(code);
        return e;
    }

    private static AzureCloudDeletionService serviceWith(BlobClient blob) {
        return new AzureCloudDeletionService(false) {
            @Override
            BlobClient resolveBlobClient(AzureResourceRef ref) {
                return blob;
            }
        };
    }

    @Test
    void aBlobThatIsAlreadyGoneOnTheProbeIsAnIdempotentSuccess() {
        BlobClient blob = mock(BlobClient.class);
        BlobStorageException gone = status(404);
        when(blob.getProperties()).thenThrow(gone);

        DeletionResult result = serviceWith(blob)
            .deleteData(UUID.randomUUID(), BLOB, DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.COMPLETED, result.getStatus());
        assertEquals(0L, result.getDataSizeBytes());
        assertThat(result.getErrorMessage()).contains("already deleted");
        verify(blob, never()).delete();
    }

    @Test
    void aFailedSizeProbeDoesNotStopTheDelete() {
        BlobClient blob = mock(BlobClient.class);
        BlobStorageException denied = status(403);
        when(blob.getProperties()).thenThrow(denied);

        DeletionResult result = serviceWith(blob)
            .deleteData(UUID.randomUUID(), BLOB, DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.COMPLETED, result.getStatus());
        verify(blob).delete();
    }

    @Test
    void aBlobThatVanishesBetweenTheProbeAndTheDeleteIsAnIdempotentSuccess() {
        BlobClient blob = mock(BlobClient.class);
        BlobProperties props = mock(BlobProperties.class);
        when(props.getBlobSize()).thenReturn(7L);
        when(blob.getProperties()).thenReturn(props);
        BlobStorageException gone = status(404);
        doThrow(gone).when(blob).delete();

        DeletionResult result = serviceWith(blob)
            .deleteData(UUID.randomUUID(), BLOB, DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.COMPLETED, result.getStatus());
        assertThat(result.getErrorMessage()).contains("already deleted");
    }

    @Test
    void aBulkRunWithOneRefusedLocationIsPartial() {
        AzureCloudDeletionService svc = new AzureCloudDeletionService(true);

        BulkDeletionResult bulk = svc.bulkDeleteData(
            List.of(UUID.randomUUID(), UUID.randomUUID()),
            List.of(BLOB, "gs://other-cloud/file.csv"),
            DeletionType.HARD, Map.of());

        assertEquals(1, bulk.getSuccessfulDeletions());
        assertEquals(1, bulk.getFailedDeletions());
        assertEquals(DeletionStatus.IN_PROGRESS, bulk.getOverallStatus());
    }
}
