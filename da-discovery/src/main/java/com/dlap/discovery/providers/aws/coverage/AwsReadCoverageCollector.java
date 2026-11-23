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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.dlap.discovery.coverage.CoverageVerdict;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.dlap.discovery.coverage.ReadCoverageCollector;
import com.dlap.discovery.coverage.ReadCoverageObservation;
import com.dlap.discovery.providers.aws.coverage.AwsSignedJsonClient.CloudTrailApiException;
import com.dlap.discovery.providers.aws.coverage.CloudTrailS3CoverageEvaluator.TrailState;
import com.dlap.discovery.providers.aws.coverage.S3BucketInventory.S3Bucket;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * AWS read coverage (COV-01): for every S3 bucket of the account, whether a logging CloudTrail
 * trail records its object reads ({@link CloudTrailS3CoverageEvaluator}). One observation per
 * bucket, root {@code s3://<bucket>}, for {@code S3_BUCKET} and {@code S3_OBJECT}, channel
 * {@code AWS_CLOUDTRAIL}, scope the account of the credentials.
 *
 * <p>Calls: S3 ListBuckets and GetBucketLocation; per bucket region CloudTrail DescribeTrails
 * (with shadow trails, so multi-region and organization trails homed elsewhere are seen); per
 * trail GetTrailStatus and GetEventSelectors in its home region; STS GetCallerIdentity once. A
 * refused call makes the affected buckets unknown, naming the IAM action.
 *
 * <p>A failure that is not a refusal and belongs to one resource (a 404 for a bucket or trail
 * deleted mid-run, a 429, a 5xx, a timeout) makes only that resource's buckets unknown, with the
 * status as the reason, and the run goes on: GetBucketLocation of one bucket, DescribeTrails of
 * one region, the status or selectors of one trail (Q-830, Q-474). A failure of the bucket
 * listing or of STS GetCallerIdentity is not per resource: the exception ends the collector,
 * nothing is published, and the catalog keeps what it had (fail closed).
 */
public class AwsReadCoverageCollector implements ReadCoverageCollector {

    private static final Logger log = LoggerFactory.getLogger(AwsReadCoverageCollector.class);

    /** Collector id. */
    public static final String ID = "aws-cloudtrail-coverage";
    /** Collector version, sent as the observation's parser version. */
    public static final String VERSION = "1.0.0";
    /** Source kind that carries CloudTrail records (log-event.md section 5.2). */
    public static final String CHANNEL = "AWS_CLOUDTRAIL";

    private final Supplier<List<S3Bucket>> buckets;
    private final CloudTrailApi cloudTrail;
    private final String stsRegion;

    /**
     * Creates the collector.
     *
     * @param buckets    lists the buckets
     * @param cloudTrail the CloudTrail reads
     * @param stsRegion  the region for STS and for the account lookup
     */
    public AwsReadCoverageCollector(Supplier<List<S3Bucket>> buckets, CloudTrailApi cloudTrail, String stsRegion) {
        this.buckets = buckets;
        this.cloudTrail = cloudTrail;
        this.stsRegion = stsRegion;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<ReadCoverageObservation> collect() {
        String account = cloudTrail.callerAccount(stsRegion);
        List<S3Bucket> all = buckets.get();

        // Regions with buckets, then the trails that apply to each (deduplicated by ARN).
        Map<String, List<TrailState>> trailsByRegion = new LinkedHashMap<>();
        Map<String, String> unknownRegions = new LinkedHashMap<>();
        Map<String, TrailState> resolved = new LinkedHashMap<>();
        for (S3Bucket b : all) {
            if (b.region() == null || trailsByRegion.containsKey(b.region()) || unknownRegions.containsKey(b.region())) {
                continue;
            }
            try {
                List<TrailState> trails = new ArrayList<>();
                for (TrailState t : CloudTrailS3CoverageEvaluator.trails(cloudTrail.describeTrails(b.region()))) {
                    trails.add(resolved.computeIfAbsent(t.arn(), arn -> resolve(t)));
                }
                trailsByRegion.put(b.region(), trails);
            } catch (MissingPermissionException e) {
                unknownRegions.put(b.region(), e.reason());
            } catch (CloudTrailApiException | CoverageTransportException e) {
                rethrowIfInterrupted(e);
                // One region's trails are unreadable (throttled, a server error, a timeout): the buckets
                // of that region are unknown with the status as the reason, and the run goes on (Q-830).
                log.warn("AWS read coverage: the trails of region {} could not be read: {}", b.region(),
                        e.getMessage());
                unknownRegions.put(b.region(), "cloudtrail:DescribeTrails call for region " + b.region() + ": "
                        + e.getMessage());
            }
        }

        List<ReadCoverageObservation> out = new ArrayList<>(all.size());
        for (S3Bucket b : all) {
            CoverageVerdict verdict;
            if (b.region() == null) {
                verdict = CoverageVerdict.unknown(List.of(Objects.requireNonNull(b.regionUnknownReason())));
            } else if (unknownRegions.containsKey(b.region())) {
                verdict = CoverageVerdict.unknown(List.of(unknownRegions.get(b.region())));
            } else {
                verdict = CloudTrailS3CoverageEvaluator.evaluate(CloudTrailS3CoverageEvaluator.partition(b.region()),
                        b.name(), b.region(), trailsByRegion.get(b.region()));
            }
            out.add(new ReadCoverageObservation("AWS", account, "s3://" + b.name(), List.of("S3_BUCKET", "S3_OBJECT"),
                    CHANNEL, verdict, ID + "@" + VERSION));
        }
        log.debug("AWS read coverage: {} buckets, {} trails", all.size(), resolved.size());
        return out;
    }

    /** Reads a trail's status and selectors; a refusal or an error leaves them unknown with the reason. */
    private TrailState resolve(TrailState t) {
        Boolean logging = null;
        JsonNode selectors = null;
        String reason = null;
        try {
            logging = cloudTrail.getTrailStatus(t.arn(), t.homeRegion()).path("IsLogging").asBoolean(false);
            selectors = cloudTrail.getEventSelectors(t.arn(), t.homeRegion());
        } catch (MissingPermissionException e) {
            reason = e.reason();
        } catch (CloudTrailApiException | CoverageTransportException e) {
            rethrowIfInterrupted(e);
            reason = "trail " + t.arn() + ": " + e.getMessage();
        }
        return new TrailState(t.arn(), t.homeRegion(), t.multiRegion(), logging, selectors, reason);
    }

    /** An interrupted run is not a per-resource failure: the exception ends the collector. */
    private static void rethrowIfInterrupted(RuntimeException e) {
        if (Thread.currentThread().isInterrupted()) {
            throw e;
        }
    }
}
