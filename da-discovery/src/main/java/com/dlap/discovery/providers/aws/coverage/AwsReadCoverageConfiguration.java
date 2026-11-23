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

package com.dlap.discovery.providers.aws.coverage;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dlap.discovery.coverage.CoverageHttp;
import com.dlap.discovery.coverage.ReadCoverageProperties;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Wires the AWS read-coverage collector when {@code dlap.discovery.coverage.enabled},
 * {@code dlap.discovery.coverage.aws.enabled} and {@code cloud.provider.aws.enabled} are all true.
 * Credentials follow {@link AwsClientFactory}: the static keys {@code cloud.provider.aws.access-key}
 * and {@code secret-key} when both are set, else the AWS default chain (environment, shared
 * config, container and instance roles).
 */
@Configuration
@ConditionalOnProperty(name = {"dlap.discovery.coverage.enabled", "dlap.discovery.coverage.aws.enabled",
        "cloud.provider.aws.enabled"}, havingValue = "true")
public class AwsReadCoverageConfiguration {

    /**
     * The collector.
     *
     * @param clients    the AWS client factory (S3 listing)
     * @param properties the coverage settings
     * @param mapper     the JSON mapper
     * @param accessKey  the static access key, or empty
     * @param secretKey  the static secret key, or empty
     * @return the collector
     */
    @Bean
    public AwsReadCoverageCollector awsReadCoverageCollector(AwsClientFactory clients, ReadCoverageProperties properties,
            ObjectMapper mapper,
            @Value("${cloud.provider.aws.access-key:}") String accessKey,
            @Value("${cloud.provider.aws.secret-key:}") String secretKey) {
        ReadCoverageProperties.Aws aws = properties.getAws();
        String override = aws.getCloudtrailEndpoint();
        AwsSignedJsonClient client = new AwsSignedJsonClient(new CoverageHttp(mapper), credentials(accessKey, secretKey),
                region -> override.isBlank() ? AwsSignedJsonClient.publicCloudTrailEndpoint(region) : URI.create(override),
                region -> override.isBlank() ? AwsSignedJsonClient.publicStsEndpoint(region) : URI.create(override));
        S3BucketInventory inventory = new S3BucketInventory(clients, aws.getDefaultRegion());
        return new AwsReadCoverageCollector(inventory::list, client, aws.getDefaultRegion());
    }

    /**
     * The credentials: static keys when both are set, else the default chain.
     *
     * @param accessKey the access key
     * @param secretKey the secret key
     * @return the provider
     */
    static AwsCredentialsProvider credentials(String accessKey, String secretKey) {
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }
}
