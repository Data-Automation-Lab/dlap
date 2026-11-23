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

package com.dlap.autodelete.providers.gcp;

import com.dlap.autodelete.nativerule.BucketVersioning;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.nativerule.RuleDocuments;
import com.dlap.autodelete.nativerule.TestEndpoints;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.BaseServiceException;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo.LifecycleRule;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleAction;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleCondition;
import com.google.cloud.storage.BucketInfo.LifecycleRule.SetStorageClassLifecycleAction;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Applies GCS lifecycle rules: reads the bucket's rules, adds DLAP's rules unless an equal rule is
 * already there, and writes the list back with a metageneration precondition, so a concurrent
 * change by someone else fails the write instead of being lost. DLAP's rules are recognised by their
 * normalized fields, never by SDK object equality (RET-06, Q-682). Least privilege:
 * {@code storage.buckets.get} (which also answers the versioning check, Q-656), {@code storage.buckets.update}.
 */
@Component
public class GcsLifecycleRuleApplier implements NativeRuleApplier {

    private static final Logger LOG = LoggerFactory.getLogger(GcsLifecycleRuleApplier.class);

    /**
     * The bucket read-modify-write, so tests need no SDK.
     */
    @FunctionalInterface
    public interface BucketLifecycle {

        /**
         * Reads the bucket's rules, passes them to {@code merge} and writes the result.
         *
         * @param bucket the bucket
         * @param merge  from the current rules to the new rules
         */
        void update(String bucket, UnaryOperator<List<LifecycleRule>> merge);

        /**
         * Reads whether the bucket has object versioning turned on (RET-06, Q-656).
         *
         * @param bucket the bucket
         * @return true when versioning is on
         * @throws UnsupportedOperationException when this access cannot tell (the answer is then unknown)
         */
        default boolean versioningEnabled(String bucket) {
            throw new UnsupportedOperationException("this bucket access does not read versioning");
        }
    }

    private final BucketLifecycle buckets;

    /** The production applier: application default credentials. */
    public GcsLifecycleRuleApplier() {
        this(TestEndpoints.none());
    }

    /**
     * The applier Spring builds: application default credentials against Google Cloud, or, when
     * {@code dlap.autodelete.test-endpoint.gcs} is set (RET-08, Q-713), that emulator without credentials, logged
     * as a WARN.
     *
     * @param endpoints the test-only endpoints, empty in production
     */
    @Autowired
    public GcsLifecycleRuleApplier(TestEndpoints endpoints) {
        this(new SdkBuckets(storageFor(endpoints)));
    }

    /**
     * An applier with its own bucket access (tests).
     *
     * @param buckets the bucket access
     */
    public GcsLifecycleRuleApplier(BucketLifecycle buckets) {
        this.buckets = buckets;
    }

    @Override
    public NativeRulePlatform platform() {
        return NativeRulePlatform.GCS_LIFECYCLE;
    }

    @Override
    public void apply(NativeRule rule) {
        JsonNode doc = RuleDocuments.read(rule.document());
        String bucket = doc.path("bucket").asText();
        List<LifecycleRule> ours = new ArrayList<>();
        for (JsonNode r : doc.path("lifecycle").path("rule")) {
            ours.add(toSdk(r));
        }
        try {
            buckets.update(bucket, current -> merge(current, ours));
        } catch (BaseServiceException e) {
            throw new NativeRuleException("GCS refused the lifecycle rules on " + bucket + ": HTTP " + e.getCode()
                    + " " + e.getReason(), e);
        } catch (RuntimeException e) {
            if (e instanceof NativeRuleException n) {
                throw n;
            }
            throw new NativeRuleException("GCS could not be reached for bucket " + bucket + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public boolean remove(NativeRule rule) {
        JsonNode doc = RuleDocuments.read(rule.document());
        String bucket = doc.path("bucket").asText();
        List<LifecycleRule> ours = new ArrayList<>();
        for (JsonNode r : doc.path("lifecycle").path("rule")) {
            ours.add(toSdk(r));
        }
        AtomicBoolean allFound = new AtomicBoolean(false);
        try {
            buckets.update(bucket, current -> {
                Removal removal = without(current, ours);
                allFound.set(removal.allFound());
                return removal.kept();
            });
        } catch (BaseServiceException e) {
            throw new NativeRuleException("GCS refused removing the lifecycle rules on " + bucket + ": HTTP "
                    + e.getCode() + " " + e.getReason(), e);
        } catch (RuntimeException e) {
            if (e instanceof NativeRuleException n) {
                throw n;
            }
            throw new NativeRuleException("GCS could not be reached for bucket " + bucket + ": "
                    + e.getClass().getSimpleName(), e);
        }
        if (!allFound.get()) {
            LOG.warn("Removing DLAP's lifecycle rules from bucket {}: not every rule of the stored document was "
                    + "found on the bucket (Q-682)", bucket);
        }
        return allFound.get();
    }

    @Override
    public BucketVersioning versioning(NativeRule rule) {
        String bucket = RuleDocuments.read(rule.document()).path("bucket").asText();
        try {
            return buckets.versioningEnabled(bucket) ? BucketVersioning.ENABLED : BucketVersioning.UNVERSIONED;
        } catch (RuntimeException e) {
            LOG.warn("Could not read the versioning of bucket {}: {}", bucket, e.getClass().getSimpleName());
            return BucketVersioning.UNKNOWN;
        }
    }

    /**
     * What a removal keeps, and whether it found every rule it was asked to remove.
     *
     * @param kept     the rules that stay
     * @param allFound true when every one of DLAP's rules was found
     */
    record Removal(List<LifecycleRule> kept, boolean allFound) {
    }

    /**
     * Keeps every current rule except those that match one of ours on their normalized fields
     * ({@link #key}), not on SDK object equality (Q-682): GCS may return a condition in another form
     * than the one DLAP built, and an equality miss would leave the Delete rule in force while the
     * restore reported it removed.
     *
     * @param current the bucket's rules
     * @param ours    DLAP's rules
     * @return the remaining list, and whether each of ours was found
     */
    static Removal without(List<LifecycleRule> current, List<LifecycleRule> ours) {
        Set<RuleKey> wanted = new LinkedHashSet<>();
        for (LifecycleRule r : ours) {
            wanted.add(key(r));
        }
        Set<RuleKey> found = new LinkedHashSet<>();
        List<LifecycleRule> kept = new ArrayList<>();
        for (LifecycleRule r : current == null ? List.<LifecycleRule>of() : current) {
            RuleKey k = key(r);
            if (wanted.contains(k)) {
                found.add(k);
            } else {
                kept.add(r);
            }
        }
        return new Removal(kept, !wanted.isEmpty() && found.containsAll(wanted));
    }

    /**
     * Keeps every current rule and appends each of ours that is not already present (compared on the
     * normalized fields, {@link #key}).
     *
     * @param current the bucket's rules
     * @param ours    DLAP's rules
     * @return the merged list
     */
    static List<LifecycleRule> merge(List<LifecycleRule> current, List<LifecycleRule> ours) {
        List<LifecycleRule> merged = new ArrayList<>(current == null ? List.of() : current);
        Set<RuleKey> present = new LinkedHashSet<>();
        for (LifecycleRule r : merged) {
            present.add(key(r));
        }
        for (LifecycleRule r : ours) {
            if (present.add(key(r))) {
                merged.add(r);
            }
        }
        return merged;
    }

    /**
     * The normalized fields a GCS lifecycle rule is matched on (Q-682): action type, storage class,
     * age, prefixes, {@code createdBefore}, and the remaining condition fields, each in one canonical
     * form (dates as calendar dates, lists sorted, an absent list empty, the action type in lower case).
     *
     * @param actionType              {@code delete}, {@code setstorageclass}, ...
     * @param storageClass            the target class of a SetStorageClass rule, or null
     * @param age                     days since creation, or null
     * @param createdBefore           the date, or null
     * @param prefixes                {@code matchesPrefix}, sorted
     * @param suffixes                {@code matchesSuffix}, sorted
     * @param isLive                  the live condition, or null
     * @param daysSinceNoncurrentTime days since the version became noncurrent, or null
     * @param numberOfNewerVersions   newer versions, or null
     * @param noncurrentTimeBefore    the date, or null
     * @param customTimeBefore        the date, or null
     * @param daysSinceCustomTime     days since the custom time, or null
     * @param matchesStorageClass     storage classes, sorted
     */
    record RuleKey(String actionType, String storageClass, Integer age, LocalDate createdBefore,
            List<String> prefixes, List<String> suffixes, Boolean isLive, Integer daysSinceNoncurrentTime,
            Integer numberOfNewerVersions, LocalDate noncurrentTimeBefore, LocalDate customTimeBefore,
            Integer daysSinceCustomTime, List<String> matchesStorageClass) {
    }

    /**
     * Normalizes one rule.
     *
     * @param r the SDK rule
     * @return its key
     */
    static RuleKey key(LifecycleRule r) {
        LifecycleAction action = r.getAction();
        String type = action == null || action.getActionType() == null ? ""
                : action.getActionType().toLowerCase(Locale.ROOT);
        String storageClass = action instanceof SetStorageClassLifecycleAction set && set.getStorageClass() != null
                ? set.getStorageClass().name() : null;
        LifecycleCondition c = r.getCondition();
        if (c == null) {
            return new RuleKey(type, storageClass, null, null, List.of(), List.of(), null, null, null, null, null,
                    null, List.of());
        }
        return new RuleKey(type, storageClass, c.getAge(), date(c.getCreatedBeforeOffsetDateTime()),
                sorted(c.getMatchesPrefix()), sorted(c.getMatchesSuffix()), c.getIsLive(),
                c.getDaysSinceNoncurrentTime(), c.getNumberOfNewerVersions(),
                date(c.getNoncurrentTimeBeforeOffsetDateTime()), date(c.getCustomTimeBeforeOffsetDateTime()),
                c.getDaysSinceCustomTime(),
                c.getMatchesStorageClass() == null ? List.of()
                        : c.getMatchesStorageClass().stream().map(String::valueOf).sorted().toList());
    }

    private static LocalDate date(OffsetDateTime t) {
        return t == null ? null : t.toLocalDate();
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).sorted().toList();
    }

    /**
     * Builds an SDK rule from one document rule (the JSON API member names written by
     * {@link GcsLifecycleRuleGenerator}).
     *
     * @param r the rule member
     * @return the SDK rule
     */
    static LifecycleRule toSdk(JsonNode r) {
        if (!"Delete".equals(r.path("action").path("type").asText())) {
            throw new IllegalArgumentException("only Delete rules are generated");
        }
        JsonNode c = r.path("condition");
        LifecycleCondition.Builder cond = LifecycleCondition.newBuilder();
        if (c.has("age")) {
            cond.setAge(c.path("age").asInt());
        }
        if (c.has("createdBefore")) {
            cond.setCreatedBeforeOffsetDateTime(LocalDate.parse(c.path("createdBefore").asText())
                    .atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (c.has("isLive")) {
            cond.setIsLive(c.path("isLive").asBoolean());
        }
        if (c.has("daysSinceNoncurrentTime")) {
            cond.setDaysSinceNoncurrentTime(c.path("daysSinceNoncurrentTime").asInt());
        }
        return new LifecycleRule(LifecycleAction.newDeleteAction(), cond.build());
    }

    /** The production bucket access: application default credentials. */
    /**
     * Where the storage client comes from: Google Cloud with application default credentials, or the test endpoint
     * (fake-gcs-server) without credentials.
     *
     * @param endpoints the test-only endpoints
     * @return a supplier of the client
     */
    static Supplier<Storage> storageFor(TestEndpoints endpoints) {
        if (endpoints.gcsHost().isEmpty()) {
            return () -> StorageOptions.getDefaultInstance().getService();
        }
        String host = endpoints.gcsHost().get();
        LOG.warn(TestEndpoints.warning(NativeRulePlatform.GCS_LIFECYCLE, host));
        return () -> testEndpointOptions(host).getService();
    }

    /**
     * The options of the test endpoint (RET-08, Q-713): the emulator's host, a fixed project, no credentials.
     *
     * @param host the emulator
     * @return the options
     */
    static StorageOptions testEndpointOptions(String host) {
        return StorageOptions.newBuilder().setHost(host).setProjectId("dlap-test-endpoint")
                .setCredentials(NoCredentials.getInstance()).build();
    }

    static final class SdkBuckets implements BucketLifecycle {

        private final Supplier<Storage> storage;

        SdkBuckets(Supplier<Storage> storage) {
            this.storage = storage;
        }

        @Override
        public void update(String bucketName, UnaryOperator<List<LifecycleRule>> merge) {
            Storage storage = this.storage.get();
            Bucket bucket = storage.get(bucketName);
            if (bucket == null) {
                throw new NativeRuleException("GCS bucket " + bucketName + " does not exist", null);
            }
            List<LifecycleRule> current = bucket.getLifecycleRules() == null ? List.of()
                    : new ArrayList<>(bucket.getLifecycleRules());
            List<LifecycleRule> merged = merge.apply(current);
            // An empty list must clear the rules, which setLifecycleRules(empty) does not do.
            Bucket.Builder updated = merged.isEmpty() ? bucket.toBuilder().deleteLifecycleRules()
                    : bucket.toBuilder().setLifecycleRules(merged);
            storage.update(updated.build(), Storage.BucketTargetOption.metagenerationMatch());
        }

        @Override
        public boolean versioningEnabled(String bucketName) {
            Bucket bucket = storage.get().get(bucketName);
            if (bucket == null) {
                throw new NativeRuleException("GCS bucket " + bucketName + " does not exist", null);
            }
            return Boolean.TRUE.equals(bucket.versioningEnabled());
        }
    }
}
