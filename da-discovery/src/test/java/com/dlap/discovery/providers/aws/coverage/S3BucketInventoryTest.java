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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.discovery.coverage.MissingPermissionException;
import com.dlap.discovery.providers.aws.coverage.S3BucketInventory.S3Bucket;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Q-830, AWS: the bucket inventory when GetBucketLocation fails for one bucket without a refusal.
 * The S3 client is a hand-written stub of the SDK interface, so the status codes the SDK would
 * raise after its own retries are exact.
 */
class S3BucketInventoryTest {

    /** An S3 client whose ListBuckets and GetBucketLocation answers are configured. */
    private static final class StubS3 implements S3Client {
        final Map<String, Function<String, GetBucketLocationResponse>> locations = new HashMap<>();
        RuntimeException listFailure;
        boolean closed;

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public ListBucketsResponse listBuckets() {
            if (listFailure != null) {
                throw listFailure;
            }
            return ListBucketsResponse.builder()
                    .buckets(locations.keySet().stream().sorted().map(n -> Bucket.builder().name(n).build()).toList())
                    .build();
        }

        @Override
        public GetBucketLocationResponse getBucketLocation(GetBucketLocationRequest request) {
            return locations.get(request.bucket()).apply(request.bucket());
        }
    }

    private static S3Exception s3(int status) {
        return (S3Exception) S3Exception.builder().statusCode(status).message("answer " + status).build();
    }

    private static Function<String, GetBucketLocationResponse> fails(RuntimeException e) {
        return name -> {
            throw e;
        };
    }

    private static Function<String, GetBucketLocationResponse> in(String location) {
        return name -> GetBucketLocationResponse.builder().locationConstraint(location).build();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 429, 500, 503})
    void aBucketLocationErrorLeavesOnlyThatBucketsRegionUnknownAndTheListingContinues(int status) {
        StubS3 stub = new StubS3();
        stub.locations.put("a-before", in("eu-west-1"));
        stub.locations.put("b-failing", fails(s3(status)));
        stub.locations.put("c-after", in(""));

        List<S3Bucket> out = new S3BucketInventory(() -> stub).list();

        assertThat(out).extracting(S3Bucket::name).containsExactly("a-before", "b-failing", "c-after");
        assertThat(out.get(0).region()).isEqualTo("eu-west-1");
        assertThat(out.get(1).region()).isNull();
        assertThat(out.get(1).regionDenied()).isNull();
        assertThat(out.get(1).regionUnknownReason())
                .isEqualTo("s3:GetBucketLocation call for arn:aws:s3:::b-failing answered HTTP " + status);
        assertThat(out.get(2).region()).as("an empty constraint is us-east-1").isEqualTo("us-east-1");
        assertThat(stub.closed).isTrue();
    }

    @Test
    void aTimeoutOnOneBucketLocationIsUnknownForThatBucketOnly() {
        StubS3 stub = new StubS3();
        stub.locations.put("slow", fails(SdkClientException.create("Unable to execute HTTP request: timed out")));
        stub.locations.put("fast", in("ap-south-1"));

        List<S3Bucket> out = new S3BucketInventory(() -> stub).list();

        assertThat(out.get(0).name()).isEqualTo("fast");
        assertThat(out.get(0).region()).isEqualTo("ap-south-1");
        assertThat(out.get(1).name()).isEqualTo("slow");
        assertThat(out.get(1).region()).isNull();
        assertThat(out.get(1).regionUnknownReason()).contains("arn:aws:s3:::slow").contains("SdkClientException");
    }

    @Test
    void aRefusedBucketLocationStillNamesThePermission() {
        StubS3 stub = new StubS3();
        stub.locations.put("hidden", fails(s3(403)));

        S3Bucket b = new S3BucketInventory(() -> stub).list().get(0);

        assertThat(b.region()).isNull();
        assertThat(b.regionDenied()).isNotNull();
        assertThat(b.regionUnknownReason())
                .isEqualTo("missing permission s3:GetBucketLocation on arn:aws:s3:::hidden");
    }

    @Test
    void anInterruptedRunIsNotSwallowedAsAnUnknownRegion() {
        StubS3 stub = new StubS3();
        stub.locations.put("a", fails(SdkClientException.create("aborted")));
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> new S3BucketInventory(() -> stub).list()).isInstanceOf(SdkClientException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFailureOfTheListingItselfStillEndsTheCollector() {
        StubS3 refused = new StubS3();
        refused.listFailure = s3(403);
        assertThatThrownBy(() -> new S3BucketInventory(() -> refused).list())
                .isInstanceOf(MissingPermissionException.class).hasMessageContaining("s3:ListAllMyBuckets");

        StubS3 broken = new StubS3();
        broken.listFailure = s3(503);
        assertThatThrownBy(() -> new S3BucketInventory(() -> broken).list()).isInstanceOf(S3Exception.class);
        assertThat(broken.closed).isTrue();
    }
}
