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

package com.dlap.discovery.providers.aws.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.IdentityProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * INST-01: pins how {@link AwsClientFactory} turns the documented AWS settings into SDK
 * clients. No network and no Docker: building an SDK client is offline, and the
 * credential chain is only resolved lazily.
 */
class AwsClientFactoryTest {

    private static AwsClientFactory factory(String endpoint, String accessKey, String secretKey) {
        AwsClientFactory factory = new AwsClientFactory();
        ReflectionTestUtils.setField(factory, "endpointOverride", endpoint);
        ReflectionTestUtils.setField(factory, "accessKey", accessKey);
        ReflectionTestUtils.setField(factory, "secretKey", secretKey);
        return factory;
    }

    private static IdentityProvider<? extends AwsCredentialsIdentity> credentialsOf(S3Client client) {
        return client.serviceClientConfiguration().credentialsProvider();
    }

    @Test
    void usesStaticKeysAndEndpointWhenFullyConfigured() {
        try (S3Client s3 = factory("http://localstack:4566", "test", "test-secret").s3Client(Region.US_EAST_1)) {
            assertThat(s3.serviceClientConfiguration().endpointOverride())
                    .contains(URI.create("http://localstack:4566"));
            IdentityProvider<? extends AwsCredentialsIdentity> provider = credentialsOf(s3);
            assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
            assertThat(((StaticCredentialsProvider) provider).resolveCredentials().accessKeyId()).isEqualTo("test");
        }
    }

    @Test
    void usesDefaultChainAndRealEndpointWhenNothingIsSet() {
        try (S3Client s3 = factory("", "", "").s3Client(Region.US_EAST_1)) {
            assertThat(s3.serviceClientConfiguration().endpointOverride()).isEmpty();
            assertThat(credentialsOf(s3)).isInstanceOf(DefaultCredentialsProvider.class);
        }
    }

    @Test
    void ignoresALoneKeyAndFallsBackToTheDefaultChain() {
        try (S3Client onlyAccess = factory("", "AKIAEXAMPLE", "").s3Client(Region.US_EAST_1);
                S3Client onlySecret = factory("", "", "secret").s3Client(Region.US_EAST_1)) {
            assertThat(credentialsOf(onlyAccess)).isInstanceOf(DefaultCredentialsProvider.class);
            assertThat(credentialsOf(onlySecret)).isInstanceOf(DefaultCredentialsProvider.class);
        }
    }

    @Test
    void toleratesUnsetFields() {
        try (S3Client s3 = factory(null, null, null).s3Client(Region.EU_WEST_1)) {
            assertThat(s3.serviceClientConfiguration().endpointOverride()).isEmpty();
            assertThat(credentialsOf(s3)).isInstanceOf(DefaultCredentialsProvider.class);
        }
    }

    @Test
    void isCreatedOnlyWhenTheProviderIsEnabledAndBindsTheDocumentedProperties() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(AwsClientFactory.class);

        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(AwsClientFactory.class));
        runner.withPropertyValues("cloud.provider.aws.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AwsClientFactory.class));

        runner.withPropertyValues(
                        "cloud.provider.aws.enabled=true",
                        "cloud.provider.aws.endpoint=http://localstack:4566",
                        "cloud.provider.aws.access-key=test",
                        "cloud.provider.aws.secret-key=test")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AwsClientFactory.class);
                    try (S3Client s3 = ctx.getBean(AwsClientFactory.class).s3Client(Region.US_EAST_1)) {
                        assertThat(s3.serviceClientConfiguration().endpointOverride())
                                .contains(URI.create("http://localstack:4566"));
                        assertThat(credentialsOf(s3)).isInstanceOf(StaticCredentialsProvider.class);
                    }
                });
    }
}
