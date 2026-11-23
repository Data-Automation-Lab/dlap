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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.NativeRuleProvider.RuleSpec;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RuleScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo.LifecycleRule;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleAction;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleCondition;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageClass;
import com.google.cloud.storage.StorageException;

/**
 * The GCS rule provider against a stateful stand-in for one bucket. fake-gcs-server does not keep a
 * bucket's lifecycle rules (checked with PATCH {@code b/{bucket}}: the body comes back without
 * {@code lifecycle}), so the real-service behaviour is covered by the live check in the guide, not
 * by an emulator.
 */
class GcsLifecycleRuleProviderTest {

    private final AtomicReference<List<LifecycleRule>> stored = new AtomicReference<>(new ArrayList<>());
    private final AtomicInteger metagenerationFailures = new AtomicInteger();
    private final AtomicInteger updates = new AtomicInteger();
    private Storage storage;
    private GcsLifecycleRuleProvider provider;

    private static LifecycleRule rule(StorageClass cls, int age, String prefix) {
        LifecycleCondition.Builder c = LifecycleCondition.newBuilder().setAge(age);
        if (prefix != null) {
            c.setMatchesPrefix(List.of(prefix));
        }
        return new LifecycleRule(LifecycleAction.newSetStorageClassAction(cls), c.build());
    }

    private static RuleSpec spec(String cls, String prefix, int days) {
        return new RuleSpec("dlap-archive-0123456789ab", new RuleScope("GCP", "", "pilot", prefix, false), cls, days);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void init() {
        storage = mock(Storage.class);
        Bucket bucket = mock(Bucket.class);
        when(storage.get("pilot")).thenAnswer(inv -> bucket);
        when(bucket.getName()).thenReturn("pilot");
        when(bucket.getLifecycleRules()).thenAnswer(inv -> stored.get());
        Bucket.Builder builder = mock(Bucket.Builder.class);
        AtomicReference<List<LifecycleRule>> pending = new AtomicReference<>();
        AtomicReference<Boolean> delete = new AtomicReference<>(false);
        when(bucket.toBuilder()).thenReturn(builder);
        when(builder.setLifecycleRules(any())).thenAnswer(inv -> {
            pending.set(new ArrayList<>(inv.<List<LifecycleRule>>getArgument(0)));
            delete.set(false);
            return builder;
        });
        when(builder.deleteLifecycleRules()).thenAnswer(inv -> {
            pending.set(new ArrayList<>());
            delete.set(true);
            return builder;
        });
        Bucket built = mock(Bucket.class);
        when(builder.build()).thenReturn(built);
        when(built.update(any(Storage.BucketTargetOption[].class))).thenAnswer(inv -> {
            updates.incrementAndGet();
            if (metagenerationFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new StorageException(412, "metageneration mismatch");
            }
            stored.set(pending.get());
            return built;
        });
        provider = new GcsLifecycleRuleProvider(false, () -> storage);
    }

    @Test
    void rendersTheSetStorageClassRuleInGcloudShape() throws Exception {
        JsonNode rule = new ObjectMapper().readTree(provider.render(spec("COLDLINE", "2019/", 0))).path("rule").get(0);
        assertThat(rule.path("action").path("type").asText()).isEqualTo("SetStorageClass");
        assertThat(rule.path("action").path("storageClass").asText()).isEqualTo("COLDLINE");
        assertThat(rule.path("condition").path("age").asInt()).isZero();
        assertThat(rule.path("condition").path("matchesPrefix").get(0).asText()).isEqualTo("2019/");
        JsonNode whole = new ObjectMapper().readTree(provider.render(spec("ARCHIVE", "", 90))).path("rule").get(0);
        assertThat(whole.path("condition").has("matchesPrefix")).isFalse();
        assertThat(whole.path("condition").path("age").asInt()).isEqualTo(90);
    }

    @Test
    void applyKeepsEveryExistingRuleAndAddsOurs() {
        LifecycleRule delete = new LifecycleRule(LifecycleAction.newDeleteAction(),
                LifecycleCondition.newBuilder().setAge(3650).build());
        LifecycleRule other = rule(StorageClass.NEARLINE, 30, "logs/");
        stored.set(new ArrayList<>(List.of(delete, other)));

        NativeRuleProvider.ApplyResult r = provider.apply(spec("COLDLINE", "2019/", 0));

        assertThat(r.alreadyPresent()).isFalse();
        assertThat(r.rulesBefore()).isEqualTo(2);
        assertThat(r.rulesAfter()).isEqualTo(3);
        assertThat(stored.get()).hasSize(3).contains(delete, other);
        assertThat(stored.get()).anyMatch(x -> GcsLifecycleRuleProvider.matches(x, spec("COLDLINE", "2019/", 0)));
    }

    @Test
    void aRepeatedApplyWritesNothing() {
        provider.apply(spec("COLDLINE", "2019/", 0));
        int before = updates.get();

        NativeRuleProvider.ApplyResult again = provider.apply(spec("COLDLINE", "2019/", 0));

        assertThat(again.alreadyPresent()).isTrue();
        assertThat(updates.get()).isEqualTo(before);
        assertThat(stored.get()).hasSize(1);
    }

    @Test
    void aRuleOnAnotherPrefixOrClassIsADifferentRule() {
        provider.apply(spec("COLDLINE", "2019/", 0));
        provider.apply(spec("COLDLINE", "2020/", 0));
        provider.apply(spec("ARCHIVE", "2019/", 0));
        provider.apply(spec("COLDLINE", "2019/", 30));

        assertThat(stored.get()).hasSize(4);
    }

    @Test
    void aConcurrentChangeToTheBucketIsRetriedOnceOnTheFreshBucket() {
        metagenerationFailures.set(1);

        NativeRuleProvider.ApplyResult r = provider.apply(spec("COLDLINE", "2019/", 0));

        assertThat(r.rulesAfter()).isEqualTo(1);
        assertThat(updates.get()).isEqualTo(2);
    }

    @Test
    void aSecondPreconditionFailureIsReportedNotHidden() {
        metagenerationFailures.set(5);

        assertThatThrownBy(() -> provider.apply(spec("COLDLINE", "2019/", 0))).isInstanceOfSatisfying(RuleException.class,
                e -> assertThat(e.reason()).isEqualTo(RuleException.Reason.PROVIDER_ERROR));
        assertThat(stored.get()).isEmpty();
    }

    @Test
    void aWriteThatDoesNotShowOnReadBackIsAVerifyFailure() {
        Bucket built = storage.get("pilot").toBuilder().build();
        when(built.update(any(Storage.BucketTargetOption[].class))).thenReturn(built); // accepted, never stored

        assertThatThrownBy(() -> provider.apply(spec("COLDLINE", "2019/", 0))).isInstanceOfSatisfying(RuleException.class,
                e -> assertThat(e.reason()).isEqualTo(RuleException.Reason.VERIFY_FAILED));
    }

    @Test
    void refusesToGoPastTheHundredRuleLimit() {
        List<LifecycleRule> full = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            full.add(rule(StorageClass.NEARLINE, i, "p" + i + "/"));
        }
        stored.set(full);

        assertThatThrownBy(() -> provider.apply(spec("COLDLINE", "2019/", 0))).isInstanceOfSatisfying(RuleException.class,
                e -> assertThat(e.reason()).isEqualTo(RuleException.Reason.RULE_LIMIT));
        assertThat(updates.get()).isZero();
    }

    @Test
    void removeTakesOutOnlyOurRuleAndClearsTheListWhenItWasTheLast() {
        LifecycleRule other = rule(StorageClass.NEARLINE, 30, "logs/");
        stored.set(new ArrayList<>(List.of(other)));
        provider.apply(spec("COLDLINE", "2019/", 0));

        assertThat(provider.remove(spec("COLDLINE", "2019/", 0))).isTrue();
        assertThat(stored.get()).containsExactly(other);
        assertThat(provider.remove(spec("COLDLINE", "2019/", 0))).isFalse();

        stored.set(new ArrayList<>());
        provider.apply(spec("COLDLINE", "2019/", 0));
        assertThat(provider.remove(spec("COLDLINE", "2019/", 0))).isTrue();
        assertThat(stored.get()).isEmpty();
    }

    @Test
    void inventoryCountsRulesAndRecognisesOurs() {
        stored.set(new ArrayList<>(List.of(rule(StorageClass.NEARLINE, 30, "logs/"))));
        assertThat(provider.inventory(spec("COLDLINE", "2019/", 0)).totalRules()).isEqualTo(1);
        assertThat(provider.inventory(spec("COLDLINE", "2019/", 0)).specPresent()).isFalse();
        provider.apply(spec("COLDLINE", "2019/", 0));
        assertThat(provider.inventory(spec("COLDLINE", "2019/", 0)).specPresent()).isTrue();
        assertThat(provider.inventory(spec("COLDLINE", "2019/", 0)).providerMax()).isEqualTo(100);
    }

    @Test
    void aRuleWithMoreConditionsThanOursIsNotTakenForOurs() {
        LifecycleRule narrower = new LifecycleRule(LifecycleAction.newSetStorageClassAction(StorageClass.COLDLINE),
                LifecycleCondition.newBuilder().setAge(0).setMatchesPrefix(List.of("2019/")).setIsLive(true).build());
        stored.set(new ArrayList<>(List.of(narrower)));

        assertThat(provider.inventory(spec("COLDLINE", "2019/", 0)).specPresent()).isFalse();
    }

    @Test
    void aMissingBucketIsAProviderError() {
        when(storage.get("pilot")).thenReturn(null);

        assertThatThrownBy(() -> provider.inventory(spec("COLDLINE", "", 0))).isInstanceOfSatisfying(RuleException.class,
                e -> assertThat(e.reason()).isEqualTo(RuleException.Reason.PROVIDER_ERROR));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListingIsBoundedAndSaysWhenMoreMatch() {
        List<Blob> blobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Blob b = mock(Blob.class);
            when(b.getName()).thenReturn("2019/o" + i);
            when(b.getSize()).thenReturn(100L);
            when(b.getStorageClass()).thenReturn(StorageClass.STANDARD);
            blobs.add(b);
        }
        Page<Blob> page = mock(Page.class);
        when(page.getValues()).thenReturn(blobs);
        when(page.hasNextPage()).thenReturn(false);
        when(storage.list(eq("pilot"), any(Storage.BlobListOption[].class))).thenReturn(page);

        var listing = provider.list(new RuleScope("GCP", "", "pilot", "2019/", false), 3);

        assertThat(listing.entries()).hasSize(3);
        assertThat(listing.truncated()).isTrue();
        assertThat(listing.bytesListed()).isEqualTo(300L);
        assertThat(listing.entries().get(0).storageClass()).isEqualTo("STANDARD");
    }

    @Test
    void mockModeNeverTouchesGcs() {
        GcsLifecycleRuleProvider mocked = new GcsLifecycleRuleProvider(true, () -> {
            throw new AssertionError("the client must not be built in mock mode");
        });

        assertThat(mocked.isMock()).isTrue();
        assertThat(mocked.apply(spec("COLDLINE", "", 0)).rulesAfter()).isEqualTo(-1);
        assertThat(mocked.inventory(spec("COLDLINE", "", 0)).totalRules()).isEqualTo(-1);
        assertThat(mocked.list(new RuleScope("GCP", "", "pilot", "", false), 5).available()).isFalse();
        assertThat(mocked.remove(spec("COLDLINE", "", 0))).isFalse();
        verify(storage, never()).get(any(String.class));
    }
}
