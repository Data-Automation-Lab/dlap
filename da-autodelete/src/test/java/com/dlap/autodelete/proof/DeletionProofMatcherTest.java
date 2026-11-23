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

package com.dlap.autodelete.proof;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.RequestMetadata;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RET-02: a disposition is executed only when the provider's own event is seen. One test per
 * platform signature, and one per way a look-alike event is turned away.
 */
class DeletionProofMatcherTest {

    private static final Instant APPLIED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Duration SKEW = Duration.ofMinutes(10);

    static RetireDispositionEntity disposition(String provider, String mode, String platform, String target) {
        return RetireDispositionEntity.builder().id(UUID.randomUUID()).softDeleteRecordId(UUID.randomUUID())
                .cloudProvider(provider).mode(mode).platform(platform).targetUri(target).appliedAt(APPLIED)
                .status(RetireDispositionEntity.AWAITING_PROOF).build();
    }

    static DlapLogEvent.Builder delete(SourceProvider provider, String uri, Instant when) {
        return DlapLogEvent.newBuilder()
                .setEventId("evt-" + UUID.randomUUID())
                .setSourceProvider(provider)
                .setChangeType(ChangeType.DELETE)
                .setResultStatus(ResultStatus.SUCCESS)
                .setOrigin(Origin.ORIGIN_CLOUD_LOG)
                .setEventTime(Timestamp.newBuilder().setSeconds(when.getEpochSecond()).setNanos(when.getNano()))
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri(uri));
    }

    private static boolean proves(DlapLogEvent.Builder e, RetireDispositionEntity d) {
        return DeletionProofMatcher.match(e.build(), d, SKEW).isPresent();
    }

    @Test
    void anS3LifecycleExpirationOfAnObjectInTheBucketProvesTheS3Rule() {
        RetireDispositionEntity d = disposition("AWS", "NATIVE_RULE", "S3_LIFECYCLE", "s3://dlap-aws-e2e");
        DlapLogEvent.Builder cloudTrail = delete(SourceProvider.AWS, "s3://dlap-aws-e2e/k/a.parquet",
                APPLIED.plus(Duration.ofHours(20)))
                .setSourceService("s3lifecycle.amazonaws.com").setOperation("DeleteObject")
                .setSourceFormat("AWS_CLOUDTRAIL/s3-file");
        assertThat(DeletionProofMatcher.match(cloudTrail.build(), d, SKEW)).contains("AWS_CLOUDTRAIL/s3-file");

        DlapLogEvent.Builder accessLog = delete(SourceProvider.AWS, "s3://dlap-aws-e2e/k/b.parquet",
                APPLIED.plus(Duration.ofHours(20))).setSourceService("s3.amazonaws.com").setOperation("S3.EXPIRE.OBJECT");
        assertThat(proves(accessLog, d)).isTrue();

        DlapLogEvent.Builder invokedBy = delete(SourceProvider.AWS, "s3://dlap-aws-e2e/c",
                APPLIED.plus(Duration.ofHours(20))).setSourceService("s3.amazonaws.com").setOperation("DeleteObject")
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM).setId("lifecycle.s3.amazonaws.com"));
        assertThat(proves(invokedBy, d)).isTrue();
    }

    /**
     * Q-657 (RET-06): S3.CREATE.DELETEMARKER is no lifecycle signature any more. Even a DELETE that some other
     * producer stamps with that operation does not prove the S3 rule.
     */
    @Test
    void aDeleteMarkerOperationNoLongerProvesTheS3Rule() {
        RetireDispositionEntity d = disposition("AWS", "NATIVE_RULE", "S3_LIFECYCLE", "s3://dlap-aws-e2e");
        DlapLogEvent.Builder marker = delete(SourceProvider.AWS, "s3://dlap-aws-e2e/k/a.parquet",
                APPLIED.plus(Duration.ofHours(20))).setSourceService("s3.amazonaws.com")
                .setOperation("S3.CREATE.DELETEMARKER");
        assertThat(proves(marker, d)).isFalse();
        assertThat(DeletionProofMatcher.hasLifecycleSignature(marker.build(),
                com.dlap.autodelete.nativerule.NativeRulePlatform.S3_LIFECYCLE)).isFalse();
    }

    @Test
    void aPersonDeletingAnObjectDoesNotProveTheLifecycleRule() {
        RetireDispositionEntity d = disposition("AWS", "NATIVE_RULE", "S3_LIFECYCLE", "s3://dlap-aws-e2e");
        DlapLogEvent.Builder manual = delete(SourceProvider.AWS, "s3://dlap-aws-e2e/k/a.parquet",
                APPLIED.plus(Duration.ofHours(1))).setSourceService("s3.amazonaws.com").setOperation("DeleteObject")
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.USER).setId("arn:aws:iam::1:user/bob"));
        assertThat(proves(manual, d)).isFalse();
    }

    @Test
    void theOtherPlatformsSignaturesProveTheirRules() {
        DlapLogEvent.Builder gcs = delete(SourceProvider.GCP, "gs://dlap-gcp-e2e/x.csv", APPLIED.plus(Duration.ofDays(1)))
                .setSourceService("storage.googleapis.com").setOperation("DELETE")
                .setRequestMetadata(RequestMetadata.newBuilder().setUserAgent("GCS Lifecycle Management"));
        assertThat(proves(gcs, disposition("GCP", "NATIVE_RULE", "GCS_LIFECYCLE", "gs://dlap-gcp-e2e"))).isTrue();

        DlapLogEvent.Builder bq = delete(SourceProvider.GCP, "bq://dlap-gcp-e2e/sales/orders",
                APPLIED.plus(Duration.ofHours(2))).setSourceService("bigquery.googleapis.com")
                .setOperation("InternalTableExpired");
        assertThat(proves(bq, disposition("GCP", "NATIVE_RULE", "BIGQUERY_TABLE_EXPIRATION",
                "bq://dlap-gcp-e2e/sales/orders"))).isTrue();

        DlapLogEvent.Builder azure = delete(SourceProvider.AZURE, "az://dlapazuree2e/raw/a/b.csv",
                APPLIED.plus(Duration.ofDays(1))).setSourceService("blob.core.windows.net").setOperation("DeleteBlob")
                .setRequestMetadata(RequestMetadata.newBuilder().setUserAgent("ObjectLifecycleManagement"));
        assertThat(proves(azure, disposition("AZURE", "NATIVE_RULE", "AZURE_BLOB_LIFECYCLE",
                "az://dlapazuree2e/raw"))).isTrue();

        DlapLogEvent.Builder bqManual = delete(SourceProvider.GCP, "bq://dlap-gcp-e2e/sales/orders",
                APPLIED.plus(Duration.ofHours(2))).setSourceService("bigquery.googleapis.com")
                .setOperation("google.cloud.bigquery.v2.TableService.DeleteTable")
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.USER).setId("bob@example.test"));
        assertThat(proves(bqManual, disposition("GCP", "NATIVE_RULE", "BIGQUERY_TABLE_EXPIRATION",
                "bq://dlap-gcp-e2e/sales/orders"))).isFalse();
    }

    @Test
    void anEngineDeleteIsProvenByAnyOfTheProvidersOwnDeleteEventsOnTheTarget() {
        RetireDispositionEntity d = disposition("GCP", "DIRECT", "ENGINE_GCP", "gs://b/dir/file.csv");
        DlapLogEvent.Builder own = delete(SourceProvider.GCP, "gs://b/dir/file.csv", APPLIED.plusSeconds(5))
                .setSourceService("storage.googleapis.com").setOperation("storage.objects.delete");
        assertThat(proves(own, d)).isTrue();
    }

    @Test
    void lookAlikeEventsAreTurnedAway() {
        RetireDispositionEntity d = disposition("GCP", "DIRECT", "ENGINE_GCP", "gs://b");
        Instant after = APPLIED.plusSeconds(60);
        assertThat(proves(delete(SourceProvider.GCP, "gs://b2/x", after), d)).as("a sibling bucket").isFalse();
        assertThat(proves(delete(SourceProvider.AWS, "gs://b/x", after), d)).as("another provider").isFalse();
        assertThat(proves(delete(SourceProvider.GCP, "gs://b/x", APPLIED.minus(Duration.ofHours(1))), d))
                .as("before the rule was applied").isFalse();
        assertThat(proves(delete(SourceProvider.GCP, "gs://b/x", APPLIED.minus(Duration.ofMinutes(5))), d))
                .as("within the clock skew").isTrue();
        assertThat(proves(delete(SourceProvider.GCP, "gs://b/x", after).setResultStatus(ResultStatus.FAILURE), d))
                .as("a failed delete").isFalse();
        assertThat(proves(delete(SourceProvider.GCP, "gs://b/x", after).setChangeType(ChangeType.ACCESS), d))
                .as("not a delete").isFalse();
        assertThat(proves(delete(SourceProvider.GCP, "gs://b/x", after).setOrigin(Origin.ORIGIN_CRAWLER), d))
                .as("a crawler observation is not the provider's log").isFalse();
        assertThat(proves(delete(SourceProvider.GCP, "gs://b/x", after).setOrigin(Origin.ORIGIN_REPLAY), d))
                .as("a replayed fixture").isFalse();
        assertThat(proves(delete(SourceProvider.GCP, "", after), d)).as("no canonical URI").isFalse();
        RetireDispositionEntity notApplied = disposition("GCP", "DIRECT", "ENGINE_GCP", "gs://b");
        notApplied.setAppliedAt(null);
        assertThat(proves(delete(SourceProvider.GCP, "gs://b/x", after), notApplied)).as("never applied").isFalse();
    }
}
