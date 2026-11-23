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

package com.dlap.autoarchival.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.autoarchival.providers.aws.ActorCredentials;
import com.dlap.autoarchival.providers.aws.S3LifecycleRuleProvider;
import com.dlap.autoarchival.providers.azure.AzureBlobClients;
import com.dlap.autoarchival.providers.azure.AzureManagementPolicyRuleProvider;
import com.dlap.autoarchival.providers.gcp.GcsLifecycleRuleProvider;
import com.dlap.autoarchival.service.storage.impl.AwsS3StorageService;
import com.dlap.autoarchival.service.storage.impl.AzureCloudStorageService;
import com.dlap.autoarchival.service.storage.impl.GcsCloudStorageService;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * EXEC-01: Spring builds every credential and client holder of the archive executor from the new properties, and a
 * deprecated name reaches the same constructor as its replacement.
 */
class ExecutorCredentialsWiringTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/dlap-archive-actor";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(
            ActorCredentials.class, AzureBlobClients.class, S3LifecycleRuleProvider.class, AwsS3StorageService.class,
            GcsLifecycleRuleProvider.class, GcsCloudStorageService.class, AzureCloudStorageService.class,
            AzureManagementPolicyRuleProvider.class);

    /** Q-722: the role reaches the S3 storage service, which builds its client at start-up. */
    @Test
    void theRoleSettingsReachTheS3StorageService() {
        runner.withPropertyValues("dlap.autoarchival.aws.credentials-ref=" + ROLE,
                "dlap.autoarchival.aws.external-id=ext-7").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ActorCredentials credentials = ctx.getBean(ActorCredentials.class);
                    assertThat(credentials.assumesRole()).isTrue();
                    S3Client s3 = (S3Client) ReflectionTestUtils.getField(ctx.getBean(AwsS3StorageService.class),
                            "s3Client");
                    assertThat(s3.serviceClientConfiguration().credentialsProvider()).isSameAs(credentials.provider());
                    s3.close();
                });
    }

    /** Q-740: the new test-endpoint name and the deprecated one both reach the storage service's client. */
    @Test
    void bothTheNewAndTheDeprecatedEndpointNameReachTheS3Client() {
        runner.withPropertyValues("dlap.autoarchival.test-endpoint.s3=http://127.0.0.1:9").run(ctx -> {
            S3Client s3 = (S3Client) ReflectionTestUtils.getField(ctx.getBean(AwsS3StorageService.class), "s3Client");
            assertThat(s3.serviceClientConfiguration().endpointOverride()).contains(URI.create("http://127.0.0.1:9"));
            s3.close();
        });
        runner.withPropertyValues("dlap.autoarchival.aws.endpoint-override=http://127.0.0.1:19").run(ctx -> {
            S3Client s3 = (S3Client) ReflectionTestUtils.getField(ctx.getBean(AwsS3StorageService.class), "s3Client");
            assertThat(s3.serviceClientConfiguration().endpointOverride()).contains(URI.create("http://127.0.0.1:19"));
            s3.close();
        });
    }

    /** Q-740: the GCS emulator host reaches the storage service under either name. */
    @Test
    void bothGcsNamesReachTheStorageService() {
        runner.withPropertyValues("dlap.autoarchival.test-endpoint.gcs=http://127.0.0.1:4443").run(ctx ->
                assertThat(ReflectionTestUtils.getField(ctx.getBean(GcsCloudStorageService.class), "endpointOverride"))
                        .isEqualTo("http://127.0.0.1:4443"));
        runner.withPropertyValues("dlap.autoarchival.gcs.endpoint-override=http://127.0.0.1:4444").run(ctx ->
                assertThat(ReflectionTestUtils.getField(ctx.getBean(GcsCloudStorageService.class), "endpointOverride"))
                        .isEqualTo("http://127.0.0.1:4444"));
    }

    /** Q-724: the Azure services share one client holder and use the account endpoint. */
    @Test
    void theAzureServicesShareTheAccountEndpointClients() {
        runner.withPropertyValues("dlap.autoarchival.azure.account-endpoint=https://shared.blob.core.windows.net")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    AzureBlobClients clients = ctx.getBean(AzureBlobClients.class);
                    assertThat(ReflectionTestUtils.getField(ctx.getBean(AzureCloudStorageService.class), "clients"))
                            .isSameAs(clients);
                    assertThat(ReflectionTestUtils.getField(ctx.getBean(AzureManagementPolicyRuleProvider.class),
                            "clients")).isSameAs(clients);
                    assertThat(clients.configured()).isTrue();
                });
    }
}
