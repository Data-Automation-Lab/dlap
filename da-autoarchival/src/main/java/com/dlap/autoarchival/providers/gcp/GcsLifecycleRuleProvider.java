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

package com.dlap.autoarchival.providers.gcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RuleException.Reason;
import com.dlap.autoarchival.rule.RuleScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo.LifecycleRule;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleAction;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleCondition;
import com.google.cloud.storage.BucketInfo.LifecycleRule.SetStorageClassLifecycleAction;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageClass;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;

/**
 * GCS bucket lifecycle rules with the {@code SetStorageClass} action.
 *
 * <p>A GCS rule has no id, so a rule is recognised by what it says: action
 * {@code SetStorageClass} to the class, condition {@code age} and {@code matchesPrefix}. DLAP's own
 * rules are tracked in {@code dlap_archive_rules}, not in the bucket. {@link #apply} reads the
 * bucket, appends the rule to the rules it already has, and updates with a metageneration
 * precondition, so a concurrent change to the bucket fails the update (and is retried once on the
 * fresh bucket) instead of silently overwriting it. The rules are read back and compared.
 *
 * <p>GCS allows at most 100 lifecycle rules per bucket, and a lifecycle action only moves objects
 * to a colder class. In mock mode ({@code dlap.autoarchival.gcs.mock-mode=true}, the harness and
 * CI) nothing is sent to GCS.
 */
@Component
public class GcsLifecycleRuleProvider implements NativeRuleProvider {

    /** GCS allows at most this many lifecycle rules per bucket. */
    static final int MAX_RULES = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean mock;
    private final Supplier<Storage> storageSupplier;
    private volatile Storage storage;

    /**
     * Spring constructor: the client is built on first use with Application Default Credentials,
     * or against an emulator host.
     *
     * @param mock     {@code dlap.autoarchival.gcs.mock-mode}
     * @param host     an emulator or alternative endpoint; empty for GCS
     * @param projectId the project for an emulator host; may be empty
     */
    @Autowired
    public GcsLifecycleRuleProvider(@Value("${dlap.autoarchival.gcs.mock-mode:false}") boolean mock,
            @Value(com.dlap.autoarchival.config.TestEndpointProperties.GCS) String host,
            @Value("${dlap.autoarchival.gcs.endpoint-project:dlap-emulator}") String projectId) {
        this(mock, () -> host == null || host.isBlank() ? StorageOptions.getDefaultInstance().getService()
                : StorageOptions.newBuilder().setHost(host).setProjectId(projectId)
                        .setCredentials(com.google.cloud.NoCredentials.getInstance()).build().getService());
    }

    /**
     * Test constructor.
     *
     * @param mock            whether calls are mocked
     * @param storageSupplier supplies the client
     */
    public GcsLifecycleRuleProvider(boolean mock, Supplier<Storage> storageSupplier) {
        this.mock = mock;
        this.storageSupplier = storageSupplier;
    }

    private Storage gcs() {
        Storage s = storage;
        if (s == null) {
            synchronized (this) {
                if (storage == null) {
                    storage = storageSupplier.get();
                }
                s = storage;
            }
        }
        return s;
    }

    @Override
    public String provider() {
        return "GCP";
    }

    @Override
    public boolean canApply() {
        return true;
    }

    @Override
    public boolean isMock() {
        return mock;
    }

    @Override
    public Inventory inventory(RuleSpec spec) {
        if (mock) {
            return new Inventory(-1, MAX_RULES, false);
        }
        List<? extends LifecycleRule> rules = rulesOf(bucket(spec.scope().bucket()));
        return new Inventory(rules.size(), MAX_RULES, rules.stream().anyMatch(r -> matches(r, spec)));
    }

    @Override
    public Listing list(RuleScope scope, int maxKeys) {
        if (mock) {
            return new Listing(List.of(), false, maxKeys, 0L, false, "mock mode: no GCS call was made");
        }
        try {
            var page = gcs().list(scope.bucket(), Storage.BlobListOption.prefix(scope.prefix()),
                    Storage.BlobListOption.pageSize(Math.min(maxKeys + 1, 1000)));
            List<ListingEntry> entries = new ArrayList<>();
            long bytes = 0;
            boolean more = false;
            for (Blob b : page.getValues()) {
                if (entries.size() == maxKeys) {
                    more = true;
                    break;
                }
                long size = b.getSize() == null ? 0L : b.getSize();
                bytes += size;
                entries.add(new ListingEntry(b.getName(), size,
                        b.getStorageClass() == null ? "" : b.getStorageClass().name()));
            }
            return new Listing(entries, more || page.hasNextPage(), maxKeys, bytes, true,
                    "objects.list, first " + maxKeys + " objects");
        } catch (StorageException e) {
            throw new RuleException(Reason.PROVIDER_ERROR, "GCS listing failed: " + message(e), e);
        }
    }

    @Override
    public String render(RuleSpec spec) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("age", spec.afterDays());
        if (!spec.scope().prefix().isEmpty()) {
            condition.put("matchesPrefix", List.of(spec.scope().prefix()));
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("action", Map.of("type", "SetStorageClass", "storageClass", spec.nativeClass()));
        rule.put("condition", condition);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("rule", List.of(rule)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot render the GCS rule", e);
        }
    }

    @Override
    public ApplyResult apply(RuleSpec spec) {
        if (mock) {
            return new ApplyResult(false, -1, -1);
        }
        for (int attempt = 1;; attempt++) {
            Bucket bucket = bucket(spec.scope().bucket());
            List<LifecycleRule> before = new ArrayList<>(rulesOf(bucket));
            if (before.stream().anyMatch(r -> matches(r, spec))) {
                return new ApplyResult(true, before.size(), before.size());
            }
            if (before.size() + 1 > MAX_RULES) {
                throw new RuleException(Reason.RULE_LIMIT, "bucket '" + bucket.getName() + "' already holds "
                        + before.size() + " lifecycle rules");
            }
            List<LifecycleRule> next = new ArrayList<>(before);
            next.add(rule(spec));
            try {
                bucket.toBuilder().setLifecycleRules(next).build().update(Storage.BucketTargetOption.metagenerationMatch());
            } catch (StorageException e) {
                if (e.getCode() == 412 && attempt < 2) {
                    continue;
                }
                throw new RuleException(Reason.PROVIDER_ERROR, "GCS lifecycle update failed: " + message(e), e);
            }
            List<? extends LifecycleRule> after = rulesOf(bucket(spec.scope().bucket()));
            if (after.stream().noneMatch(r -> matches(r, spec))) {
                throw new RuleException(Reason.VERIFY_FAILED, "the rule is not on bucket '" + bucket.getName()
                        + "' after the update");
            }
            for (LifecycleRule kept : before) {
                if (after.stream().noneMatch(a -> same(a, kept))) {
                    throw new RuleException(Reason.VERIFY_FAILED, "an existing rule on bucket '" + bucket.getName()
                            + "' is missing after the update; another writer changed the bucket");
                }
            }
            return new ApplyResult(false, before.size(), after.size());
        }
    }

    @Override
    public boolean remove(RuleSpec spec) {
        if (mock) {
            return false;
        }
        for (int attempt = 1;; attempt++) {
            Bucket bucket = bucket(spec.scope().bucket());
            List<LifecycleRule> before = new ArrayList<>(rulesOf(bucket));
            List<LifecycleRule> next = new ArrayList<>(before);
            boolean removed = next.removeIf(r -> matches(r, spec));
            if (!removed) {
                return false;
            }
            try {
                Bucket.Builder b = bucket.toBuilder();
                if (next.isEmpty()) {
                    b.deleteLifecycleRules();
                } else {
                    b.setLifecycleRules(next);
                }
                b.build().update(Storage.BucketTargetOption.metagenerationMatch());
            } catch (StorageException e) {
                if (e.getCode() == 412 && attempt < 2) {
                    continue;
                }
                throw new RuleException(Reason.PROVIDER_ERROR, "GCS lifecycle update failed: " + message(e), e);
            }
            if (rulesOf(bucket(spec.scope().bucket())).stream().anyMatch(r -> matches(r, spec))) {
                throw new RuleException(Reason.VERIFY_FAILED, "the rule is still on bucket '" + bucket.getName()
                        + "' after the removal");
            }
            return true;
        }
    }

    private Bucket bucket(String name) {
        try {
            Bucket b = gcs().get(name);
            if (b == null) {
                throw new RuleException(Reason.PROVIDER_ERROR, "bucket '" + name + "' was not found or is not visible");
            }
            return b;
        } catch (StorageException e) {
            throw new RuleException(Reason.PROVIDER_ERROR, "GCS bucket read failed: " + message(e), e);
        }
    }

    private static List<? extends LifecycleRule> rulesOf(Bucket bucket) {
        List<? extends LifecycleRule> rules = bucket.getLifecycleRules();
        return rules == null ? List.of() : rules;
    }

    private static LifecycleRule rule(RuleSpec spec) {
        LifecycleCondition.Builder c = LifecycleCondition.newBuilder().setAge(spec.afterDays());
        if (!spec.scope().prefix().isEmpty()) {
            c.setMatchesPrefix(List.of(spec.scope().prefix()));
        }
        return new LifecycleRule(LifecycleAction.newSetStorageClassAction(StorageClass.valueOf(spec.nativeClass())),
                c.build());
    }

    /** Whether the stored rule says what the spec says. */
    static boolean matches(LifecycleRule r, RuleSpec spec) {
        if (!(r.getAction() instanceof SetStorageClassLifecycleAction a)
                || !StorageClass.valueOf(spec.nativeClass()).equals(a.getStorageClass())) {
            return false;
        }
        LifecycleCondition c = r.getCondition();
        List<String> prefixes = c.getMatchesPrefix() == null ? List.of() : c.getMatchesPrefix();
        List<String> want = spec.scope().prefix().isEmpty() ? List.of() : List.of(spec.scope().prefix());
        return c.getAge() != null && c.getAge() == spec.afterDays() && prefixes.equals(want)
                && c.getCreatedBefore() == null && c.getIsLive() == null && c.getNumberOfNewerVersions() == null;
    }

    private static boolean same(LifecycleRule a, LifecycleRule b) {
        return Objects.equals(a.getAction(), b.getAction()) && Objects.equals(a.getCondition(), b.getCondition());
    }

    private static String message(StorageException e) {
        String m = e.getMessage();
        return "HTTP " + e.getCode() + (m == null ? "" : " " + m.substring(0, Math.min(m.length(), 300)));
    }
}
