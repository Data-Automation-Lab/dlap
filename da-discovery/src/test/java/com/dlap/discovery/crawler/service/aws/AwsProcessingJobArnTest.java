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

package com.dlap.discovery.crawler.service.aws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.CanonicalAssetId;

/**
 * The ARN a job crawler hands to the normalizer carries the partition of the region it ran in, so a
 * GovCloud or China account is not written as a standard-partition resource.
 */
class AwsProcessingJobArnTest {

    private static final String ACCOUNT = "123456789012";

    @Test
    void thePartitionFollowsTheRegion() {
        assertThat(AwsProcessingJobResourceCrawler.arn("us-east-1", "glue", ACCOUNT, "job/etl"))
                .isEqualTo("arn:aws:glue:us-east-1:123456789012:job/etl");
        assertThat(AwsProcessingJobResourceCrawler.arn("cn-north-1", "glue", ACCOUNT, "job/etl"))
                .isEqualTo("arn:aws-cn:glue:cn-north-1:123456789012:job/etl");
        assertThat(AwsProcessingJobResourceCrawler.arn("us-gov-west-1", "glue", ACCOUNT, "job/etl"))
                .isEqualTo("arn:aws-us-gov:glue:us-gov-west-1:123456789012:job/etl");
        assertThat(AwsProcessingJobResourceCrawler.arn("us-iso-east-1", "glue", ACCOUNT, "job/etl"))
                .startsWith("arn:aws-iso:");
        assertThat(AwsProcessingJobResourceCrawler.arn("us-isob-east-1", "glue", ACCOUNT, "job/etl"))
                .startsWith("arn:aws-iso-b:");
        assertThat(AwsProcessingJobResourceCrawler.arn(null, "glue", ACCOUNT, "job/etl")).startsWith("arn:aws:glue:");
    }

    @Test
    void everyPartitionTheCrawlerWritesIsAcceptedByTheNormalizer() {
        for (String region : new String[] {"us-east-1", "cn-north-1", "us-gov-west-1"}) {
            String arn = AwsProcessingJobResourceCrawler.arn(region, "glue", ACCOUNT, "job/etl");

            CanonicalAssetId id = AssetIdNormalizer.normalize(arn, AssetIdHints.of(AssetKind.SPARK_JOB)).orElseThrow();

            assertThat(id.uri()).isEqualTo(arn);
            assertThat(id.kind()).isEqualTo(AssetKind.SPARK_JOB);
        }
    }

    @Test
    void aPythonShellHintKeepsTheKindOfAGlueJobArn() {
        String arn = AwsProcessingJobResourceCrawler.arn("us-east-1", "glue", ACCOUNT, "job/py");

        assertThat(AssetIdNormalizer.normalize(arn, AssetIdHints.of(AssetKind.PYTHON_JOB)).orElseThrow().kind())
                .isEqualTo(AssetKind.PYTHON_JOB);
        assertThat(AssetIdNormalizer.normalize(arn, AssetIdHints.of(AssetKind.SPARK_JOB)).orElseThrow().kind())
                .isEqualTo(AssetKind.SPARK_JOB);
    }
}
