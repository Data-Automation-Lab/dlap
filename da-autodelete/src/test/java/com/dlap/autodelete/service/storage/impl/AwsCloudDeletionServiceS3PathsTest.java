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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.autodelete.service.storage.BulkDeletionResult;
import com.dlap.autodelete.service.storage.DeletionResult;
import com.dlap.autodelete.service.storage.DeletionStatus;
import com.dlap.autodelete.service.storage.DeletionType;
import com.dlap.autodelete.service.storage.StorageReclaimEstimate;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The S3 object delete path of {@link AwsCloudDeletionService}, driven through a
 * stand-in S3 client so no network or credentials are needed: the size probe
 * decides between an idempotent success (object already gone) and a delete that
 * goes ahead even when the probe fails. Also covers a partial bulk result and
 * the zero estimate when the SDK client cannot be built.
 */
class AwsCloudDeletionServiceS3PathsTest {

    private static final String OBJECT = "s3://my-bucket/dir/file.csv";

    /** Service whose S3 client is a scripted stand-in. */
    private static final class Scripted extends AwsCloudDeletionService {
        private final RuntimeException probe;
        private final RuntimeException build;
        final List<String> calls = new ArrayList<>();

        Scripted(RuntimeException probe, RuntimeException build) {
            super(false);
            this.probe = probe;
            this.build = build;
        }

        @Override
        S3Client buildS3Client() {
            if (build != null) {
                throw build;
            }
            return (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(), new Class<?>[] {S3Client.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "headObject":
                            calls.add("head");
                            if (probe != null) {
                                throw probe;
                            }
                            return HeadObjectResponse.builder().contentLength(42L).build();
                        case "deleteObject":
                            calls.add("delete");
                            return null;
                        case "close":
                            return null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
        }
    }

    private static S3Exception s3Failure() {
        return (S3Exception) S3Exception.builder().message("probe refused").statusCode(403).build();
    }

    @Test
    void anObjectThatIsAlreadyGoneIsAnIdempotentSuccessAndNothingIsDeleted() {
        Scripted svc = new Scripted(NoSuchKeyException.builder().message("gone").build(), null);

        DeletionResult result = svc.deleteData(UUID.randomUUID(), OBJECT, DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.COMPLETED, result.getStatus());
        assertEquals(0L, result.getDataSizeBytes());
        assertThat(result.getErrorMessage()).contains("already deleted");
        assertThat(svc.calls).containsExactly("head");
    }

    @Test
    void aFailedSizeProbeDoesNotStopTheDelete() {
        Scripted svc = new Scripted(s3Failure(), null);

        DeletionResult result = svc.deleteData(UUID.randomUUID(), OBJECT, DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.COMPLETED, result.getStatus());
        assertThat(svc.calls).containsExactly("head", "delete");
    }

    @Test
    void aBulkRunWithOneRefusedLocationIsPartial() {
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true);

        BulkDeletionResult bulk = svc.bulkDeleteData(
            List.of(UUID.randomUUID(), UUID.randomUUID()),
            List.of(OBJECT, "gs://other-cloud/file.csv"),
            DeletionType.HARD, Map.of());

        assertEquals(1, bulk.getSuccessfulDeletions());
        assertEquals(1, bulk.getFailedDeletions());
        assertEquals(DeletionStatus.IN_PROGRESS, bulk.getOverallStatus());
    }

    @Test
    void anUnavailableSdkClientGivesAZeroEstimate() {
        Scripted svc = new Scripted(null, new IllegalStateException("no region"));

        StorageReclaimEstimate estimate = svc.calculateStorageReclaim(List.of(OBJECT));

        assertEquals(0, estimate.getTotalObjects());
        assertEquals(0L, estimate.getTotalSizeBytes());
    }
}
