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

package com.dlap.discovery.log.service.aws.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parser tests for the AWS CloudTrail file-replay source. Proves a faithful CloudTrail export
 * maps to {@link AWSLogEntryAdapter}s the analyzer can turn into catalog assets - the container
 * resource (bucket / DB instance), not the object, is chosen as the asset identity.
 */
class AWSLoggingAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CLOUDTRAIL = """
        {"Records":[
          {"eventID":"e1","eventTime":"2026-07-05T12:00:00Z","eventSource":"s3.amazonaws.com",
           "eventName":"PutObject","awsRegion":"us-east-1","sourceIPAddress":"203.0.113.10",
           "userAgent":"aws-cli/2.15.0",
           "userIdentity":{"type":"IAMUser","arn":"arn:aws:iam::123456789012:user/alice","userName":"alice"},
           "requestParameters":{"bucketName":"finance-reports","key":"q2/report.csv"},
           "resources":[{"type":"AWS::S3::Object","ARN":"arn:aws:s3:::finance-reports/q2/report.csv"},
                        {"type":"AWS::S3::Bucket","ARN":"arn:aws:s3:::finance-reports"}],
           "eventType":"AwsApiCall"},
          {"eventID":"e2","eventTime":"2026-07-05T12:05:00Z","eventSource":"rds.amazonaws.com",
           "eventName":"CreateDBInstance","awsRegion":"eu-west-1",
           "userIdentity":{"arn":"arn:aws:iam::123456789012:user/dba"},
           "requestParameters":{"dBInstanceIdentifier":"prod-postgres"},
           "resources":[{"type":"AWS::RDS::DBInstance","ARN":"arn:aws:rds:eu-west-1:123456789012:db:prod-postgres"}],
           "eventType":"AwsApiCall"}
        ]}
        """;

    @Test
    void parsesCloudTrailRecordsAndPicksContainerResource() throws Exception {
        List<AWSLogEntryAdapter> entries =
                AWSLoggingAdapter.parseRecords(objectMapper.readTree(CLOUDTRAIL).path("Records"), 0);

        assertThat(entries).hasSize(2);

        AWSLogEntryAdapter s3 = entries.get(0);
        assertThat(s3.getEventName()).isEqualTo("PutObject");
        assertThat(s3.getEventSource()).isEqualTo("s3.amazonaws.com");
        assertThat(s3.getAwsRegion()).isEqualTo("us-east-1");
        assertThat(s3.getEventTime()).isEqualTo(Instant.parse("2026-07-05T12:00:00Z"));
        assertThat(s3.getUserIdentity()).isEqualTo("arn:aws:iam::123456789012:user/alice");
        // Container (bucket) chosen over the object; name is the last ARN segment.
        assertThat(s3.getResources().get("resourceId")).isEqualTo("arn:aws:s3:::finance-reports");
        assertThat(s3.getResources().get("resourceName")).isEqualTo("finance-reports");

        AWSLogEntryAdapter rds = entries.get(1);
        assertThat(rds.getEventName()).isEqualTo("CreateDBInstance");
        assertThat(rds.getResources().get("resourceId"))
                .isEqualTo("arn:aws:rds:eu-west-1:123456789012:db:prod-postgres");
        assertThat(rds.getResources().get("resourceName")).isEqualTo("prod-postgres");
    }

    @Test
    void maxResultsCapsOutput() throws Exception {
        List<AWSLogEntryAdapter> entries =
                AWSLoggingAdapter.parseRecords(objectMapper.readTree(CLOUDTRAIL).path("Records"), 1);
        assertThat(entries).hasSize(1);
    }

    @Test
    void queryLogsReturnsEmptyWhenNoReplayFileAndDeferred() {
        AWSLoggingAdapter adapter = new AWSLoggingAdapter(objectMapper, "");
        assertThat(adapter.queryLogs("123456789012", "filter", 10)).isEmpty();
    }
}
