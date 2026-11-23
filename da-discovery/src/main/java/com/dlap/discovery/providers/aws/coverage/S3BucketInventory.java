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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.coverage.MissingPermissionException;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The account's S3 buckets and their regions, through the S3 client of {@link AwsClientFactory}.
 *
 * <p>A GetBucketLocation that fails for one bucket without a refusal (a 404 for a bucket deleted
 * during the run, a 429 or 503 after the SDK's own retries, a timeout) leaves only that bucket's
 * region unknown, with the error as the reason, and the listing goes on (Q-830). A failure of
 * ListBuckets itself is not per bucket: it ends the listing and so the collector.
 */
public class S3BucketInventory {

    private static final Logger log = LoggerFactory.getLogger(S3BucketInventory.class);

    /**
     * A bucket.
     *
     * @param name          the bucket name
     * @param region        its region; {@code null} when GetBucketLocation was refused or failed
     * @param regionDenied  the refusal, when the region is unknown because it was refused
     * @param regionError   why the region is unknown when GetBucketLocation failed without a refusal
     *                      (a status or an error type, never a credential); {@code null} otherwise
     */
    public record S3Bucket(String name, String region, MissingPermissionException regionDenied, String regionError) {

        /**
         * A bucket whose region is known, or unknown because the read was refused.
         *
         * @param name         the bucket name
         * @param region       its region; {@code null} when refused
         * @param regionDenied the refusal, when the region is unknown
         */
        public S3Bucket(String name, String region, MissingPermissionException regionDenied) {
            this(name, region, regionDenied, null);
        }

        /**
         * Why the region is unknown.
         *
         * @return the refusal or the error text; {@code null} when the region is known
         */
        public String regionUnknownReason() {
            if (regionDenied != null) {
                return regionDenied.reason();
            }
            return regionError;
        }
    }

    private final Supplier<S3Client> s3;

    /**
     * Creates the inventory.
     *
     * @param clients       the AWS client factory
     * @param defaultRegion the region of the S3 client that lists the buckets
     */
    public S3BucketInventory(AwsClientFactory clients, String defaultRegion) {
        this(() -> clients.s3Client(Region.of(defaultRegion)));
    }

    /**
     * Creates the inventory on a given S3 client source (tests).
     *
     * @param s3 opens an S3 client; the inventory closes it
     */
    S3BucketInventory(Supplier<S3Client> s3) {
        this.s3 = s3;
    }

    /**
     * Lists the buckets.
     *
     * @return the buckets with their regions
     * @throws MissingPermissionException when ListBuckets is refused ({@code s3:ListAllMyBuckets})
     */
    public List<S3Bucket> list() {
        List<S3Bucket> out = new ArrayList<>();
        try (S3Client s3 = this.s3.get()) {
            List<Bucket> buckets;
            try {
                buckets = s3.listBuckets().buckets();
            } catch (S3Exception e) {
                if (e.statusCode() == 403) {
                    throw new MissingPermissionException("s3:ListAllMyBuckets", "the account");
                }
                throw e;
            }
            for (Bucket b : buckets) {
                try {
                    String location = s3.getBucketLocation(GetBucketLocationRequest.builder().bucket(b.name()).build())
                            .locationConstraintAsString();
                    out.add(new S3Bucket(b.name(), region(location), null));
                } catch (S3Exception e) {
                    if (e.statusCode() == 403) {
                        out.add(new S3Bucket(b.name(), null,
                                new MissingPermissionException("s3:GetBucketLocation", "arn:aws:s3:::" + b.name())));
                    } else {
                        out.add(failed(b.name(), "HTTP " + e.statusCode()));
                    }
                } catch (SdkException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw e;
                    }
                    out.add(failed(b.name(), e.getClass().getSimpleName()));
                }
            }
        }
        return out;
    }

    /** A bucket whose region could not be read for a reason other than a refusal (Q-830). */
    private static S3Bucket failed(String bucket, String what) {
        log.warn("AWS read coverage: s3:GetBucketLocation for {} failed: {}", bucket, what);
        return new S3Bucket(bucket, null, null,
                "s3:GetBucketLocation call for arn:aws:s3:::" + bucket + " answered " + what);
    }

    /**
     * The region of a location constraint: empty is {@code us-east-1} and {@code EU} is
     * {@code eu-west-1} (S3 API reference, GetBucketLocation).
     *
     * @param location the constraint
     * @return the region
     */
    static String region(String location) {
        if (location == null || location.isBlank() || "null".equals(location)) {
            return "us-east-1";
        }
        if ("EU".equals(location)) {
            return "eu-west-1";
        }
        return location;
    }
}
