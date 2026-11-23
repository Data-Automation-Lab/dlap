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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.autodelete.service.storage.BulkDeletionResult;
import com.dlap.autodelete.service.storage.DeletionStatus;
import com.dlap.autodelete.service.storage.DeletionType;

/**
 * A bulk run where one location is refused and one is accepted ends partial
 * (IN_PROGRESS): the upstream consumer reads the counts to decide what to retry.
 */
class GcpCloudDeletionServiceBulkTest {

    @Test
    void aBulkRunWithOneRefusedLocationIsPartial() {
        GcpCloudDeletionService svc = new GcpCloudDeletionService(true);

        BulkDeletionResult bulk = svc.bulkDeleteData(
            List.of(UUID.randomUUID(), UUID.randomUUID()),
            List.of("gs://my-bucket/dir/file.csv", "s3://other-cloud/file.csv"),
            DeletionType.HARD, Map.of());

        assertEquals(1, bulk.getSuccessfulDeletions());
        assertEquals(1, bulk.getFailedDeletions());
        assertEquals(DeletionStatus.IN_PROGRESS, bulk.getOverallStatus());
    }
}
