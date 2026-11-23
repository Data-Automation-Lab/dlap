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

package com.dlap.autoarchival.providers.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.RuleScope;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * EXEC-01 (Q-722): a real STS exchange. moto implements STS AssumeRole, so the role credentials of the delete
 * executor are exchanged for temporary credentials by the real SDK client built from the settings, and the S3
 * rule provider then lists with them. moto does not check the trust policy or the external id (the unit tests in
 * {@code ActorCredentialsTest} pin the request that is sent); this proves the exchange, the session credentials and
 * the wiring work end to end. Requires Docker.
 */
@Testcontainers
class ActorCredentialsMotoTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/dlap-archive-actor";

    @Container
    static final GenericContainer<?> MOTO = new GenericContainer<>(DockerImageName.parse("motoserver/moto:5.1.4"))
            .withExposedPorts(5000)
            .waitingFor(Wait.forHttp("/moto-api/").forPort(5000).forStatusCode(200));

    private static String previousKey;
    private static String previousSecret;

    private static URI edge() {
        return URI.create("http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000));
    }

    @BeforeAll
    static void callerKeys() {
        // The role is assumed with the default chain as the caller; the system properties are one of its sources.
        previousKey = System.getProperty("aws.accessKeyId");
        previousSecret = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", "AKIACALLERTEST");
        System.setProperty("aws.secretAccessKey", "caller-secret");
    }

    @AfterAll
    static void restore() {
        restore("aws.accessKeyId", previousKey);
        restore("aws.secretAccessKey", previousSecret);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** Q-722: the configured role is exchanged by a real STS call and the session credentials list a scope. */
    @Test
    void theRoleIsAssumedWithStsAndItsSessionCredentialsListAScope() {
        ActorCredentials credentials = new ActorCredentials(ROLE, "ext-live", edge().toString(), "us-east-1");

        AwsCredentials session = credentials.provider().resolveCredentials();

        assertThat(session).isInstanceOf(AwsSessionCredentials.class);
        assertThat(session.accessKeyId()).isNotEqualTo("AKIACALLERTEST").isNotBlank();
        assertThat(((AwsSessionCredentials) session).sessionToken()).isNotBlank();

        String bucket = "exec01-" + Long.toHexString(System.nanoTime());
        try (S3Client admin = S3Client.builder().region(Region.US_EAST_1).endpointOverride(edge()).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
                S3Client asRole = S3LifecycleRuleProvider.build("us-east-1", edge().toString(), "", "", true,
                        credentials)) {
            admin.createBucket(b -> b.bucket(bucket));
            admin.putObject(b -> b.bucket(bucket).key("data/a.csv"), RequestBody.fromString("x"));
            NativeRuleProvider provider = new S3LifecycleRuleProvider(() -> asRole);

            NativeRuleProvider.Listing listing = provider.list(new RuleScope("AWS", "", bucket, "data/", false), 10);

            assertThat(listing.available()).isTrue();
            assertThat(listing.entries()).extracting(NativeRuleProvider.ListingEntry::key).containsExactly("data/a.csv");
        } finally {
            credentials.destroy();
        }
    }
}
