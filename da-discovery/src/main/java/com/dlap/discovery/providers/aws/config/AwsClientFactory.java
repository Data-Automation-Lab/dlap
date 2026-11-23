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

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.emr.EmrClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Central factory for AWS SDK clients (N5).
 *
 * <p><b>Configuration.</b> The properties are {@code cloud.provider.aws.enabled},
 * {@code cloud.provider.aws.endpoint}, {@code cloud.provider.aws.access-key} and
 * {@code cloud.provider.aws.secret-key}. The documented way to set them is the
 * {@code DLAP_AWS_ENABLED}, {@code DLAP_AWS_ENDPOINT}, {@code DLAP_AWS_ACCESS_KEY}
 * and {@code DLAP_AWS_SECRET_KEY} environment variables, which
 * {@code config/application.yml} binds to those properties; the region comes from
 * {@code DLAP_AWS_REGION} through {@code aws.region}. That is the one AWS variable set
 * for discovery (see {@code .env.example}). The dev profile file pins
 * {@code cloud.provider.aws.enabled=false} above that binding, so the dev compose also
 * maps {@code DLAP_AWS_ENABLED} onto the {@code CLOUD_PROVIDER_AWS_ENABLED} environment
 * variable, which outranks a profile file.
 *
 * <p><b>Credentials.</b> By default uses {@link DefaultCredentialsProvider},
 * which resolves IAM credentials from the standard chain: environment
 * access keys ({@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY}),
 * shared config, ECS/EKS container credentials, and the EC2 Instance
 * Metadata Service (IMDS) workload identity, so the same build runs with
 * static keys in dev and instance-role credentials in production with no
 * code change. Static keys are used only when both the access key and the
 * secret key are set; a lone key is ignored with a warning.
 *
 * <p><b>Endpoint override.</b> When {@code cloud.provider.aws.endpoint} is
 * set (e.g. a LocalStack URL), clients target that endpoint with S3 path-style
 * access; pair it with static test credentials. This is the seam the
 * LocalStack smoke test uses; in real deployments the property is unset and
 * clients hit the real AWS endpoints.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class AwsClientFactory {

    private static final Logger log = LoggerFactory.getLogger(AwsClientFactory.class);

    @Value("${cloud.provider.aws.endpoint:}")
    private String endpointOverride;

    @Value("${cloud.provider.aws.access-key:}")
    private String accessKey;

    @Value("${cloud.provider.aws.secret-key:}")
    private String secretKey;

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasEndpointOverride() {
        return hasText(endpointOverride);
    }

    private AwsCredentialsProvider credentialsProvider() {
        boolean hasKey = hasText(accessKey);
        boolean hasSecret = hasText(secretKey);
        if (hasKey && hasSecret) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        if (hasKey != hasSecret) {
            log.warn("Only one of DLAP_AWS_ACCESS_KEY and DLAP_AWS_SECRET_KEY is set; "
                    + "ignoring it and using the AWS default credential chain");
        }
        // IAM access key (env/shared config) + IMDS workload identity.
        return DefaultCredentialsProvider.create();
    }

    /**
     * Builds an S3 client for the given region, honouring the credential and endpoint settings above.
     *
     * @param region the AWS region to target
     * @return a new client; the caller closes it
     */
    public S3Client s3Client(Region region) {
        var builder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider());
        if (hasEndpointOverride()) {
            log.info("AWS S3 client using endpoint override {} (LocalStack/dev)", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride))
                    .forcePathStyle(true);
        }
        return builder.build();
    }

    /**
     * Builds an RDS client for the given region, honouring the credential and endpoint settings above.
     *
     * @param region the AWS region to target
     * @return a new client; the caller closes it
     */
    public RdsClient rdsClient(Region region) {
        var builder = RdsClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider());
        if (hasEndpointOverride()) {
            log.info("AWS RDS client using endpoint override {} (LocalStack/dev)", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }

    /**
     * Builds an Glue client for the given region, honouring the credential and endpoint settings above.
     *
     * @param region the AWS region to target
     * @return a new client; the caller closes it
     */
    public GlueClient glueClient(Region region) {
        var builder = GlueClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider());
        if (hasEndpointOverride()) {
            log.info("AWS Glue client using endpoint override {} (LocalStack/dev)", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }

    /**
     * Builds an Athena client for the given region, honouring the credential and endpoint settings above.
     *
     * @param region the AWS region to target
     * @return a new client; the caller closes it
     */
    public AthenaClient athenaClient(Region region) {
        var builder = AthenaClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider());
        if (hasEndpointOverride()) {
            log.info("AWS Athena client using endpoint override {} (LocalStack/dev)", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }

    /**
     * Builds an EMR client for the given region, honouring the credential and endpoint settings above.
     *
     * @param region the AWS region to target
     * @return a new client; the caller closes it
     */
    public EmrClient emrClient(Region region) {
        var builder = EmrClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider());
        if (hasEndpointOverride()) {
            log.info("AWS EMR client using endpoint override {} (LocalStack/dev)", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }
}
