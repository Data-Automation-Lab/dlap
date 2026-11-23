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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.nativerule.BucketVersioning;
import com.dlap.autodelete.nativerule.ContainerRuleAge;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.RuleDocuments;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.CanonicalAssetId;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.storage.model.Bucket;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.storage.BucketInfo.LifecycleRule;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleAction;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleCondition;
import com.google.cloud.storage.StorageException;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * GCS lifecycle and BigQuery expiration generation, checked against the Google API shapes: the
 * documents are parsed into the generated API model classes (google-api-services-storage and
 * -bigquery, built from Google's discovery documents) and must leave no unknown member.
 */
class GcpNativeRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:15:00Z");
    private static final UUID KEY = UUID.fromString("abcdabcd-0000-4000-8000-0000000000c1");

    private static NativeRule gcsRule() {
        CanonicalAssetId bucket = AssetIdNormalizer.normalize("gs://dlap-gcp-e2e").orElseThrow();
        return new GcsLifecycleRuleGenerator().generate(bucket, KEY, NOW);
    }

    private static NativeRule bqRule() {
        CanonicalAssetId table = AssetIdNormalizer.normalize("bq://dlap-gcp-e2e/sales/orders").orElseThrow();
        return new BigQueryTableExpirationGenerator(RetireProperties.defaults()).generate(table, KEY, NOW);
    }

    @Test
    void theGcsRulesParseIntoTheStorageApiModelWithNoUnknownMember() throws IOException {
        JsonNode doc = RuleDocuments.read(gcsRule().document());
        assertThat(doc.path("api").asText()).isEqualTo("storage.buckets.patch");
        Bucket.Lifecycle lifecycle = GsonFactory.getDefaultInstance()
                .fromString(RuleDocuments.write(doc.path("lifecycle")), Bucket.Lifecycle.class);
        assertThat(lifecycle.getUnknownKeys()).isEmpty();
        assertThat(lifecycle.getRule()).hasSize(2);
        for (Bucket.Lifecycle.Rule r : lifecycle.getRule()) {
            assertThat(r.getUnknownKeys()).isEmpty();
            assertThat(r.getAction().getUnknownKeys()).isEmpty();
            assertThat(r.getCondition().getUnknownKeys()).isEmpty();
            assertThat(r.getAction().getType()).isEqualTo("Delete");
        }
        assertThat(lifecycle.getRule().get(0).getCondition().getAge()).as("Q-677: age-based").isEqualTo(1);
        assertThat(lifecycle.getRule().get(0).getCondition().getCreatedBefore()).as("no date condition").isNull();
        assertThat(lifecycle.getRule().get(1).getCondition().getIsLive()).isFalse();
        assertThat(lifecycle.getRule().get(1).getCondition().getDaysSinceNoncurrentTime()).isEqualTo(1);
    }

    /** Q-677 (RET-06): the live Delete rule is age-based (age of at least 1), never createdBefore. */
    @Test
    void theGcsRulesAreAgeBasedAndBecomeTheExpectedSdkRules() {
        JsonNode rules = RuleDocuments.read(gcsRule().document()).path("lifecycle").path("rule");
        assertThat(GcsLifecycleRuleApplier.toSdk(rules.get(0))).isEqualTo(new LifecycleRule(
                LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setAge(1).build()));
        assertThat(ContainerRuleAge.violation(gcsRule())).isEmpty();
        assertThat(GcsLifecycleRuleApplier.toSdk(rules.get(1))).isEqualTo(new LifecycleRule(
                LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setIsLive(false)
                .setDaysSinceNoncurrentTime(1).build()));
        assertThat(gcsRule().effectiveAt()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
    }

    @Test
    void theGcsApplierKeepsTheOwnersRulesAndAddsItsOwnOnce() {
        LifecycleRule owners = new LifecycleRule(LifecycleAction.newSetStorageClassAction(
                com.google.cloud.storage.StorageClass.COLDLINE), LifecycleCondition.newBuilder().setAge(90).build());
        AtomicReference<List<LifecycleRule>> stored = new AtomicReference<>(List.of(owners));
        GcsLifecycleRuleApplier applier = new GcsLifecycleRuleApplier((bucket, merge) -> {
            assertThat(bucket).isEqualTo("dlap-gcp-e2e");
            stored.set(merge.apply(new ArrayList<>(stored.get())));
        });

        applier.apply(gcsRule());
        assertThat(stored.get()).hasSize(3).first().isEqualTo(owners);
        applier.apply(gcsRule());
        assertThat(stored.get()).as("a second apply adds nothing").hasSize(3);
    }

    /** Q-631: a restore of a rule in force removes DLAP's rules and keeps the owner's. */
    @Test
    void theGcsApplierRemovesOnlyItsOwnRulesAndRemovingTwiceChangesNothing() {
        LifecycleRule owners = new LifecycleRule(LifecycleAction.newSetStorageClassAction(
                com.google.cloud.storage.StorageClass.COLDLINE), LifecycleCondition.newBuilder().setAge(90).build());
        AtomicReference<List<LifecycleRule>> stored = new AtomicReference<>(List.of(owners));
        GcsLifecycleRuleApplier applier = new GcsLifecycleRuleApplier((bucket, merge) -> {
            assertThat(bucket).isEqualTo("dlap-gcp-e2e");
            stored.set(merge.apply(new ArrayList<>(stored.get())));
        });
        applier.apply(gcsRule());
        assertThat(stored.get()).hasSize(3);

        assertThat(applier.remove(gcsRule())).isTrue();
        assertThat(stored.get()).containsExactly(owners);
        assertThat(applier.remove(gcsRule())).as("Q-682: a rule that is gone is reported as not found").isFalse();
        assertThat(stored.get()).as("removing a rule that is gone changes nothing").containsExactly(owners);
    }

    /**
     * Q-682 (RET-06): GCS returns DLAP's rules in another form than the SDK objects DLAP built (a createdBefore at
     * another offset, prefixes in another order, an empty prefix list). SDK equality misses them; the normalized
     * match (action type, storage class, age, prefixes, createdBefore and the other condition fields) finds them.
     */
    @Test
    void removalMatchesOnNormalizedFieldsNotOnSdkEquality() {
        LifecycleRule ours = new LifecycleRule(LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder()
                .setCreatedBeforeOffsetDateTime(OffsetDateTime.of(2026, 11, 2, 0, 0, 0, 0, ZoneOffset.UTC))
                .setMatchesPrefix(List.of("a/", "b/")).build());
        LifecycleRule asReturned = new LifecycleRule(LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder()
                .setCreatedBeforeOffsetDateTime(OffsetDateTime.of(2026, 11, 2, 0, 0, 0, 0, ZoneOffset.ofHours(-8)))
                .setMatchesPrefix(List.of("b/", "a/")).build());
        LifecycleRule ownersLookalike = new LifecycleRule(LifecycleAction.newDeleteAction(), LifecycleCondition
                .newBuilder().setCreatedBeforeOffsetDateTime(OffsetDateTime.of(2026, 11, 3, 0, 0, 0, 0, ZoneOffset.UTC))
                .setMatchesPrefix(List.of("a/", "b/")).build());
        assertThat(asReturned).as("the SDK objects differ").isNotEqualTo(ours);

        GcsLifecycleRuleApplier.Removal removal = GcsLifecycleRuleApplier.without(
                List.of(ownersLookalike, asReturned), List.of(ours));

        assertThat(removal.allFound()).isTrue();
        assertThat(removal.kept()).as("the owner's rule one day later stays").containsExactly(ownersLookalike);
    }

    /** Q-682: the key keeps apart what must stay apart (an owner's Delete rule of another age, another action). */
    @Test
    void theNormalizedKeyKeepsDifferentRulesApart() {
        LifecycleRule ours = new LifecycleRule(LifecycleAction.newDeleteAction(),
                LifecycleCondition.newBuilder().setAge(1).build());
        LifecycleRule ownersOlder = new LifecycleRule(LifecycleAction.newDeleteAction(),
                LifecycleCondition.newBuilder().setAge(2).build());
        LifecycleRule ownersMove = new LifecycleRule(LifecycleAction.newSetStorageClassAction(
                com.google.cloud.storage.StorageClass.COLDLINE), LifecycleCondition.newBuilder().setAge(1).build());
        LifecycleRule ownersPrefixed = new LifecycleRule(LifecycleAction.newDeleteAction(),
                LifecycleCondition.newBuilder().setAge(1).setMatchesPrefix(List.of("tmp/")).build());

        GcsLifecycleRuleApplier.Removal removal = GcsLifecycleRuleApplier.without(
                List.of(ownersOlder, ownersMove, ownersPrefixed), List.of(ours));

        assertThat(removal.allFound()).isFalse();
        assertThat(removal.kept()).containsExactly(ownersOlder, ownersMove, ownersPrefixed);
        assertThat(GcsLifecycleRuleApplier.key(ours)).isEqualTo(GcsLifecycleRuleApplier.key(new LifecycleRule(
                LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setAge(1)
                        .setMatchesPrefix(List.of()).build())));
    }

    /** Q-682: when only some of DLAP's rules are found, what was found is removed and the answer is false. */
    @Test
    void aPartialMatchRemovesWhatWasFoundAndReportsNotFound() {
        List<LifecycleRule> ours = new ArrayList<>();
        for (JsonNode r : RuleDocuments.read(gcsRule().document()).path("lifecycle").path("rule")) {
            ours.add(GcsLifecycleRuleApplier.toSdk(r));
        }
        AtomicReference<List<LifecycleRule>> stored = new AtomicReference<>(List.of(ours.get(1)));
        GcsLifecycleRuleApplier applier = new GcsLifecycleRuleApplier((bucket, merge) ->
                stored.set(merge.apply(new ArrayList<>(stored.get()))));

        assertThat(applier.remove(gcsRule())).isFalse();
        assertThat(stored.get()).isEmpty();
    }

    /** Q-656 (RET-06): the GCS applier reads the bucket's versioning; an access that cannot tell is UNKNOWN. */
    @Test
    void theGcsApplierReadsTheBucketsVersioning() {
        GcsLifecycleRuleApplier.BucketLifecycle versioned = new GcsLifecycleRuleApplier.BucketLifecycle() {
            @Override
            public void update(String bucket, java.util.function.UnaryOperator<List<LifecycleRule>> merge) {
            }

            @Override
            public boolean versioningEnabled(String bucket) {
                assertThat(bucket).isEqualTo("dlap-gcp-e2e");
                return true;
            }
        };
        assertThat(new GcsLifecycleRuleApplier(versioned).versioning(gcsRule())).isEqualTo(BucketVersioning.ENABLED);
        assertThat(new GcsLifecycleRuleApplier((bucket, merge) -> { }).versioning(gcsRule()))
                .isEqualTo(BucketVersioning.UNKNOWN);
    }

    @Test
    void aGcsRefusalOfTheRemovalBecomesANativeRuleException() {
        GcsLifecycleRuleApplier applier = new GcsLifecycleRuleApplier((bucket, merge) -> {
            throw new StorageException(403, "forbidden");
        });
        assertThatThrownBy(() -> applier.remove(gcsRule())).isInstanceOf(NativeRuleException.class)
                .hasMessageContaining("403");
    }

    @Test
    void aBigQueryTableExpirationHasNoRuleToRemove() {
        BigQueryTableExpirationApplier.Tables tables = mock(BigQueryTableExpirationApplier.Tables.class);
        assertThatThrownBy(() -> new BigQueryTableExpirationApplier(tables).remove(bqRule()))
                .isInstanceOf(NativeRuleException.class);
    }

    @Test
    void aGcsRefusalBecomesANativeRuleException() {
        GcsLifecycleRuleApplier applier = new GcsLifecycleRuleApplier((bucket, merge) -> {
            throw new StorageException(412, "conditionNotMet");
        });
        assertThatThrownBy(() -> applier.apply(gcsRule())).isInstanceOf(NativeRuleException.class)
                .hasMessageContaining("412");
    }

    @Test
    void aSingleObjectAndADatasetHaveNoExactRule() {
        assertThat(new GcsLifecycleRuleGenerator().supports(AssetIdNormalizer.normalize("gs://dlap-gcp-e2e/o.csv").orElseThrow()))
                .isFalse();
        assertThat(new BigQueryTableExpirationGenerator(RetireProperties.defaults())
                .supports(AssetIdNormalizer.normalize("bq://dlap-gcp-e2e/sales").orElseThrow())).isFalse();
    }

    @Test
    void theBigQueryBodyParsesIntoTheTableResourceWithNoUnknownMember() throws IOException {
        NativeRule rule = bqRule();
        JsonNode doc = RuleDocuments.read(rule.document());
        assertThat(doc.path("api").asText()).isEqualTo("bigquery.tables.patch");
        assertThat(doc.path("projectId").asText()).isEqualTo("dlap-gcp-e2e");
        assertThat(doc.path("datasetId").asText()).isEqualTo("sales");
        assertThat(doc.path("tableId").asText()).isEqualTo("orders");
        Table body = GsonFactory.getDefaultInstance().fromString(RuleDocuments.write(doc.path("body")), Table.class);
        assertThat(body.getUnknownKeys()).isEmpty();
        assertThat(body.getExpirationTime()).isEqualTo(Instant.parse("2026-11-01T11:15:00Z").toEpochMilli());
        assertThat(rule.effectiveAt()).isEqualTo(Instant.parse("2026-11-01T11:15:00Z"));
    }

    @Test
    void theBigQueryApplierKeepsAnEarlierExpirationAndSetsItsOwnOtherwise() {
        long ours = Instant.parse("2026-11-01T11:15:00Z").toEpochMilli();
        BigQueryTableExpirationApplier.Tables tables = mock(BigQueryTableExpirationApplier.Tables.class);
        TableId id = TableId.of("dlap-gcp-e2e", "sales", "orders");

        when(tables.expiration(id)).thenReturn(Optional.of(ours - 1));
        new BigQueryTableExpirationApplier(tables).apply(bqRule());
        verify(tables, never()).setExpiration(any(), org.mockito.ArgumentMatchers.anyLong());

        when(tables.expiration(id)).thenReturn(Optional.empty());
        new BigQueryTableExpirationApplier(tables).apply(bqRule());
        verify(tables).setExpiration(id, ours);
    }

    @Test
    void aMissingTableIsANativeRuleException() {
        BigQueryTableExpirationApplier.Tables tables = mock(BigQueryTableExpirationApplier.Tables.class);
        when(tables.expiration(any())).thenThrow(new NativeRuleException("BigQuery table does not exist", null));
        assertThatThrownBy(() -> new BigQueryTableExpirationApplier(tables).apply(bqRule()))
                .isInstanceOf(NativeRuleException.class).hasMessageContaining("does not exist");
    }
}
