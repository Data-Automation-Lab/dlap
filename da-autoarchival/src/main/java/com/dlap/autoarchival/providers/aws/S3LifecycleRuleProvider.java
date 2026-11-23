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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dlap.autoarchival.config.TestEndpointProperties;
import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RuleException.Reason;
import com.dlap.autoarchival.rule.RuleScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Transition;
import software.amazon.awssdk.services.s3.model.TransitionStorageClass;

/**
 * S3 lifecycle transition rules.
 *
 * <p>{@code PutBucketLifecycleConfiguration} replaces the bucket's whole configuration, so
 * {@link #apply} reads the rules first, keeps every one of them, adds or replaces only the rule
 * with DLAP's id, writes, and reads the configuration back. A rule that went missing between the
 * read and the write (another writer) is reported as {@code VERIFY_FAILED}, never hidden.
 *
 * <p>Provider limits used by the planner: 1000 lifecycle rules per bucket; a transition to
 * {@code STANDARD_IA} or {@code ONEZONE_IA} needs an age of at least 30 days
 * ({@code TierResolver}). The client follows a bucket's region
 * ({@code crossRegionAccessEnabled}).
 */
@Component
public class S3LifecycleRuleProvider implements NativeRuleProvider {

    /** S3 allows at most this many lifecycle rules per bucket. */
    static final int MAX_RULES = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<S3Client> clientSupplier;
    private volatile S3Client client;

    /**
     * A provider on the default credentials chain (tests and callers without the Actor configuration).
     *
     * @param region    the default region ({@code aws.s3.region})
     * @param endpoint  an S3-compatible endpoint for tests and emulators; empty for AWS
     * @param accessKey static access key for the endpoint override; empty to use the default chain
     * @param secretKey static secret key for the endpoint override
     */
    public S3LifecycleRuleProvider(String region, String endpoint, String accessKey, String secretKey) {
        this(region, endpoint, accessKey, secretKey, true, ActorCredentials.defaultChain());
    }

    /**
     * Spring constructor: the client is built on first use, so the service starts without AWS
     * credentials. The endpoint is the test-only {@code dlap.autoarchival.test-endpoint.s3} (the deprecated
     * {@code aws.endpoint-override} still works). Every client signs with the Actor credentials (EXEC-01, Q-722).
     *
     * @param region      the default region ({@code aws.s3.region})
     * @param endpoint    an S3-compatible endpoint for tests and emulators; empty for AWS
     * @param accessKey   static access key for the endpoint override (deprecated); empty to use the default chain
     * @param secretKey   static secret key for the endpoint override (deprecated)
     * @param pathStyle   path-style requests against the endpoint
     * @param credentials the Actor credentials: the assumed role of {@code dlap.autoarchival.aws.credentials-ref},
     *                    else the default chain
     */
    @Autowired
    public S3LifecycleRuleProvider(@Value("${aws.s3.region:us-east-1}") String region,
            @Value(TestEndpointProperties.S3) String endpoint,
            @Value("${dlap.autoarchival.aws.endpoint-access-key:}") String accessKey,
            @Value("${dlap.autoarchival.aws.endpoint-secret-key:}") String secretKey,
            @Value(TestEndpointProperties.S3_PATH_STYLE) boolean pathStyle,
            ActorCredentials credentials) {
        this(() -> build(region, endpoint, accessKey, secretKey, pathStyle, credentials));
    }

    /**
     * Test constructor.
     *
     * @param clientSupplier supplies the S3 client
     */
    public S3LifecycleRuleProvider(Supplier<S3Client> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    /**
     * Builds an S3 client on the default credentials chain.
     *
     * @param region    the default region
     * @param endpoint  the emulator endpoint; empty for AWS
     * @param accessKey static access key for the emulator; empty for the default chain
     * @param secretKey static secret key for the emulator
     * @return the client
     */
    public static S3Client build(String region, String endpoint, String accessKey, String secretKey) {
        return build(region, endpoint, accessKey, secretKey, true, ActorCredentials.defaultChain());
    }

    /**
     * Builds an S3 client. With an endpoint it targets that S3-compatible emulator (path-style by default) and signs
     * with static keys when given (deprecated), else with the assumed role when one is configured, else with the
     * default chain or anonymously (an emulator does not check signatures). This is the test-only override (Q-713):
     * off by default and refused under the prod profile by {@code EmulatorEndpointGuard}. Without an endpoint it
     * follows the bucket's region and signs with the Actor credentials.
     *
     * @param region      the default region
     * @param endpoint    the emulator endpoint; empty for AWS
     * @param accessKey   static access key for the emulator; empty for the default chain
     * @param secretKey   static secret key for the emulator
     * @param pathStyle   path-style requests against the endpoint
     * @param credentials the Actor credentials
     * @return the client
     */
    public static S3Client build(String region, String endpoint, String accessKey, String secretKey,
            boolean pathStyle, ActorCredentials credentials) {
        S3ClientBuilder b = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            b.endpointOverride(URI.create(endpoint)).forcePathStyle(pathStyle);
            if (accessKey != null && !accessKey.isBlank()) {
                b.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
            } else if (credentials.assumesRole()) {
                b.credentialsProvider(credentials.provider());
            } else {
                b.credentialsProvider(AwsCredentialsProviderChain.builder()
                        .credentialsProviders(DefaultCredentialsProvider.create(), AnonymousCredentialsProvider.create())
                        .build());
            }
        } else {
            b.crossRegionAccessEnabled(true).credentialsProvider(credentials.provider());
        }
        return b.build();
    }

    private S3Client s3() {
        S3Client c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = clientSupplier.get();
                }
                c = client;
            }
        }
        return c;
    }

    @Override
    public String provider() {
        return "AWS";
    }

    @Override
    public boolean canApply() {
        return true;
    }

    @Override
    public Inventory inventory(RuleSpec spec) {
        List<LifecycleRule> rules = readRules(spec.scope().bucket());
        boolean present = rules.stream().anyMatch(r -> matches(r, spec));
        return new Inventory(rules.size(), MAX_RULES, present);
    }

    @Override
    public Listing list(RuleScope scope, int maxKeys) {
        try {
            ListObjectsV2Response r = s3().listObjectsV2(b -> b.bucket(scope.bucket()).prefix(scope.prefix())
                    .maxKeys(Math.min(maxKeys + 1, 1000)));
            List<ListingEntry> entries = new ArrayList<>();
            long bytes = 0;
            for (S3Object o : r.contents()) {
                if (entries.size() == maxKeys) {
                    break;
                }
                long size = o.size() == null ? 0L : o.size();
                bytes += size;
                entries.add(new ListingEntry(o.key(), size, o.storageClassAsString() == null ? "" : o.storageClassAsString()));
            }
            boolean truncated = r.contents().size() > maxKeys || Boolean.TRUE.equals(r.isTruncated());
            return new Listing(entries, truncated, maxKeys, bytes, true, "ListObjectsV2, first " + maxKeys + " keys");
        } catch (SdkException e) {
            throw new RuleException(Reason.PROVIDER_ERROR, "S3 listing failed: " + message(e), e);
        }
    }

    @Override
    public String render(RuleSpec spec) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ID", spec.ruleId());
        rule.put("Status", "Enabled");
        rule.put("Filter", Map.of("Prefix", spec.scope().prefix()));
        rule.put("Transitions", List.of(Map.of("Days", spec.afterDays(), "StorageClass", spec.nativeClass())));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("Rules", List.of(rule)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot render the S3 rule", e);
        }
    }

    @Override
    public ApplyResult apply(RuleSpec spec) {
        String bucket = spec.scope().bucket();
        List<LifecycleRule> before = readRules(bucket);
        if (before.stream().anyMatch(r -> matches(r, spec))) {
            return new ApplyResult(true, before.size(), before.size());
        }
        if (before.size() + 1 > MAX_RULES && before.stream().noneMatch(r -> spec.ruleId().equals(r.id()))) {
            throw new RuleException(Reason.RULE_LIMIT, "bucket '" + bucket + "' already holds " + before.size() + " rules");
        }
        List<LifecycleRule> next = new ArrayList<>();
        for (LifecycleRule r : before) {
            if (!spec.ruleId().equals(r.id())) {
                next.add(r);
            }
        }
        next.add(rule(spec));
        writeRules(bucket, next);
        List<LifecycleRule> after = readRules(bucket);
        if (after.stream().noneMatch(r -> matches(r, spec))) {
            throw new RuleException(Reason.VERIFY_FAILED, "the rule " + spec.ruleId() + " is not on bucket '"
                    + bucket + "' after the write");
        }
        for (LifecycleRule kept : next) {
            if (after.stream().noneMatch(a -> sameId(a, kept))) {
                throw new RuleException(Reason.VERIFY_FAILED, "rule '" + kept.id() + "' on bucket '" + bucket
                        + "' is missing after the write; another writer changed the configuration");
            }
        }
        return new ApplyResult(false, before.size(), after.size());
    }

    @Override
    public boolean remove(RuleSpec spec) {
        String bucket = spec.scope().bucket();
        List<LifecycleRule> before = readRules(bucket);
        List<LifecycleRule> next = new ArrayList<>();
        for (LifecycleRule r : before) {
            if (!spec.ruleId().equals(r.id())) {
                next.add(r);
            }
        }
        if (next.size() == before.size()) {
            return false;
        }
        try {
            if (next.isEmpty()) {
                s3().deleteBucketLifecycle(b -> b.bucket(bucket));
            } else {
                writeRules(bucket, next);
            }
        } catch (SdkException e) {
            throw new RuleException(Reason.PROVIDER_ERROR, "S3 lifecycle removal failed: " + message(e), e);
        }
        if (readRules(bucket).stream().anyMatch(r -> spec.ruleId().equals(r.id()))) {
            throw new RuleException(Reason.VERIFY_FAILED, "the rule " + spec.ruleId() + " is still on bucket '"
                    + bucket + "' after the removal");
        }
        return true;
    }

    private List<LifecycleRule> readRules(String bucket) {
        try {
            return new ArrayList<>(s3().getBucketLifecycleConfiguration(b -> b.bucket(bucket)).rules());
        } catch (S3Exception e) {
            if (e.statusCode() == 404 && "NoSuchLifecycleConfiguration".equals(
                    e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode())) {
                return new ArrayList<>();
            }
            throw new RuleException(Reason.PROVIDER_ERROR, "S3 lifecycle read failed: " + message(e), e);
        } catch (SdkException e) {
            throw new RuleException(Reason.PROVIDER_ERROR, "S3 lifecycle read failed: " + message(e), e);
        }
    }

    private void writeRules(String bucket, List<LifecycleRule> rules) {
        try {
            s3().putBucketLifecycleConfiguration(b -> b.bucket(bucket)
                    .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(rules).build()));
        } catch (SdkException e) {
            throw new RuleException(Reason.PROVIDER_ERROR, "S3 lifecycle write failed: " + message(e), e);
        }
    }

    private static LifecycleRule rule(RuleSpec spec) {
        return LifecycleRule.builder()
                .id(spec.ruleId())
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix(spec.scope().prefix()).build())
                .transitions(Transition.builder().days(spec.afterDays())
                        .storageClass(TransitionStorageClass.fromValue(spec.nativeClass())).build())
                .build();
    }

    private static boolean sameId(LifecycleRule a, LifecycleRule b) {
        return a.id() == null ? b.id() == null : a.id().equals(b.id());
    }

    /** Whether the stored rule is the rule the spec asks for, field by field. */
    static boolean matches(LifecycleRule r, RuleSpec spec) {
        if (!spec.ruleId().equals(r.id()) || r.status() != ExpirationStatus.ENABLED || !r.hasTransitions()
                || r.transitions().size() != 1) {
            return false;
        }
        Transition t = r.transitions().get(0);
        String prefix = r.filter() != null && r.filter().prefix() != null ? r.filter().prefix()
                : r.prefix() == null ? "" : r.prefix();
        return t.storageClassAsString().equals(spec.nativeClass()) && t.days() != null
                && t.days() == spec.afterDays() && prefix.equals(spec.scope().prefix());
    }

    private static String message(SdkException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.substring(0, Math.min(m.length(), 300));
    }
}
