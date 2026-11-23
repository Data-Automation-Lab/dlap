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

package com.dlap.autoarchival.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;
import com.dlap.autoarchival.executor.ArchiveTaskSync;
import com.dlap.autoarchival.rule.ArchiveRuleProperties;
import com.dlap.common.audit.AuditException;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.Timestamp;

/**
 * The log proof: an archive is EXECUTED only from the provider's own event. Each test names one way
 * an event could look like proof and is not, or the one way it is.
 */
class ArchiveProofServiceTest {

    private static final Instant APPLIED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-11-02T00:05:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), ZoneOffset.UTC);

    private final ArchiveRuleRepository rules = mock(ArchiveRuleRepository.class);
    private final ArchiveTaskSync sync = mock(ArchiveTaskSync.class);
    private final ArchiveAudit audit = mock(ArchiveAudit.class);
    private final ArchiveRuleProperties props = new ArchiveRuleProperties();
    private ArchiveProofService proof;

    @BeforeEach
    void init() {
        proof = new ArchiveProofService(rules, sync, audit, CLOCK, props);
        // The database's conditional update: 1 when the row still has the status the proof loaded.
        when(rules.markExecutedIf(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    private static ArchiveRuleEntity row(String provider, String mode, String bucket, String prefix, String cls,
            String status) {
        ArchiveRuleEntity r = new ArchiveRuleEntity("dlap-archive-aaaaaaaaaaaa", "appr-1", "fire-1", "task-1",
                "asset-1", provider.toLowerCase() + "://" + bucket + "/" + prefix, provider, mode, bucket, prefix, cls,
                0, APPLIED);
        r.markApplied(status, "{}", false, APPLIED);
        return r;
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }

    private static DlapLogEvent.Builder event(SourceProvider p, String op, String uri, Instant at) {
        return DlapLogEvent.newBuilder().setEventId("ev-" + op).setEventTime(ts(at)).setSourceProvider(p)
                .setOperation(op).setResultStatus(ResultStatus.SUCCESS).setOrigin(Origin.ORIGIN_CLOUD_LOG)
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri(uri));
    }

    private void waiting(ArchiveRuleEntity... rows) {
        when(rules.findByProviderAndScopeBucketAndStatusIn(any(), any(), anyCollection())).thenReturn(List.of(rows));
    }

    // ---- the one way it counts ---------------------------------------------------------------------

    @Test
    void aSuccessfulLifecycleTransitionUnderThePrefixMarksTheRuleExecutedFromTheEvent() {
        ArchiveRuleEntity r = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(r);

        int marked = proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/finance/q1.csv", AFTER)
                .setEventId("ct-77").build());

        assertThat(marked).isEqualTo(1);
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTED);
        assertThat(r.getEvidenceEventId()).isEqualTo("ct-77");
        assertThat(r.getEvidenceOperation()).isEqualTo("LifecycleTransition");
        assertThat(r.getEvidenceKind()).isEqualTo(ArchiveProofService.KIND_PROVIDER_LOG);
        // business time is the provider's event time, not the clock
        assertThat(r.getEvidenceEventTime()).isEqualTo(AFTER);
        assertThat(r.getExecutedAt()).isEqualTo(AFTER).isNotEqualTo(CLOCK.instant());
        verify(rules).markExecutedIf(eq(r.getRuleId()), eq("APPLIED"), eq("ct-77"), eq("LifecycleTransition"),
                eq(AFTER), eq(ArchiveProofService.KIND_PROVIDER_LOG), eq(CLOCK.instant()));
        verify(audit).executed(r, null);
        verify(sync).sync("task-1");
    }

    @Test
    void theRepositoryIsAskedOnlyForRowsStillWaitingForProof() {
        waiting();
        proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/finance/q1.csv", AFTER).build());

        ArgumentCaptor<java.util.Collection<String>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(rules).findByProviderAndScopeBucketAndStatusIn(eq("AWS"), eq("pilot"), statuses.capture());
        assertThat(Set.copyOf(statuses.getValue())).containsExactlyInAnyOrder("APPLIED", "RENDERED");
    }

    @Test
    void aRenderedRuleThatTheOperatorAppliedIsProvenTheSameWay() {
        ArchiveRuleEntity r = row("AZURE", ArchiveRuleEntity.MODE_NATIVE_RULE, "raw", "2019/", "Cool",
                ArchiveRuleEntity.STATUS_RENDERED);
        waiting(r);

        int marked = proof.onEvent(event(SourceProvider.AZURE, "StorageBlobLogs:SetBlobTier", "az://acct/raw/2019/a.csv",
                AFTER).build());

        assertThat(marked).isEqualTo(1);
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTED);
    }

    @Test
    void anS3AccessLogTransitionCodeMustNameTheRulesClass() {
        ArchiveRuleEntity gir = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(gir);
        // the documented operation for Deep Archive is not the documented operation for Glacier Instant Retrieval
        assertThat(proof.onEvent(event(SourceProvider.AWS, "S3.TRANSITION_GDA.OBJECT", "s3://pilot/k", AFTER).build()))
                .isZero();
        assertThat(gir.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(proof.onEvent(event(SourceProvider.AWS, "S3.TRANSITION_GIR.OBJECT", "s3://pilot/k", AFTER).build()))
                .isEqualTo(1);
    }

    /**
     * Q-644. The six transition operations of the AWS S3 "Lifecycle and logging" page (retrieved 2026-11-01,
     * https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-and-other-bucket-config.html) and the class
     * each one proves. Every operation proves exactly its own class and no other.
     */
    @Test
    void everyDocumentedS3TransitionOperationProvesItsOwnClassAndNoOtherClass() {
        java.util.Map<String, String> documented = new java.util.LinkedHashMap<>();
        documented.put("S3.TRANSITION_SIA.OBJECT", "STANDARD_IA");
        documented.put("S3.TRANSITION_ZIA.OBJECT", "ONEZONE_IA");
        documented.put("S3.TRANSITION_INT.OBJECT", "INTELLIGENT_TIERING");
        documented.put("S3.TRANSITION_GIR.OBJECT", "GLACIER_IR");
        documented.put("S3.TRANSITION.OBJECT", "GLACIER");
        documented.put("S3.TRANSITION_GDA.OBJECT", "DEEP_ARCHIVE");
        assertThat(ArchiveProofService.AWS_ACCESS_LOG_TRANSITIONS).containsExactlyInAnyOrderEntriesOf(documented);

        for (var op : documented.entrySet()) {
            for (String ruleClass : documented.values()) {
                ArchiveRuleEntity r = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", ruleClass,
                        ArchiveRuleEntity.STATUS_APPLIED);
                waiting(r);
                int marked = proof.onEvent(event(SourceProvider.AWS, op.getKey(), "s3://pilot/k", AFTER).build());
                if (ruleClass.equals(op.getValue())) {
                    assertThat(marked).as("%s proves %s", op.getKey(), ruleClass).isEqualTo(1);
                    assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTED);
                } else {
                    assertThat(marked).as("%s must not prove %s", op.getKey(), ruleClass).isZero();
                    assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
                }
            }
        }
    }

    @Test
    void glacierFlexibleRetrievalIsTheCodelessTransitionOperationAndIsCheckedAgainstTheRulesClass() {
        // before Q-644 this operation matched any rule class
        ArchiveRuleEntity deepArchive = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "DEEP_ARCHIVE",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(deepArchive);
        assertThat(proof.onEvent(event(SourceProvider.AWS, "S3.TRANSITION.OBJECT", "s3://pilot/k", AFTER).build()))
                .isZero();
        ArchiveRuleEntity glacier = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(glacier);
        assertThat(proof.onEvent(event(SourceProvider.AWS, "S3.TRANSITION.OBJECT", "s3://pilot/k", AFTER).build()))
                .isEqualTo(1);
    }

    @Test
    void anUndocumentedTransitionCodeIsNotProofForAnyClass() {
        // S3.TRANSITION_DAA.OBJECT is what the code used to expect for Deep Archive; the page does not list it
        for (String undocumented : List.of("S3.TRANSITION_DAA.OBJECT", "S3.TRANSITION_XYZ.OBJECT",
                "S3.TRANSITION_SIA", "S3.EXPIRE.OBJECT", "S3.DELETE.UPLOAD")) {
            ArchiveRuleEntity r = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "DEEP_ARCHIVE",
                    ArchiveRuleEntity.STATUS_APPLIED);
            waiting(r);
            assertThat(proof.onEvent(event(SourceProvider.AWS, undocumented, "s3://pilot/k", AFTER).build()))
                    .as(undocumented).isZero();
            assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        }
    }

    @Test
    void anOperationWithSurroundingSpaceIsCheckedAgainstItsClassLikeOneWithout() {
        // the transition check strips the name, so the class check must too, or a padded name proves any class
        ArchiveRuleEntity wrongClass = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(wrongClass);
        assertThat(proof.onEvent(event(SourceProvider.AWS, " S3.TRANSITION_GDA.OBJECT ", "s3://pilot/k", AFTER)
                .build())).isZero();
        assertThat(wrongClass.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
    }

    @Test
    void anObservationOfTheObjectsClassProvesItWhenItSaysTheClassChangedAfterTheRuleBegan() {
        ArchiveRuleEntity r = row("GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "COLDLINE",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(r);
        DlapLogEvent observed = event(SourceProvider.GCP, "storage.objects.get", "gs://pilot/finance/q1.csv", AFTER)
                .setOrigin(Origin.ORIGIN_CRAWLER)
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri("gs://pilot/finance/q1.csv")
                        .putTechnicalMetadata("storageClass", "COLDLINE")
                        .putTechnicalMetadata("timeStorageClassUpdated", "2026-11-02T00:00:03Z"))
                .build();

        assertThat(proof.onEvent(observed)).isEqualTo(1);
        assertThat(r.getEvidenceKind()).isEqualTo(ArchiveProofService.KIND_STATE_OBSERVATION);
    }

    @Test
    void aDirectChangeIsProvenByTheProvidersWriteEventOnExactlyThatObject() {
        ArchiveRuleEntity r = row("GCP", ArchiveRuleEntity.MODE_DIRECT_OBJECT, "pilot", "finance/q1.csv", "COLDLINE",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(r);

        assertThat(proof.onEvent(event(SourceProvider.GCP, "storage.objects.update", "gs://pilot/finance/q1.csv.bak", AFTER)
                .build())).isZero();
        assertThat(proof.onEvent(event(SourceProvider.GCP, "storage.objects.update", "gs://pilot/finance/q1.csv", AFTER)
                .build())).isEqualTo(1);
    }

    // ---- everything that looks like proof and is not -------------------------------------------

    @Test
    void aMockedApplyWroteNothingSoNoEventCanProveIt() {
        ArchiveRuleEntity r = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        r.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", true, APPLIED);
        waiting(r);

        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/k", AFTER).build())).isZero();
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
    }

    @Test
    void aFailedOrPendingEventIsNotProof() {
        waiting(row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR", ArchiveRuleEntity.STATUS_APPLIED));
        for (ResultStatus st : List.of(ResultStatus.FAILURE, ResultStatus.PENDING, ResultStatus.RESULT_STATUS_UNSPECIFIED)) {
            assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/k", AFTER)
                    .setResultStatus(st).build())).isZero();
        }
        verify(rules, never()).save(any());
    }

    @Test
    void anEventFromBeforeTheRuleBeganIsNotProof() {
        waiting(row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR", ArchiveRuleEntity.STATUS_APPLIED));

        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/k",
                APPLIED.minusSeconds(1)).build())).isZero();
        // at the very instant the apply began is allowed
        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/k", APPLIED).build()))
                .isEqualTo(1);
    }

    @Test
    void anEventOutsideThePrefixTheBucketOrTheProviderIsNotProof() {
        ArchiveRuleEntity r = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(r);

        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/other/k", AFTER).build()))
                .isZero();
        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/finance", AFTER).build()))
                .isZero();
        // a sibling prefix that merely starts with the same letters
        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/financeX/k", AFTER).build()))
                .isZero();
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
    }

    @Test
    void theBucketLevelEventOfTheRuleItselfIsNotProof() {
        // the audit trail of DLAP writing the rule must never count as the rule firing
        waiting(row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR", ArchiveRuleEntity.STATUS_APPLIED));

        assertThat(proof.onEvent(event(SourceProvider.AWS, "PutBucketLifecycleConfiguration", "s3://pilot", AFTER).build()))
                .isZero();
        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot", AFTER).build())).isZero();
    }

    @Test
    void aManualCopyDoesNotProveALifecycleRuleAndATransitionDoesNotProveADirectChange() {
        ArchiveRuleEntity rule = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(rule);
        assertThat(proof.onEvent(event(SourceProvider.AWS, "CopyObject", "s3://pilot/finance/k", AFTER).build())).isZero();
        assertThat(proof.onEvent(event(SourceProvider.AWS, "GetObject", "s3://pilot/finance/k", AFTER).build())).isZero();

        ArchiveRuleEntity direct = row("AWS", ArchiveRuleEntity.MODE_DIRECT_OBJECT, "pilot", "finance/k", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(direct);
        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/finance/k", AFTER).build()))
                .isZero();
    }

    @Test
    void gcsAuditLogsDoNotRecordLifecycleSoNoAuditLogOperationProvesAGcsRule() {
        ArchiveRuleEntity r = row("GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "COLDLINE",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(r);

        for (String op : List.of("storage.objects.update", "storage.objects.rewrite", "storage.objects.get",
                "storage.buckets.update", "SetStorageClass")) {
            assertThat(proof.onEvent(event(SourceProvider.GCP, op, "gs://pilot/finance/k", AFTER).build())).isZero();
        }
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
    }

    @Test
    void aStateObservationNeedsTheTimeTheClassChangedAndTheRightClass() {
        ArchiveRuleEntity r = row("GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "COLDLINE",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(r);
        String uri = "gs://pilot/finance/k";
        // no change time: the object may have sat in the class all along
        assertThat(proof.onEvent(observation(uri, "COLDLINE", null))).isZero();
        // changed before the rule began
        assertThat(proof.onEvent(observation(uri, "COLDLINE", "2026-10-01T00:00:00Z"))).isZero();
        // not parseable
        assertThat(proof.onEvent(observation(uri, "COLDLINE", "yesterday"))).isZero();
        // another class
        assertThat(proof.onEvent(observation(uri, "NEARLINE", "2026-11-02T00:00:03Z"))).isZero();
        // no class reported
        assertThat(proof.onEvent(observation(uri, null, "2026-11-02T00:00:03Z"))).isZero();
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
    }

    private static DlapLogEvent observation(String uri, String cls, String changed) {
        TargetResource.Builder t = TargetResource.newBuilder().setCanonicalUri(uri);
        if (cls != null) {
            t.putTechnicalMetadata("storageClass", cls);
        }
        if (changed != null) {
            t.putTechnicalMetadata("timeStorageClassUpdated", changed);
        }
        return event(SourceProvider.GCP, "storage.objects.get", uri, AFTER).setOrigin(Origin.ORIGIN_CRAWLER)
                .setTargetResource(t).build();
    }

    @Test
    void aReportedClassThatIsNotTheRulesClassVoidsAnOperationEvent() {
        ArchiveRuleEntity r = row("AZURE", ArchiveRuleEntity.MODE_NATIVE_RULE, "raw", "", "Archive",
                ArchiveRuleEntity.STATUS_RENDERED);
        waiting(r);
        DlapLogEvent toCool = event(SourceProvider.AZURE, "SetBlobTier", "az://acct/raw/k", AFTER)
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri("az://acct/raw/k")
                        .putTechnicalMetadata("accessTier", "Cool")).build();
        assertThat(proof.onEvent(toCool)).isZero();
        DlapLogEvent toArchive = toCool.toBuilder().setTargetResource(toCool.getTargetResource().toBuilder()
                .putTechnicalMetadata("accessTier", "Archive")).build();
        assertThat(proof.onEvent(toArchive)).isEqualTo(1);
    }

    @Test
    void replayedEventsAreNotProofUnlessTheTestSwitchIsOn() {
        ArchiveRuleEntity r = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(r);
        DlapLogEvent replayed = event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/k", AFTER)
                .setOrigin(Origin.ORIGIN_REPLAY).build();

        assertThat(proof.onEvent(replayed)).isZero();
        props.setAcceptReplayEvidence(true);
        assertThat(proof.onEvent(replayed)).isEqualTo(1);
    }

    @Test
    void aCrawlerEventWithoutAStateObservationIsNotProofEvenWithATransitionLookingName() {
        waiting(row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR", ArchiveRuleEntity.STATUS_APPLIED));

        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/k", AFTER)
                .setOrigin(Origin.ORIGIN_CRAWLER).build())).isZero();
    }

    @Test
    void unknownProvidersAndEventsWithoutTimeOrTargetAreIgnored() {
        waiting(row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR", ArchiveRuleEntity.STATUS_APPLIED));

        assertThat(proof.onEvent(event(SourceProvider.SNOWFLAKE, "LifecycleTransition", "s3://pilot/k", AFTER).build()))
                .isZero();
        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/k", AFTER)
                .clearEventTime().build())).isZero();
        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "", AFTER).build())).isZero();
        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "not a uri", AFTER).build())).isZero();
        verify(rules, never()).save(any());
    }

    @Test
    void anAuditFailureWhileRecordingTheProofDoesNotLoseTheProof() {
        ArchiveRuleEntity r = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(r);
        when(audit.executed(any(), any())).thenThrow(new AuditException("down", null));

        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/k", AFTER).build()))
                .isEqualTo(1);
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTED);
        verify(sync).sync("task-1");
    }

    @Test
    void oneEventMarksEveryWaitingRuleItProves() {
        ArchiveRuleEntity wide = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        ArchiveRuleEntity narrow = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        ArchiveRuleEntity elsewhere = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "hr/", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(wide, narrow, elsewhere);

        assertThat(proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/finance/k", AFTER).build()))
                .isEqualTo(2);
        assertThat(elsewhere.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
    }

    // ---- Q-674: the proof update cannot overwrite a restore ----------------------------------------

    @Test
    void q674_theProofIsAConditionalUpdateOnTheStatusItLoaded() {
        ArchiveRuleEntity r = row("AZURE", ArchiveRuleEntity.MODE_NATIVE_RULE, "raw", "2019/", "Cool",
                ArchiveRuleEntity.STATUS_RENDERED);
        waiting(r);

        proof.onEvent(event(SourceProvider.AZURE, "StorageBlobLogs:SetBlobTier", "az://acct/raw/2019/a.csv", AFTER)
                .build());

        verify(rules).markExecutedIf(eq(r.getRuleId()), eq("RENDERED"), any(), any(), eq(AFTER), any(), any());
        verify(rules, never()).save(any());
    }

    @Test
    void q674_aRowThatARestoreRemovedMeanwhileIsNotOverwrittenWithExecuted() {
        ArchiveRuleEntity r = row("AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "GLACIER_IR",
                ArchiveRuleEntity.STATUS_APPLIED);
        waiting(r);
        // the restore got there first: the database no longer has the status the proof loaded
        when(rules.markExecutedIf(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        int marked = proof.onEvent(event(SourceProvider.AWS, "LifecycleTransition", "s3://pilot/finance/q1.csv", AFTER)
                .build());

        assertThat(marked).isZero();
        assertThat(r.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED); // untouched in memory too
        assertThat(r.getEvidenceEventId()).isNull();
        verify(rules, never()).save(any());
        verify(audit, never()).executed(any(), any());
        verify(sync, never()).sync(any());
    }
}
