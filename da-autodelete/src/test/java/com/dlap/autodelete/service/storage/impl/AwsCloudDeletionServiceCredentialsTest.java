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
import static org.mockito.Mockito.mock;

import com.dlap.autodelete.providers.aws.ActorCredentials;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * EXEC-01 (Q-722): the direct engine delete signs its S3 requests with the Actor credentials, the assumed role when
 * dlap.autodelete.aws.credentials-ref is set.
 */
class AwsCloudDeletionServiceCredentialsTest {

    /** Q-722: the S3 client of the engine delete uses the shared role provider. */
    @Test
    void theEngineDeleteSignsWithTheActorRole() {
        ActorCredentials credentials = new ActorCredentials("arn:aws:iam::123456789012:role/dlap-delete-actor", "", "",
                "us-east-1", k -> null, caller -> mock(StsClient.class));
        AwsCloudDeletionService service = new AwsCloudDeletionService(true, "", credentials);

        // The engine delete takes its region from the SDK chain; the test supplies one.
        String before = System.getProperty("aws.region");
        System.setProperty("aws.region", "us-east-1");
        try (S3Client s3 = service.buildS3Client()) {
            assertThat(s3.serviceClientConfiguration().credentialsProvider()).isSameAs(credentials.provider());
        } finally {
            if (before == null) {
                System.clearProperty("aws.region");
            } else {
                System.setProperty("aws.region", before);
            }
        }
    }
}
