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

import static com.dlap.discovery.coverage.CoverageFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.discovery.coverage.CoverageFixtures;
import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.dlap.discovery.coverage.CoverageLevel;
import com.dlap.discovery.coverage.LocalJsonServer;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.dlap.discovery.coverage.ReadCoverageObservation;
import com.dlap.discovery.providers.aws.coverage.AwsSignedJsonClient.CloudTrailApiException;
import com.dlap.discovery.providers.aws.coverage.S3BucketInventory.S3Bucket;
import com.fasterxml.jackson.databind.JsonNode;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/** COV-01, AWS: the collector over a fake CloudTrail built from the API-reference fixtures. */
class AwsReadCoverageCollectorTest {

    private static final String ORG = "arn:aws:cloudtrail:us-east-1:111122223333:trail/org-trail";
    private static final String EU = "arn:aws:cloudtrail:eu-west-1:111122223333:trail/eu-trail";

    /** A CloudTrail whose answers are fixtures; a missing answer is a refusal. */
    static final class FakeCloudTrail implements CloudTrailApi {
        final Map<String, JsonNode> selectors = new HashMap<>();
        final Map<String, JsonNode> status = new HashMap<>();
        final List<String> deniedRegions = new ArrayList<>();
        final Map<String, RuntimeException> failingRegions = new HashMap<>();
        final Map<String, RuntimeException> failingStatus = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        RuntimeException callerFailure;

        @Override
        public JsonNode describeTrails(String region) {
            calls.add("DescribeTrails " + region);
            if (deniedRegions.contains(region)) {
                throw new MissingPermissionException("cloudtrail:DescribeTrails", "region " + region);
            }
            if (failingRegions.containsKey(region)) {
                throw failingRegions.get(region);
            }
            return json("aws/describe-trails.json");
        }

        @Override
        public JsonNode getEventSelectors(String trailArn, String homeRegion) {
            calls.add("GetEventSelectors " + trailArn + " " + homeRegion);
            JsonNode s = selectors.get(trailArn);
            if (s == null) {
                throw new MissingPermissionException("cloudtrail:GetEventSelectors", trailArn);
            }
            return s;
        }

        @Override
        public JsonNode getTrailStatus(String trailArn, String homeRegion) {
            calls.add("GetTrailStatus " + trailArn + " " + homeRegion);
            if (failingStatus.containsKey(trailArn)) {
                throw failingStatus.get(trailArn);
            }
            return status.getOrDefault(trailArn, json("aws/trail-status-logging.json"));
        }

        @Override
        public String callerAccount(String region) {
            if (callerFailure != null) {
                throw callerFailure;
            }
            return "111122223333";
        }
    }

    private static List<ReadCoverageObservation> run(FakeCloudTrail ct, S3Bucket... buckets) {
        return new AwsReadCoverageCollector(() -> List.of(buckets), ct, "us-east-1").collect();
    }

    @Test
    void oneObservationPerBucketWithTheAccountScopeAndCloudTrailChannel() {
        FakeCloudTrail ct = new FakeCloudTrail();
        ct.selectors.put(ORG, json("aws/selectors-basic-prefix.json"));
        ct.selectors.put(EU, json("aws/selectors-basic-bucket.json"));

        List<ReadCoverageObservation> out = run(ct,
                new S3Bucket("finance-raw", "eu-west-1", null),
                new S3Bucket("web-assets", "us-east-1", null));

        assertThat(out).hasSize(2);
        ReadCoverageObservation raw = out.get(0);
        assertThat(raw.rootUri()).isEqualTo("s3://finance-raw");
        assertThat(raw.scopeId()).isEqualTo("111122223333");
        assertThat(raw.provider()).isEqualTo("AWS");
        assertThat(raw.channel()).isEqualTo("AWS_CLOUDTRAIL");
        assertThat(raw.appliesTo()).containsExactly("S3_BUCKET", "S3_OBJECT");
        assertThat(raw.collector()).isEqualTo("aws-cloudtrail-coverage@1.0.0");
        assertThat(raw.verdict().level()).as("the eu trail selects the bucket").isEqualTo(CoverageLevel.FULL);

        ReadCoverageObservation web = out.get(1);
        assertThat(web.verdict().level()).as("only the org trail's prefix applies in us-east-1")
                .isEqualTo(CoverageLevel.NONE);
    }

    @Test
    void trailsAreReadOnceInTheirHomeRegion() {
        FakeCloudTrail ct = new FakeCloudTrail();
        ct.selectors.put(ORG, json("aws/selectors-basic-all-buckets.json"));
        ct.selectors.put(EU, json("aws/selectors-basic-bucket.json"));

        run(ct, new S3Bucket("a", "eu-west-1", null), new S3Bucket("b", "us-east-1", null),
                new S3Bucket("c", "eu-west-1", null));

        assertThat(ct.calls).containsExactly(
                "DescribeTrails eu-west-1",
                "GetTrailStatus " + ORG + " us-east-1",
                "GetEventSelectors " + ORG + " us-east-1",
                "GetTrailStatus " + EU + " eu-west-1",
                "GetEventSelectors " + EU + " eu-west-1",
                "DescribeTrails us-east-1");
    }

    @Test
    void refusedReadsMakeTheAffectedBucketsUnknownAndNameThePermission() {
        FakeCloudTrail ct = new FakeCloudTrail();
        ct.selectors.put(EU, json("aws/selectors-basic-bucket.json"));
        // org-trail selectors refused, us-west-2 DescribeTrails refused.
        ct.deniedRegions.add("us-west-2");
        List<ReadCoverageObservation> out = run(ct,
                new S3Bucket("finance-raw", "eu-west-1", null),
                new S3Bucket("web-assets", "us-east-1", null),
                new S3Bucket("logs", "us-west-2", null),
                new S3Bucket("hidden", null, new MissingPermissionException("s3:GetBucketLocation",
                        "arn:aws:s3:::hidden")));

        assertThat(out.get(0).verdict().level()).as("the eu trail proves it despite the refused org trail")
                .isEqualTo(CoverageLevel.FULL);
        assertThat(out.get(1).verdict().known()).isFalse();
        assertThat(out.get(1).verdict().reasons())
                .containsExactly("missing permission cloudtrail:GetEventSelectors on " + ORG);
        assertThat(out.get(2).verdict().reasons())
                .containsExactly("missing permission cloudtrail:DescribeTrails on region us-west-2");
        assertThat(out.get(3).verdict().known()).isFalse();
        assertThat(out.get(3).verdict().reasons())
                .containsExactly("missing permission s3:GetBucketLocation on arn:aws:s3:::hidden");
    }

    @Test
    void aNonRefusalErrorOnOneRegionMakesOnlyThatRegionsBucketsUnknownAndTheRunContinues() {
        FakeCloudTrail ct = new FakeCloudTrail();
        ct.selectors.put(EU, json("aws/selectors-basic-bucket.json"));
        ct.selectors.put(ORG, json("aws/selectors-basic-prefix.json"));
        ct.failingRegions.put("us-west-2",
                new CloudTrailApiException("DescribeTrails answered HTTP 429 ThrottlingException"));

        List<ReadCoverageObservation> out = run(ct,
                new S3Bucket("finance-raw", "eu-west-1", null),
                new S3Bucket("logs", "us-west-2", null),
                new S3Bucket("logs-2", "us-west-2", null),
                new S3Bucket("web-assets", "us-east-1", null));

        assertThat(out).hasSize(4);
        assertThat(out.get(0).verdict().level()).as("region before").isEqualTo(CoverageLevel.FULL);
        for (int i : new int[] {1, 2}) {
            assertThat(out.get(i).verdict().known()).isFalse();
            assertThat(out.get(i).verdict().reasons()).singleElement().asString()
                    .contains("cloudtrail:DescribeTrails").contains("us-west-2").contains("HTTP 429");
        }
        assertThat(out.get(3).verdict().level()).as("region after").isEqualTo(CoverageLevel.NONE);
        assertThat(ct.calls.stream().filter(c -> c.equals("DescribeTrails us-west-2"))).as("asked once per region")
                .hasSize(1);
    }

    @Test
    void aTransportFailureOnOneRegionIsUnknownForThatRegionOnly() {
        FakeCloudTrail ct = new FakeCloudTrail();
        ct.selectors.put(EU, json("aws/selectors-basic-bucket.json"));
        ct.failingRegions.put("us-east-1", new CoverageTransportException(
                "call to https://cloudtrail.us-east-1.amazonaws.com/ failed: HttpTimeoutException"));

        List<ReadCoverageObservation> out = run(ct,
                new S3Bucket("web-assets", "us-east-1", null),
                new S3Bucket("finance-raw", "eu-west-1", null));

        assertThat(out.get(0).verdict().known()).isFalse();
        assertThat(out.get(0).verdict().reasons()).singleElement().asString().contains("HttpTimeoutException");
        assertThat(out.get(1).verdict().level()).isEqualTo(CoverageLevel.FULL);
    }

    @Test
    void aTrailWhoseStatusCannotBeReadIsUnknownForThoseBucketsAndTheRunContinues() {
        FakeCloudTrail ct = new FakeCloudTrail();
        ct.selectors.put(ORG, json("aws/selectors-basic-all-buckets.json"));
        ct.selectors.put(EU, json("aws/selectors-basic-bucket.json"));
        ct.failingStatus.put(ORG, new CoverageTransportException("call to https://cloudtrail.us-east-1.amazonaws.com/"
                + " failed: HttpTimeoutException"));

        List<ReadCoverageObservation> out = run(ct,
                new S3Bucket("web-assets", "us-east-1", null),
                new S3Bucket("finance-raw", "eu-west-1", null));

        assertThat(out.get(0).verdict().known()).isFalse();
        assertThat(out.get(0).verdict().reasons()).singleElement().asString().contains(ORG)
                .contains("HttpTimeoutException");
        assertThat(out.get(1).verdict().level()).as("the eu trail still proves the eu bucket")
                .isEqualTo(CoverageLevel.FULL);
    }

    @Test
    void aBucketWhoseRegionCannotBeReadIsUnknownWithTheErrorAndTheRunContinues() {
        FakeCloudTrail ct = new FakeCloudTrail();
        ct.selectors.put(EU, json("aws/selectors-basic-bucket.json"));

        List<ReadCoverageObservation> out = run(ct,
                new S3Bucket("gone", null, null, "s3:GetBucketLocation call for arn:aws:s3:::gone answered HTTP 404"),
                new S3Bucket("finance-raw", "eu-west-1", null));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).verdict().known()).isFalse();
        assertThat(out.get(0).verdict().reasons())
                .containsExactly("s3:GetBucketLocation call for arn:aws:s3:::gone answered HTTP 404");
        assertThat(out.get(1).verdict().level()).isEqualTo(CoverageLevel.FULL);
    }

    @Test
    void anInterruptedRunIsNotSwallowedAsAnUnknownRegion() {
        FakeCloudTrail ct = new FakeCloudTrail();
        ct.failingRegions.put("us-east-1", new CoverageTransportException("interrupted calling cloudtrail"));
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> run(ct, new S3Bucket("web-assets", "us-east-1", null)))
                    .isInstanceOf(CoverageTransportException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFailureOfTheWholeCollectorStillPublishesNothing() {
        FakeCloudTrail ct = new FakeCloudTrail();
        // The bucket listing is refused: nothing to name the observations by.
        assertThatThrownBy(() -> new AwsReadCoverageCollector(() -> {
            throw new MissingPermissionException("s3:ListAllMyBuckets", "the account");
        }, ct, "us-east-1").collect()).isInstanceOf(MissingPermissionException.class);

        // The account lookup fails: the scope of every observation is unknown.
        ct.callerFailure = new CoverageTransportException("STS GetCallerIdentity answered HTTP 503");
        assertThatThrownBy(() -> run(ct, new S3Bucket("a", "us-east-1", null)))
                .isInstanceOf(CoverageTransportException.class).hasMessageContaining("STS");

        // The bucket listing fails without a refusal.
        assertThatThrownBy(() -> new AwsReadCoverageCollector(() -> {
            throw new CoverageTransportException("ListBuckets answered HTTP 500");
        }, new FakeCloudTrail(), "us-east-1").collect()).isInstanceOf(CoverageTransportException.class);
    }

    /** The collector over the real SigV4 client and a local server, one path per region. */
    private static List<ReadCoverageObservation> collectOver(LocalJsonServer server, S3Bucket... buckets) {
        AwsSignedJsonClient client = new AwsSignedJsonClient(CoverageFixtures.http(),
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "test-secret-not-real")),
                region -> URI.create(server.base() + "/ct/" + region + "/"),
                region -> URI.create(server.base() + "/sts/"));
        return new AwsReadCoverageCollector(() -> List.of(buckets), client, "us-east-1").collect();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 429, 500, 503})
    void aRegionAnsweringAnHttpErrorIsUnknownAndTheOtherRegionsStillPublish(int status) throws IOException {
        try (LocalJsonServer server = new LocalJsonServer()
                .answer("POST /sts/", 200,
                        "<GetCallerIdentityResponse><Account>111122223333</Account></GetCallerIdentityResponse>")
                .answer("POST /ct/us-west-2/", status, "{\"__type\":\"com.amazonaws.cloudtrail#ThrottlingException\"}")
                .answer("POST /ct/eu-west-1/", 200, "{\"trailList\":[]}")) {
            List<ReadCoverageObservation> out = collectOver(server,
                    new S3Bucket("logs", "us-west-2", null),
                    new S3Bucket("finance-raw", "eu-west-1", null));

            assertThat(out).extracting(ReadCoverageObservation::rootUri).containsExactly("s3://logs", "s3://finance-raw");
            assertThat(out.get(0).verdict().known()).isFalse();
            assertThat(out.get(0).verdict().reasons()).singleElement().asString()
                    .contains("DescribeTrails").contains("us-west-2").contains("HTTP " + status);
            assertThat(out.get(1).verdict().level()).as("a region with no trail is NONE, not unknown")
                    .isEqualTo(CoverageLevel.NONE);
            assertThat(out.get(1).scopeId()).isEqualTo("111122223333");
        }
    }

    @Test
    void aStsFailureOverTheRealClientEndsTheCollector() throws IOException {
        try (LocalJsonServer server = new LocalJsonServer()
                .answer("POST /sts/", 503, "<Error/>")
                .answer("POST /ct/us-east-1/", 200, "{\"trailList\":[]}")) {
            assertThatThrownBy(() -> collectOver(server, new S3Bucket("a", "us-east-1", null)))
                    .isInstanceOf(CoverageTransportException.class).hasMessageContaining("GetCallerIdentity");
        }
    }

    @Test
    void aStoppedTrailIsReportedAsNotLogging() {
        FakeCloudTrail ct = new FakeCloudTrail();
        ct.selectors.put(ORG, json("aws/selectors-basic-all-buckets.json"));
        ct.selectors.put(EU, json("aws/selectors-basic-bucket.json"));
        ct.status.put(ORG, json("aws/trail-status-stopped.json"));

        ReadCoverageObservation o = run(ct, new S3Bucket("web-assets", "us-east-1", null)).get(0);
        assertThat(o.verdict().level()).isEqualTo(CoverageLevel.NONE);
        assertThat(o.verdict().reasons()).contains("trail " + ORG + " is not logging");
    }

    @Test
    void bucketRegionsFollowTheGetBucketLocationRules() {
        assertThat(S3BucketInventory.region("")).isEqualTo("us-east-1");
        assertThat(S3BucketInventory.region(null)).isEqualTo("us-east-1");
        assertThat(S3BucketInventory.region("EU")).isEqualTo("eu-west-1");
        assertThat(S3BucketInventory.region("ap-south-1")).isEqualTo("ap-south-1");
    }
}
