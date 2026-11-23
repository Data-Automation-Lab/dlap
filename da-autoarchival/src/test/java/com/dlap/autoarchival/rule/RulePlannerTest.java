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

package com.dlap.autoarchival.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;

class RulePlannerTest {

    private final ArchiveRuleProperties props = new ArchiveRuleProperties();
    private final ArchiveRuleRepository rules = mock(ArchiveRuleRepository.class);
    private final FakeRuleProvider aws = new FakeRuleProvider("AWS", true);
    private final FakeRuleProvider gcp = new FakeRuleProvider("GCP", true);
    private final FakeRuleProvider azure = new FakeRuleProvider("AZURE", false);
    private RulePlanner planner;

    @BeforeEach
    void init() {
        planner = new RulePlanner(List.of(aws, gcp, azure), props, rules);
    }

    private static RuleException.Reason reason(Runnable r) {
        try {
            r.run();
        } catch (RuleException e) {
            return e.reason();
        }
        throw new AssertionError("expected a RuleException");
    }

    @Test
    void aBucketPlanCarriesTheSpecTheDocumentTheInventoryAndABoundedListing() {
        RulePlan plan = planner.plan("GCP", "gs://pilot", Map.of("tier", "COLD"), null, "appr-1", null);

        assertThat(plan.scopeKind()).isEqualTo("BUCKET");
        assertThat(plan.spec().nativeClass()).isEqualTo("COLDLINE");
        assertThat(plan.spec().afterDays()).isZero();
        assertThat(plan.spec().ruleId()).startsWith("dlap-archive-").hasSize("dlap-archive-".length() + 12);
        assertThat(plan.document()).contains(plan.spec().ruleId());
        assertThat(plan.applyable()).isTrue();
        assertThat(plan.listing().entries()).hasSize(3);
        assertThat(plan.listing().limit()).isEqualTo(50);
        assertThat(plan.notes()).anyMatch(n -> n.contains("every object in the bucket"));
    }

    @Test
    void aPrefixPlanListsOnlyThatPrefix() {
        RulePlan plan = planner.plan("AWS", "s3://pilot-data/finance/", Map.of("prefix", "2019/"), null, "appr-1", 2);

        assertThat(plan.scopeKind()).isEqualTo("PREFIX");
        assertThat(plan.spec().scope().prefix()).isEqualTo("finance/2019/");
        assertThat(plan.listing().entries()).extracting(NativeRuleProvider.ListingEntry::key)
                .containsExactly("finance/2019/obj-0", "finance/2019/obj-1");
        assertThat(plan.listing().truncated()).isTrue();
        assertThat(plan.notes()).anyMatch(n -> n.contains("sample of 2"));
    }

    @Test
    void theListingIsBoundedWhateverTheCallerAsks() {
        aws.objectCount = 5000;
        assertThat(planner.plan("AWS", "s3://b", null, null, null, 100000).listing().limit()).isEqualTo(1000);
        assertThat(planner.plan("AWS", "s3://b", null, null, null, -5).listing().limit()).isEqualTo(1);
        assertThat(planner.plan("AWS", "s3://b", null, null, null, 0).listing().entries()).isEmpty();
        assertThat(aws.calls).doesNotContain("list:0");
    }

    @Test
    void theDefaultTierComesFromTheConfigurationAndAnInvalidOneIsRefusedNotGuessed() {
        assertThat(planner.plan("AWS", "s3://b", null, "ARCHIVE", null, 0).spec().nativeClass()).isEqualTo("DEEP_ARCHIVE");
        assertThat(planner.plan("AWS", "s3://b", null, null, null, 0).spec().nativeClass()).isEqualTo("GLACIER_IR");
        assertThat(reason(() -> planner.plan("AWS", "s3://b", null, "STANDARD_ARCHIVE", null, 0)))
                .isEqualTo(RuleException.Reason.INVALID_TIER);
        // the action's own tier wins over the default, in either of its spellings
        assertThat(planner.plan("GCP", "gs://b", Map.of("targetStorageClass", "ARCHIVE"), "COOL", null, 0)
                .spec().nativeClass()).isEqualTo("ARCHIVE");
    }

    @Test
    void s3InfrequentAccessNeedsThirtyDays() {
        // not asked: the class minimum is used
        assertThat(planner.plan("AWS", "s3://b", Map.of("tier", "COOL"), null, null, 0).spec().afterDays()).isEqualTo(30);
        assertThat(reason(() -> planner.plan("AWS", "s3://b", Map.of("tier", "COOL", "afterDays", 10), null, null, 0)))
                .isEqualTo(RuleException.Reason.INVALID_AGE);
        assertThat(planner.plan("AWS", "s3://b", Map.of("tier", "COOL", "afterDays", "45"), null, null, 0)
                .spec().afterDays()).isEqualTo(45);
    }

    @Test
    void theAgeHasAnUpperBoundAndMustBeANumber() {
        assertThat(reason(() -> planner.plan("GCP", "gs://b", Map.of("afterDays", 3651), null, null, 0)))
                .isEqualTo(RuleException.Reason.INVALID_AGE);
        assertThat(planner.plan("GCP", "gs://b", Map.of("afterDays", 3650), null, null, 0).spec().afterDays())
                .isEqualTo(3650);
        assertThat(reason(() -> planner.plan("GCP", "gs://b", Map.of("afterDays", "soon"), null, null, 0)))
                .isEqualTo(RuleException.Reason.INVALID_AGE);
        assertThat(reason(() -> planner.plan("GCP", "gs://b", Map.of("afterDays", -1), null, null, 0)))
                .isEqualTo(RuleException.Reason.INVALID_AGE);
    }

    @Test
    void aWholeBucketRuleCanBeSwitchedOff() {
        props.setAllowBucketWide(false);
        assertThat(reason(() -> planner.plan("GCP", "gs://b", null, null, null, 0)))
                .isEqualTo(RuleException.Reason.BUCKET_WIDE_NOT_ALLOWED);
        assertThat(planner.plan("GCP", "gs://b", Map.of("prefix", "x/"), null, null, 0).scopeKind()).isEqualTo("PREFIX");
    }

    @Test
    void thePrefixLimitIsEnforcedBeforeAnythingIsAskedOfTheProvider() {
        props.setMaxPrefixBytes(8);
        assertThat(reason(() -> planner.plan("AWS", "s3://b", Map.of("prefix", "123456789"), null, null, 0)))
                .isEqualTo(RuleException.Reason.PREFIX_TOO_LONG);
        assertThat(aws.calls).isEmpty();
    }

    @Test
    void theProvidersOwnRuleLimitStopsThePlan() {
        aws.providerMax = 1000;
        aws.foreignRules = 1000;
        assertThat(reason(() -> planner.plan("AWS", "s3://b", null, null, "appr-1", 0)))
                .isEqualTo(RuleException.Reason.RULE_LIMIT);
        gcp.providerMax = 100;
        gcp.foreignRules = 99;
        assertThat(planner.plan("GCP", "gs://b", null, null, "appr-1", 0).scopeKind()).isEqualTo("BUCKET");
        gcp.foreignRules = 100;
        assertThat(reason(() -> planner.plan("GCP", "gs://b", null, null, "appr-1", 0)))
                .isEqualTo(RuleException.Reason.RULE_LIMIT);
    }

    @Test
    void dlapsOwnCapPerBucketStopsThePlan() {
        when(rules.countByProviderAndScopeBucketAndStatusIn(eq("GCP"), eq("busy"), any())).thenReturn(20L);
        assertThat(reason(() -> planner.plan("GCP", "gs://busy", null, null, "appr-1", 0)))
                .isEqualTo(RuleException.Reason.RULE_LIMIT);
        assertThat(planner.plan("GCP", "gs://quiet", null, null, "appr-1", 0)).isNotNull();
    }

    @Test
    void aRuleThatIsAlreadyInPlaceIsNotCountedAgainstTheLimitsSoARepeatIsIdempotent() {
        RulePlan first = planner.plan("GCP", "gs://b", null, null, "appr-1", 0);
        gcp.rules.add(first.spec().ruleId());
        gcp.foreignRules = 100;
        when(rules.countByProviderAndScopeBucketAndStatusIn(eq("GCP"), eq("b"), any())).thenReturn(20L);

        RulePlan again = planner.plan("GCP", "gs://b", null, null, "appr-1", 0);

        assertThat(again.inventory().specPresent()).isTrue();
        assertThat(again.spec()).isEqualTo(first.spec());
    }

    @Test
    void theRuleIdIsStableForTheSameApprovalAndScopeAndDiffersWhenAnythingChanges() {
        RuleScope s = new RuleScope("AWS", "", "b", "p/", false);
        String id = RulePlanner.ruleId("appr-1", s, "GLACIER_IR", 0);
        assertThat(RulePlanner.ruleId("appr-1", s, "GLACIER_IR", 0)).isEqualTo(id);
        assertThat(RulePlanner.ruleId("appr-2", s, "GLACIER_IR", 0)).isNotEqualTo(id);
        assertThat(RulePlanner.ruleId("appr-1", s, "DEEP_ARCHIVE", 0)).isNotEqualTo(id);
        assertThat(RulePlanner.ruleId("appr-1", s, "GLACIER_IR", 1)).isNotEqualTo(id);
        assertThat(RulePlanner.ruleId("appr-1", new RuleScope("AWS", "", "b", "q/", false), "GLACIER_IR", 0))
                .isNotEqualTo(id);
        assertThat(RulePlanner.ruleId(null, s, "GLACIER_IR", 0)).startsWith("dlap-archive-");
    }

    @Test
    void aSingleObjectGetsADirectChangeNotAPrefixRule() {
        RulePlan plan = planner.plan("AWS", "s3://b/finance/q1.parquet", null, null, "appr-1", null);

        assertThat(plan.scopeKind()).isEqualTo("OBJECT");
        assertThat(plan.document()).contains("set-storage-class").contains("GLACIER_IR");
        assertThat(plan.inventory().totalRules()).isEqualTo(-1);
        // the listing keeps only the exact key, not its neighbours
        assertThat(plan.listing().entries()).isEmpty();
        assertThat(aws.calls).doesNotContain("inventory");
        assertThat(plan.notes()).anyMatch(n -> n.contains("direct storage class change"));
    }

    @Test
    void azureIsRenderOnlyAndSaysSo() {
        RulePlan plan = planner.plan("AZURE", "az://acct/raw/2019/", Map.of("tier", "ARCHIVE"), null, "appr-1", 0);

        assertThat(plan.applyable()).isFalse();
        assertThat(plan.notes()).anyMatch(n -> n.contains("cannot write this provider's rule"));
        assertThat(plan.spec().nativeClass()).isEqualTo("Archive");
    }

    @Test
    void recheckCatchesABucketThatFilledUpSinceTheApproval() {
        RulePlan plan = planner.plan("GCP", "gs://b", null, null, "appr-1", 0);
        gcp.foreignRules = 100;

        assertThat(reason(() -> planner.recheck(plan.spec()))).isEqualTo(RuleException.Reason.RULE_LIMIT);
    }

    @Test
    void anUnregisteredProviderIsRefused() {
        assertThat(reason(() -> new RulePlanner(List.of(aws), props, rules).plan("GCP", "gs://b", null, null, null, 0)))
                .isEqualTo(RuleException.Reason.UNSUPPORTED_PROVIDER);
    }

    @Test
    void aMisconfiguredLimitStopsTheStartUp() {
        ArchiveRuleProperties bad = new ArchiveRuleProperties();
        bad.setMaxDlapRulesPerBucket(0);
        assertThatThrownBy(() -> new RulePlanner(List.of(aws), bad, rules)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-dlap-rules-per-bucket");
        ArchiveRuleProperties badTier = new ArchiveRuleProperties();
        badTier.setDefaultTier("STANDARD_ARCHIVE");
        assertThatThrownBy(() -> new RulePlanner(List.of(aws), badTier, rules)).isInstanceOf(RuleException.class);
    }

    // ---- Q-673: an identical live GCS scope is refused -----------------------------------------------

    private static ArchiveRuleEntity liveRow(String ruleId, String provider, String bucket, String prefix,
            String cls, int days, String status, boolean mock) {
        ArchiveRuleEntity r = new ArchiveRuleEntity(ruleId, "appr-" + ruleId, null, "task-1", "asset-1",
                provider.equals("GCP") ? "gs://" + bucket + "/" + prefix : "s3://" + bucket + "/" + prefix, provider,
                ArchiveRuleEntity.MODE_NATIVE_RULE, bucket, prefix, cls, days, java.time.Instant.parse("2026-11-01T00:00:00Z"));
        r.markApplied(status, "{}", mock, java.time.Instant.parse("2026-11-01T00:00:00Z"));
        return r;
    }

    private RulePlanner plannerOver(ArchiveRuleEntity... existing) {
        Map<String, ArchiveRuleEntity> store = new java.util.HashMap<>();
        for (ArchiveRuleEntity r : existing) {
            store.put(r.getRuleId(), r);
        }
        return new RulePlanner(List.of(aws, gcp, azure), props, com.dlap.autoarchival.executor.InMemoryRules.over(store));
    }

    @Test
    void q673_aSecondRowForAnIdenticalLiveGcsScopeIsRefusedNamingTheExistingRow() {
        RulePlanner p = plannerOver(liveRow("dlap-archive-first0000", "GCP", "pilot", "finance/", "COLDLINE", 0,
                ArchiveRuleEntity.STATUS_APPLIED, false));

        RuleException e = org.junit.jupiter.api.Assertions.assertThrows(RuleException.class,
                () -> p.plan("GCP", "gs://pilot/finance/", Map.of("tier", "COLD"), null, "appr-second", 0));

        assertThat(e.reason()).isEqualTo(RuleException.Reason.DUPLICATE_SCOPE);
        assertThat(e.getMessage()).contains("dlap-archive-first0000").contains("APPLIED").contains("gs://pilot/finance/")
                .contains("COLDLINE");
    }

    @Test
    void q673_everyLiveStatusCountsAndEveryOtherStatusDoesNot() {
        for (String live : List.of("PLANNED", "EXECUTING", "APPLIED", "EXECUTED")) {
            RulePlanner p = plannerOver(liveRow("dlap-archive-first0000", "GCP", "pilot", "finance/", "COLDLINE", 0,
                    live, false));
            assertThat(reason(() -> p.plan("GCP", "gs://pilot/finance/", Map.of("tier", "COLD"), null, "appr-2", 0)))
                    .as(live).isEqualTo(RuleException.Reason.DUPLICATE_SCOPE);
        }
        for (String gone : List.of("REMOVED", "FAILED", "REFUSED")) {
            RulePlanner p = plannerOver(liveRow("dlap-archive-first0000", "GCP", "pilot", "finance/", "COLDLINE", 0,
                    gone, false));
            assertThat(p.plan("GCP", "gs://pilot/finance/", Map.of("tier", "COLD"), null, "appr-2", 0).spec().ruleId())
                    .as(gone).startsWith("dlap-archive-");
        }
    }

    @Test
    void q673_aDifferentPrefixClassOrAgeIsNotADuplicate() {
        RulePlanner p = plannerOver(liveRow("dlap-archive-first0000", "GCP", "pilot", "finance/", "COLDLINE", 0,
                ArchiveRuleEntity.STATUS_APPLIED, false));

        assertThat(p.plan("GCP", "gs://pilot/hr/", Map.of("tier", "COLD"), null, "appr-2", 0)).isNotNull();
        assertThat(p.plan("GCP", "gs://pilot/finance/", Map.of("tier", "ARCHIVE"), null, "appr-2", 0)).isNotNull();
        assertThat(p.plan("GCP", "gs://pilot/finance/", Map.of("tier", "COLD", "afterDays", 30), null, "appr-2", 0))
                .isNotNull();
        assertThat(p.plan("GCP", "gs://other/finance/", Map.of("tier", "COLD"), null, "appr-2", 0)).isNotNull();
    }

    @Test
    void q673_aMockedRowWroteNothingSoItDoesNotBlockAndS3RulesCarryTheirOwnIdSoTheyDoNotCollide() {
        RulePlanner mocked = plannerOver(liveRow("dlap-archive-first0000", "GCP", "pilot", "finance/", "COLDLINE", 0,
                ArchiveRuleEntity.STATUS_APPLIED, true));
        assertThat(mocked.plan("GCP", "gs://pilot/finance/", Map.of("tier", "COLD"), null, "appr-2", 0)).isNotNull();

        RulePlanner s3 = plannerOver(liveRow("dlap-archive-first0000", "AWS", "pilot", "finance/", "GLACIER_IR", 0,
                ArchiveRuleEntity.STATUS_APPLIED, false));
        assertThat(s3.plan("AWS", "s3://pilot/finance/", Map.of("tier", "COLD"), null, "appr-2", 0)).isNotNull();
    }

    @Test
    void q673_theDryRunAndThePreApprovalCheckSeeTheDuplicateToo() {
        RulePlanner p = plannerOver(liveRow("dlap-archive-first0000", "GCP", "pilot", "finance/", "COLDLINE", 0,
                ArchiveRuleEntity.STATUS_EXECUTED, false));

        // approvalId null is the dry run and the check made before an approval is asked
        assertThat(reason(() -> p.plan("GCP", "gs://pilot/finance/", Map.of("tier", "COLD"), null, null, 0)))
                .isEqualTo(RuleException.Reason.DUPLICATE_SCOPE);
    }
}
