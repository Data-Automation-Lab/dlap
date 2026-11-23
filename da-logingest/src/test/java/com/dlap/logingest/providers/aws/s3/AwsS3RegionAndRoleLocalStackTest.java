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

package com.dlap.logingest.providers.aws.s3;

import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.ACCOUNT;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.gzip;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.lifecycleDelete;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.trailFile;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.trailKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.parse.cloudtrail.CloudTrailFormat;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.StreamContext;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

/**
 * Two live checks of the S3 transports against LocalStack (community edition, S3 and STS; Docker
 * required; the image is pinned by digest, see {@link LocalStackImage} for how to pre-pull it):
 * <ul>
 *   <li>Q-684: region discovery. A trail stream with neither a {@code regions} key nor a scope region
 *       lists the folders under {@code AWSLogs/<account>/CloudTrail/} through the real SDK client and
 *       reads a file from a discovered region.</li>
 *   <li>Q-662: a {@code credentials-ref} role ARN with an external id is exchanged for temporary
 *       credentials by a real STS call, and the S3 client built from the stream lists and reads with
 *       them. LocalStack does not check the trust policy or the external id; the unit tests in
 *       {@code SdkS3LogStoreTest} pin the request the transport sends, this test proves the exchange and
 *       the session credentials work end to end.</li>
 * </ul>
 */
class AwsS3RegionAndRoleLocalStackTest {

    private static final String BUCKET = "dlap-log05-trail";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/dlap-log-reader";
    private static final FetchWindow WINDOW = new FetchWindow(Instant.parse("2026-11-02T03:00:00Z"),
            Instant.parse("2026-11-02T05:00:00Z"));

    @SuppressWarnings("resource")
    private static final GenericContainer<?> LOCALSTACK = new GenericContainer<>(LocalStackImage.name())
            .withEnv("SERVICES", "s3,sts")
            .withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200));

    private static S3Client admin;

    @BeforeAll
    static void start() {
        LOCALSTACK.start();
        // The transports read the SDK default chain; the system properties are one of its sources.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        admin = S3Client.builder().endpointOverride(endpoint()).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        admin.createBucket(b -> b.bucket(BUCKET));
        put(trailKey("", "us-east-1", "20261102T0315Z", "UsEast100000001"), "raw/us.parquet", "0001");
        put(trailKey("", "eu-west-1", "20261102T0316Z", "EuWest100000001"), "raw/eu.parquet", "0002");
        put(trailKey("", "ap-southeast-2", "20261102T0317Z", "ApSouth100000001"), "raw/ap.parquet", "0003");
        // A folder that is no region, and the digest tree beside CloudTrail: neither is a partition.
        admin.putObject(b -> b.bucket(BUCKET).key("AWSLogs/" + ACCOUNT + "/CloudTrail/not-a-region/x"),
                RequestBody.fromString("x"));
        admin.putObject(b -> b.bucket(BUCKET).key("AWSLogs/" + ACCOUNT + "/CloudTrail-Digest/us-east-1/y"),
                RequestBody.fromString("y"));
    }

    @AfterAll
    static void stop() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
        admin.close();
        LOCALSTACK.stop();
    }

    private static String edge() {
        return endpoint().toString();
    }

    private static URI endpoint() {
        return URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
    }

    private static void put(String key, String object, String id) {
        byte[] file = gzip(trailFile(lifecycleDelete("0a5e0c11-1fe0-4e00-8000-00000000" + id,
                "2026-11-02T03:16:07Z", object)));
        admin.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(file));
    }

    /** A trail stream with no regions key and no scope region; the client region is the location key. */
    private static StreamContext discovery(String credentialsRef, Map<String, String> extra) {
        Map<String, String> location = new LinkedHashMap<>(extra);
        location.put("bucket", BUCKET);
        location.put("region", "us-east-1");
        location.put("endpoint", edge());
        location.put("path-style", "true");
        return AwsS3ForTests.context(CloudTrailTrailFileTransport.ID, CloudTrailFormat.SOURCE_KIND,
                CloudTrailFormat.S3_FILE, null, location, new AwsS3ForTests.DoneObjects(),
                com.dlap.logingest.parse.core.BindingConfig.DEFAULT_MAX_RECORD_BYTES, credentialsRef);
    }

    @Test
    void theRegionsOfAnAccountAreDiscoveredFromTheBucketAndEachIsRead() {
        SdkS3LogStore store = new SdkS3LogStore();
        try {
            CloudTrailTrailFileTransport transport = new CloudTrailTrailFileTransport(store);
            StreamContext ctx = discovery(null, Map.of());

            List<String> partitions = transport.partitions(ctx);

            assertThat(partitions).as("region folders only, sorted; no digest tree, no non-region folder")
                    .containsExactly("ap-southeast-2", "eu-west-1", "us-east-1");
            for (String region : partitions) {
                List<RawBatch> batches = AwsS3ForTests.drain(transport.fetch(ctx, region, CursorPosition.INITIAL,
                        WINDOW));
                assertThat(batches).as("region %s: its file, then the window-end batch", region).hasSize(2);
                assertThat(batches.get(0).object().orElseThrow().key()).contains("/CloudTrail/" + region + "/");
                assertThat(batches.get(0).records()).hasSize(1);
            }
        } finally {
            store.destroy();
        }
    }

    @Test
    void aRoleArnCredentialsRefIsExchangedForSessionCredentialsByARealStsCall() {
        java.util.ArrayList<AutoCloseable> owned = new java.util.ArrayList<>();
        StreamContext ctx = discovery(ROLE, Map.of("external-id", "dlap-ext-1", "sts-endpoint", edge()));
        try {
            AwsCredentialsProvider provider = SdkS3LogStore.credentials(ctx, System::getenv,
                    (c, caller) -> software.amazon.awssdk.services.sts.StsClient.builder().credentialsProvider(caller)
                            .region(SdkS3LogStore.region(c)).endpointOverride(URI.create(edge())).build(), owned);
            assertThat(provider).isInstanceOf(StsAssumeRoleCredentialsProvider.class);

            AwsCredentials assumed = provider.resolveCredentials();

            assertThat(assumed).isInstanceOf(AwsSessionCredentials.class);
            assertThat(assumed.accessKeyId()).as("not the caller's key").isNotEqualTo("test").isNotBlank();
            assertThat(((AwsSessionCredentials) assumed).sessionToken()).isNotBlank();
        } finally {
            owned.forEach(c -> {
                try {
                    c.close();
                } catch (Exception e) {
                    // test cleanup
                }
            });
        }
    }

    @Test
    void theStoreListsAndReadsThroughTheAssumedRoleWithTheStreamsStsEndpoint() {
        SdkS3LogStore store = new SdkS3LogStore();
        try {
            CloudTrailTrailFileTransport transport = new CloudTrailTrailFileTransport(store);
            StreamContext ctx = discovery(ROLE, Map.of("external-id", "dlap-ext-1", "sts-endpoint", edge(),
                    "regions", "us-east-1"));

            assertThat(transport.partitions(ctx)).containsExactly("us-east-1");
            List<RawBatch> batches = AwsS3ForTests.drain(transport.fetch(ctx, "us-east-1", CursorPosition.INITIAL,
                    WINDOW));

            assertThat(batches.get(0).records()).hasSize(1);
        } finally {
            store.destroy();
        }
    }
}
