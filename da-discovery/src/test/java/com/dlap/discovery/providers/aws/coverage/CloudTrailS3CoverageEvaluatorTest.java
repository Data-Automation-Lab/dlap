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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dlap.discovery.coverage.CoverageLevel;
import com.dlap.discovery.coverage.CoverageVerdict;
import com.dlap.discovery.providers.aws.coverage.CloudTrailS3CoverageEvaluator.TrailState;

/**
 * COV-01, AWS: the coverage of one bucket from CloudTrail responses written from the CloudTrail
 * API reference ({@code src/test/resources/coverage/aws/meta.yaml}).
 */
class CloudTrailS3CoverageEvaluatorTest {

    private static final String ORG = "arn:aws:cloudtrail:us-east-1:111122223333:trail/org-trail";
    private static final String EU = "arn:aws:cloudtrail:eu-west-1:111122223333:trail/eu-trail";

    private static TrailState org(String selectorsFixture) {
        return new TrailState(ORG, "us-east-1", true, true, json("aws/" + selectorsFixture), null);
    }

    private static CoverageVerdict bucket(String name, String region, TrailState... trails) {
        return CloudTrailS3CoverageEvaluator.evaluate("aws", name, region, List.of(trails));
    }

    @Test
    void describeTrailsGivesArnHomeRegionAndMultiRegionFlag() {
        List<TrailState> trails = CloudTrailS3CoverageEvaluator.trails(json("aws/describe-trails.json"));
        assertThat(trails).extracting(TrailState::arn).containsExactly(ORG, EU);
        assertThat(trails.get(0).multiRegion()).isTrue();
        assertThat(trails.get(1).homeRegion()).isEqualTo("eu-west-1");
        assertThat(trails.get(1).multiRegion()).isFalse();
    }

    @Test
    void aBasicSelectorForEveryBucketIsFull() {
        CoverageVerdict v = bucket("finance-raw", "ap-south-1", org("selectors-basic-all-buckets.json"));
        assertThat(v.level()).isEqualTo(CoverageLevel.FULL);
        assertThat(v.reasons()).containsExactly("trail " + ORG + " records S3 data read events for the bucket");
    }

    @Test
    void aBasicSelectorOnTheBucketIsFullAndAPrefixIsPartial() {
        TrailState eu = new TrailState(EU, "eu-west-1", false, true, json("aws/selectors-basic-bucket.json"), null);
        assertThat(bucket("finance-raw", "eu-west-1", eu).level()).isEqualTo(CoverageLevel.FULL);

        CoverageVerdict images = bucket("web-assets", "eu-west-1", eu);
        assertThat(images.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(images.reasons()).containsExactly(
                CloudTrailS3CoverageEvaluator.PREFIX_SUBSET + " (trail " + EU + ")");

        assertThat(bucket("other-bucket", "eu-west-1", eu).level()).isEqualTo(CoverageLevel.NONE);
    }

    @Test
    void aSingleRegionTrailDoesNotCoverABucketInAnotherRegion() {
        TrailState eu = new TrailState(EU, "eu-west-1", false, true, json("aws/selectors-basic-bucket.json"), null);
        CoverageVerdict v = bucket("finance-raw", "us-east-1", eu);
        assertThat(v.level()).isEqualTo(CoverageLevel.NONE);
        assertThat(v.reasons()).containsExactly("no logging CloudTrail trail records S3 data read events for the bucket");
    }

    @Test
    void aPrefixSelectorIsPartialWithTheSpecWording() {
        CoverageVerdict v = bucket("finance-raw", "us-east-1", org("selectors-basic-prefix.json"));
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(v.reasons()).allMatch(r -> r.startsWith("CloudTrail data events cover a prefix subset"));
    }

    @Test
    void writeOnlyAndManagementOnlySelectorsRecordNoRead() {
        assertThat(bucket("finance-raw", "us-east-1", org("selectors-basic-write-only.json")).level())
                .isEqualTo(CoverageLevel.NONE);
        assertThat(bucket("finance-raw", "us-east-1", org("selectors-management-only.json")).level())
                .isEqualTo(CoverageLevel.NONE);
        assertThat(bucket("finance-raw", "us-east-1", org("selectors-advanced-writes-only.json")).level())
                .isEqualTo(CoverageLevel.NONE);
    }

    @Test
    void anAdvancedSelectorStartingWithTheBucketArnIsFullForThatBucketOnly() {
        assertThat(bucket("finance-raw", "us-east-1", org("selectors-advanced-bucket-reads.json")).level())
                .isEqualTo(CoverageLevel.FULL);
        assertThat(bucket("finance-curated", "us-east-1", org("selectors-advanced-bucket-reads.json")).level())
                .isEqualTo(CoverageLevel.NONE);
    }

    @Test
    void anExcludedPrefixIsPartialAndAnExcludedBucketIsNone() {
        CoverageVerdict partly = bucket("finance-raw", "us-east-1", org("selectors-advanced-excludes-prefix.json"));
        assertThat(partly.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(partly.reasons()).anyMatch(r -> r.contains("excludes a key prefix of the bucket"));

        assertThat(bucket("scratch-bucket", "us-east-1", org("selectors-advanced-excludes-prefix.json")).level())
                .isEqualTo(CoverageLevel.NONE);
        assertThat(bucket("web-assets", "us-east-1", org("selectors-advanced-excludes-prefix.json")).level())
                .as("an exclusion of other buckets leaves this one fully covered")
                .isEqualTo(CoverageLevel.FULL);
    }

    @Test
    void filtersOnEventNameOrPrincipalArePartialAndNameTheField() {
        CoverageVerdict v = bucket("finance-raw", "us-east-1", org("selectors-advanced-event-name.json"));
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(v.reasons()).anyMatch(r -> r.contains("filters on eventName"))
                .anyMatch(r -> r.contains("filters on userIdentity.arn"));
        assertThat(String.join(" ", v.reasons())).as("no principal is quoted").doesNotContain("backup");
    }

    @Test
    void aStoppedTrailCountsForNothing() {
        TrailState stopped = new TrailState(ORG, "us-east-1", true, false, json("aws/selectors-basic-all-buckets.json"),
                null);
        CoverageVerdict v = bucket("finance-raw", "us-east-1", stopped);
        assertThat(v.level()).isEqualTo(CoverageLevel.NONE);
        assertThat(v.reasons()).contains("trail " + ORG + " is not logging");
    }

    @Test
    void anUnreadableTrailMakesItUnknownUnlessAnotherTrailProvesFull() {
        TrailState unreadable = new TrailState(EU, "eu-west-1", true, null, null,
                "missing permission cloudtrail:GetEventSelectors on " + EU);
        CoverageVerdict unknown = bucket("finance-raw", "eu-west-1", unreadable, org("selectors-basic-prefix.json"));
        assertThat(unknown.known()).isFalse();
        assertThat(unknown.reasons()).first().isEqualTo("missing permission cloudtrail:GetEventSelectors on " + EU);

        CoverageVerdict full = bucket("finance-raw", "eu-west-1", unreadable, org("selectors-basic-all-buckets.json"));
        assertThat(full.level()).isEqualTo(CoverageLevel.FULL);
    }

    @Test
    void prefixValuesFollowTheDataResourceRules() {
        String objects = "arn:aws:s3:::finance-raw/";
        assertThat(CloudTrailS3CoverageEvaluator.prefixValue("arn:aws:s3", objects).cover())
                .isEqualTo(CloudTrailS3CoverageEvaluator.Cover.FULL);
        assertThat(CloudTrailS3CoverageEvaluator.prefixValue("arn:aws:s3:::finance-raw/", objects).cover())
                .isEqualTo(CloudTrailS3CoverageEvaluator.Cover.FULL);
        assertThat(CloudTrailS3CoverageEvaluator.prefixValue("arn:aws:s3:::finance-raw/a", objects).cover())
                .isEqualTo(CloudTrailS3CoverageEvaluator.Cover.PARTIAL);
        assertThat(CloudTrailS3CoverageEvaluator.prefixValue("arn:aws:s3:::finance-raw2/", objects).cover())
                .isEqualTo(CloudTrailS3CoverageEvaluator.Cover.NONE);
        assertThat(CloudTrailS3CoverageEvaluator.prefixValue("", objects).cover())
                .isEqualTo(CloudTrailS3CoverageEvaluator.Cover.NONE);
    }

    @Test
    void partitionsFollowTheRegion() {
        assertThat(CloudTrailS3CoverageEvaluator.partition("cn-north-1")).isEqualTo("aws-cn");
        assertThat(CloudTrailS3CoverageEvaluator.partition("us-gov-west-1")).isEqualTo("aws-us-gov");
        assertThat(CloudTrailS3CoverageEvaluator.partition("eu-west-1")).isEqualTo("aws");
    }
}
