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

package com.dlap.autodelete.providers.aws;

import com.dlap.autodelete.nativerule.BucketVersioning;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.nativerule.RuleDocuments;
import com.dlap.autodelete.nativerule.TestEndpoints;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortIncompleteMultipartUpload;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteBucketLifecycleRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoncurrentVersionExpiration;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;

/**
 * Applies an S3 lifecycle rule: reads the bucket's lifecycle configuration, keeps every rule except
 * one with the same ID, adds DLAP's rule and writes the configuration back.
 * {@code PutBucketLifecycleConfiguration} replaces the whole configuration, so a write without the
 * read would delete the bucket owner's own rules. {@link #remove} (a restore) takes DLAP's rule out the same way.
 * Least privilege: {@code s3:GetLifecycleConfiguration}, {@code s3:PutLifecycleConfiguration} (which also
 * covers {@code DeleteBucketLifecycle}), {@code s3:GetBucketLocation}, and {@code s3:GetBucketVersioning} for
 * the versioned-bucket check (RET-06, Q-656).
 */
@Component
public class S3LifecycleRuleApplier implements NativeRuleApplier {

    private static final Logger LOG = LoggerFactory.getLogger(S3LifecycleRuleApplier.class);

    /** S3 answers this error code when a bucket has no lifecycle configuration. */
    static final String NO_LIFECYCLE = "NoSuchLifecycleConfiguration";

    /** S3 allows at most this many lifecycle rules per bucket. */
    static final int MAX_RULES = 1000;

    private final S3ClientFactory clients;

    /** The production applier: default credentials chain, region from {@code GetBucketLocation}. */
    public S3LifecycleRuleApplier() {
        this(TestEndpoints.none());
    }

    /**
     * An applier on the default credentials chain, with the test-only endpoints.
     *
     * @param endpoints the test-only endpoints, empty in production
     */
    public S3LifecycleRuleApplier(TestEndpoints endpoints) {
        this(endpoints, ActorCredentials.defaultChain());
    }

    /**
     * The applier Spring builds: the production clients, or, when {@code dlap.autodelete.test-endpoint.s3} is set
     * (RET-08, Q-713), clients for that emulator, logged as a WARN. Every client signs with the Actor credentials
     * (EXEC-01, Q-722): the assumed role when {@code dlap.autodelete.aws.credentials-ref} is set, else the default
     * chain.
     *
     * @param endpoints   the test-only endpoints, empty in production
     * @param credentials the Actor credentials
     */
    @Autowired
    public S3LifecycleRuleApplier(TestEndpoints endpoints, ActorCredentials credentials) {
        this(clientsFor(endpoints, credentials));
    }

    /**
     * An applier with its own clients (tests).
     *
     * @param clients the client factory
     */
    public S3LifecycleRuleApplier(S3ClientFactory clients) {
        this.clients = clients;
    }

    @Override
    public NativeRulePlatform platform() {
        return NativeRulePlatform.S3_LIFECYCLE;
    }

    @Override
    public void apply(NativeRule rule) {
        JsonNode doc = RuleDocuments.read(rule.document());
        String bucket = doc.path("bucket").asText();
        List<LifecycleRule> ours = new ArrayList<>();
        for (JsonNode r : doc.path("rules")) {
            ours.add(toSdk(r));
        }
        try (S3Client s3 = clients.forBucket(bucket)) {
            List<LifecycleRule> merged = new ArrayList<>();
            for (LifecycleRule existing : existingRules(s3, bucket)) {
                if (ours.stream().noneMatch(o -> o.id().equals(existing.id()))) {
                    merged.add(existing);
                }
            }
            merged.addAll(ours);
            if (merged.size() > MAX_RULES) {
                throw new NativeRuleException("bucket " + bucket + " would have " + merged.size()
                        + " lifecycle rules; S3 allows " + MAX_RULES, null);
            }
            s3.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                    .bucket(bucket)
                    .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(merged).build())
                    .build());
        } catch (AwsServiceException e) {
            String code = e.awsErrorDetails() == null ? "unknown" : e.awsErrorDetails().errorCode();
            throw new NativeRuleException("S3 refused the lifecycle rule on " + bucket + ": " + code
                    + " (HTTP " + e.statusCode() + ")", e);
        } catch (SdkException e) {
            throw new NativeRuleException("S3 could not be reached for bucket " + bucket + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public boolean remove(NativeRule rule) {
        JsonNode doc = RuleDocuments.read(rule.document());
        String bucket = doc.path("bucket").asText();
        List<String> ids = new ArrayList<>();
        for (JsonNode r : doc.path("rules")) {
            ids.add(r.path("ID").asText());
        }
        try (S3Client s3 = clients.forBucket(bucket)) {
            List<LifecycleRule> existing = existingRules(s3, bucket);
            List<LifecycleRule> kept = existing.stream().filter(r -> !ids.contains(r.id())).toList();
            long found = existing.stream().map(LifecycleRule::id).filter(ids::contains).distinct().count();
            if (kept.size() == existing.size()) {
                return false;
            }
            if (kept.isEmpty()) {
                // PutBucketLifecycleConfiguration refuses an empty rule list; DeleteBucketLifecycle is the
                // same permission (s3:PutLifecycleConfiguration).
                s3.deleteBucketLifecycle(DeleteBucketLifecycleRequest.builder().bucket(bucket).build());
            } else {
                s3.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                        .bucket(bucket)
                        .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(kept).build())
                        .build());
            }
            return found == ids.stream().distinct().count();
        } catch (AwsServiceException e) {
            String code = e.awsErrorDetails() == null ? "unknown" : e.awsErrorDetails().errorCode();
            throw new NativeRuleException("S3 refused removing the lifecycle rule on " + bucket + ": " + code
                    + " (HTTP " + e.statusCode() + ")", e);
        } catch (SdkException e) {
            throw new NativeRuleException("S3 could not be reached for bucket " + bucket + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * {@code GetBucketVersioning}: a bucket whose versioning was never turned on answers no status
     * (Q-656). Needs {@code s3:GetBucketVersioning}.
     */
    @Override
    public BucketVersioning versioning(NativeRule rule) {
        String bucket = RuleDocuments.read(rule.document()).path("bucket").asText();
        try (S3Client s3 = clients.forBucket(bucket)) {
            BucketVersioningStatus status = s3.getBucketVersioning(GetBucketVersioningRequest.builder()
                    .bucket(bucket).build()).status();
            if (status == null) {
                return BucketVersioning.UNVERSIONED;
            }
            return switch (status) {
                case ENABLED -> BucketVersioning.ENABLED;
                case SUSPENDED -> BucketVersioning.SUSPENDED;
                default -> BucketVersioning.UNKNOWN;
            };
        } catch (RuntimeException e) {
            LOG.warn("Could not read the versioning of bucket {}: {}", bucket, e.getClass().getSimpleName());
            return BucketVersioning.UNKNOWN;
        }
    }

    private static List<LifecycleRule> existingRules(S3Client s3, String bucket) {
        try {
            List<LifecycleRule> rules = s3.getBucketLifecycleConfiguration(GetBucketLifecycleConfigurationRequest
                    .builder().bucket(bucket).build()).rules();
            return rules == null ? List.of() : rules;
        } catch (AwsServiceException e) {
            if (e.awsErrorDetails() != null && NO_LIFECYCLE.equals(e.awsErrorDetails().errorCode())) {
                return List.of();
            }
            throw e;
        }
    }

    /**
     * Builds the SDK rule from the document's rule (the API member names written by
     * {@link S3LifecycleRuleGenerator}).
     *
     * @param r the rule member
     * @return the SDK rule
     */
    static LifecycleRule toSdk(JsonNode r) {
        JsonNode expiration = r.path("Expiration");
        LifecycleExpiration.Builder exp = LifecycleExpiration.builder();
        if (expiration.has("Days")) {
            exp.days(expiration.path("Days").asInt());
        } else {
            // A rule applied before RET-06 (date-based); rebuilt only to be removed.
            exp.date(Instant.parse(expiration.path("Date").asText()));
        }
        return LifecycleRule.builder()
                .id(r.path("ID").asText())
                .filter(LifecycleRuleFilter.builder().prefix(r.path("Filter").path("Prefix").asText("")).build())
                .status(ExpirationStatus.fromValue(r.path("Status").asText()))
                .expiration(exp.build())
                .noncurrentVersionExpiration(NoncurrentVersionExpiration.builder()
                        .noncurrentDays(r.path("NoncurrentVersionExpiration").path("NoncurrentDays").asInt()).build())
                .abortIncompleteMultipartUpload(AbortIncompleteMultipartUpload.builder()
                        .daysAfterInitiation(r.path("AbortIncompleteMultipartUpload").path("DaysAfterInitiation").asInt())
                        .build())
                .build();
    }

    /**
     * The client factory for the settings: the bucket's regional client, or one client per call for the test
     * endpoint.
     *
     * @param endpoints the test-only endpoints
     * @return the factory
     */
    static S3ClientFactory clientsFor(TestEndpoints endpoints) {
        return clientsFor(endpoints, ActorCredentials.defaultChain());
    }

    /**
     * The client factory for the settings and the Actor credentials.
     *
     * @param endpoints   the test-only endpoints
     * @param credentials the Actor credentials
     * @return the factory
     */
    static S3ClientFactory clientsFor(TestEndpoints endpoints, ActorCredentials credentials) {
        if (endpoints.s3Endpoint().isEmpty()) {
            return bucket -> regionalClient(bucket, credentials.provider());
        }
        URI endpoint = endpoints.s3Endpoint().get();
        boolean pathStyle = endpoints.s3PathStyle();
        String shown = endpoint + (pathStyle ? " (path-style)" : "");
        LOG.warn(TestEndpoints.warning(NativeRulePlatform.S3_LIFECYCLE, shown));
        return bucket -> testEndpointClient(endpoint, pathStyle,
                credentials.assumesRole() ? credentials.provider() : defaultOrAnonymous());
    }

    /**
     * A client for the test endpoint (RET-08, Q-713): us-east-1, the default credentials when they resolve, else
     * anonymous (an emulator does not check signatures). Never used without the test endpoint.
     *
     * @param endpoint  the emulator
     * @param pathStyle path-style requests
     * @return the client
     */
    static S3Client testEndpointClient(URI endpoint, boolean pathStyle) {
        return testEndpointClient(endpoint, pathStyle, defaultOrAnonymous());
    }

    /**
     * A client for the test endpoint with the given credentials.
     *
     * @param endpoint    the emulator
     * @param pathStyle   path-style requests
     * @param credentials the credentials to sign with
     * @return the client
     */
    static S3Client testEndpointClient(URI endpoint, boolean pathStyle, AwsCredentialsProvider credentials) {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(endpoint)
                .forcePathStyle(pathStyle)
                .credentialsProvider(credentials)
                .build();
    }

    private static AwsCredentialsProvider defaultOrAnonymous() {
        return AwsCredentialsProviderChain.builder()
                .credentialsProviders(DefaultCredentialsProvider.create(), AnonymousCredentialsProvider.create())
                .build();
    }

    /** A client in the bucket's region (an empty location constraint is us-east-1, "EU" is eu-west-1). */
    static S3Client regionalClient(String bucket) {
        return regionalClient(bucket, DefaultCredentialsProvider.create());
    }

    /** A client in the bucket's region that signs with the given credentials. */
    static S3Client regionalClient(String bucket, AwsCredentialsProvider credentials) {
        String location;
        try (S3Client global = S3Client.builder().region(Region.US_EAST_1)
                .credentialsProvider(credentials).build()) {
            location = global.getBucketLocation(GetBucketLocationRequest.builder().bucket(bucket).build())
                    .locationConstraintAsString();
        }
        Region region = location == null || location.isBlank() ? Region.US_EAST_1
                : "EU".equals(location) ? Region.EU_WEST_1 : Region.of(location);
        return S3Client.builder().region(region).credentialsProvider(credentials).build();
    }
}
