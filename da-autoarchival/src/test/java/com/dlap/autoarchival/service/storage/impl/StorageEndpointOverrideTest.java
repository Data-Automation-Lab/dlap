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

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.autoarchival.providers.aws.S3LifecycleRuleProvider;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Q-713: the direct-object storage services take the same test-only emulator endpoint as the lifecycle rule
 * providers, so the harness can apply a single-object archive against an emulator too. Without an endpoint they
 * behave as before.
 */
class StorageEndpointOverrideTest {

    @Test
    void q713_theS3ServiceTargetsTheEmulatorEndpointWithPathStyleWhenOneIsGiven() {
        AwsS3StorageService service = new AwsS3StorageService("us-east-1", "http://127.0.0.1:9", "test", "test");

        S3Client client = (S3Client) ReflectionTestUtils.getField(service, "s3Client");

        assertThat(client.serviceClientConfiguration().endpointOverride()).contains(URI.create("http://127.0.0.1:9"));
        client.close();
    }

    @Test
    void q713_theS3ServiceUsesTheRealEndpointWhenNoneIsGiven() {
        AwsS3StorageService service = new AwsS3StorageService("us-east-1", "", "", "");

        S3Client client = (S3Client) ReflectionTestUtils.getField(service, "s3Client");

        assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        client.close();
    }

    @Test
    void q713_theS3ServiceSingleArgumentConstructorStaysTheRealAws() {
        S3Client client = (S3Client) ReflectionTestUtils.getField(new AwsS3StorageService("eu-west-1"), "s3Client");

        assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        client.close();
    }

    @Test
    void q713_theRuleProviderAndTheStorageServiceBuildTheSameEmulatorClient() {
        S3Client client = S3LifecycleRuleProvider.build("us-east-1", "http://127.0.0.1:9", "test", "test");

        assertThat(client.serviceClientConfiguration().endpointOverride()).contains(URI.create("http://127.0.0.1:9"));
        client.close();
    }

    @Test
    void q713_theGcsServiceTargetsTheEmulatorHostAndProjectWhenOneIsGiven() {
        GcsCloudStorageService service = new GcsCloudStorageService(false, "http://127.0.0.1:4443", "emu-project");

        var options = service.storage().getOptions();

        assertThat(options.getHost()).isEqualTo("http://127.0.0.1:4443");
        assertThat(options.getProjectId()).isEqualTo("emu-project");
        assertThat(options.getCredentials()).isEqualTo(com.google.cloud.NoCredentials.getInstance());
    }

    @Test
    void q713_theGcsServiceMockConstructorIsUnchanged() {
        GcsCloudStorageService service = new GcsCloudStorageService(true);

        assertThat(ReflectionTestUtils.getField(service, "endpointOverride")).isEqualTo("");
    }
}
