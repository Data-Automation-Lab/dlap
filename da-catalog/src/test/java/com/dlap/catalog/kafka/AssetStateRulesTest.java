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

package com.dlap.catalog.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.catalog.kafka.AssetStateRules.LifecycleOutcome;
import com.dlap.catalog.kafka.AssetStateRules.Transition;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.dlap.common.model.entity.Asset;
import com.dlap.proto.log.v1.EventCategory;

/**
 * The ADR-0046 event-time rules on a plain {@link Asset}: monotonic maxima and minimum,
 * lifecycle ordering, and the incarnation boundary.
 */
class AssetStateRulesTest {

    private static final Instant T10 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T15 = Instant.parse("2026-10-01T15:00:00Z");
    private static final Instant T20 = Instant.parse("2026-10-01T20:00:00Z");
    private static final Instant T25 = Instant.parse("2026-10-02T01:00:00Z");
    private static final Instant T30 = Instant.parse("2026-10-02T06:00:00Z");
    private static final Instant T35 = Instant.parse("2026-10-02T11:00:00Z");

    private static Asset asset() {
        Asset a = new Asset("gs://rules-bucket", "GCP", "GCS_BUCKET");
        a.setAssetId(UUID.randomUUID());
        return a;
    }

    @Test
    void lastAccessedIsTheMaximumOfReadEventTimes() {
        Asset a = asset();

        assertThat(AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_DATA_READ, T20, "AUDIT_LOG")).isTrue();
        assertThat(AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_DATA_READ, T10, "LATE")).isFalse();
        assertThat(a.getLastAccessedAt()).isEqualTo(T20);
        assertThat(a.getLastAccessSource()).isEqualTo("AUDIT_LOG");

        assertThat(AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED, T30,
                "PROVIDER_METADATA")).isTrue();
        assertThat(a.getLastAccessedAt()).isEqualTo(T30);
        assertThat(a.getLastAccessSource()).isEqualTo("PROVIDER_METADATA");
    }

    @Test
    void writeAndPermissionMaximaMoveOnlyTheirOwnField() {
        Asset a = asset();

        AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_DATA_WRITE, T20, "AUDIT_LOG");
        AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_PERMISSION, T15, "AUDIT_LOG");
        AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_DATA_WRITE, T10, "AUDIT_LOG");
        boolean management = AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_MANAGEMENT, T30, "X");

        assertThat(a.getLastModifiedAt()).isEqualTo(T20);
        assertThat(a.getLastPermissionChangeAt()).isEqualTo(T15);
        assertThat(a.getLastAccessedAt()).isNull();
        assertThat(management).isFalse();
    }

    @Test
    void cloudCreatedKeepsTheLeastKnownValue() {
        Asset a = asset();

        assertThat(AssetStateRules.applyCreatedTime(a, T20, "CRAWLER")).isTrue();
        assertThat(AssetStateRules.applyCreatedTime(a, T10, "AUDIT_LOG")).isTrue();
        assertThat(AssetStateRules.applyCreatedTime(a, T30, "CRAWLER")).isFalse();

        assertThat(a.getCloudCreatedAt()).isEqualTo(T10);
        assertThat(a.getCloudCreatedSource()).isEqualTo("AUDIT_LOG");
    }

    @Test
    void newerSizeObservationReplacesOlderIsIgnored() {
        Asset a = asset();

        AssetStateRules.applySize(a, 100L, T20);
        AssetStateRules.applySize(a, 50L, T10);
        assertThat(a.getSizeBytes()).isEqualTo(100L);
        assertThat(a.getSizeObservedAt()).isEqualTo(T20);

        AssetStateRules.applySize(a, 0L, T30);
        assertThat(a.getSizeBytes()).as("0 is a real size").isZero();
        assertThat(a.getSizeObservedAt()).isEqualTo(T30);
    }

    @Test
    void statusChangesOnlyWhenEventTimeIsAtOrAfterTheLastTransition() {
        Asset a = asset();

        assertThat(AssetStateRules.applyLifecycle(a, Transition.DELETE, T20, "AUDIT_LOG"))
                .isEqualTo(LifecycleOutcome.APPLIED);
        assertThat(a.getStatus()).isEqualTo("DELETED");
        assertThat(a.getLifecycleEventTime()).isEqualTo(T20);

        // A late archive older than the delete changes nothing.
        assertThat(AssetStateRules.applyLifecycle(a, Transition.ARCHIVE, T15, "AUDIT_LOG"))
                .isEqualTo(LifecycleOutcome.STALE);
        assertThat(a.getStatus()).isEqualTo("DELETED");

        // Same event time applies (>=).
        assertThat(AssetStateRules.applyLifecycle(a, Transition.DELETE, T20, "AUDIT_LOG"))
                .isEqualTo(LifecycleOutcome.APPLIED);
    }

    @Test
    void createAfterDeleteStartsANewIncarnation() {
        Asset a = asset();
        a.setCloudCreatedAt(T10);
        a.setLastAccessedAt(T15);
        a.setLastAccessSource("AUDIT_LOG");
        a.setLastModifiedAt(T15);
        a.setLastPermissionChangeAt(T15);
        a.setSizeBytes(42L);
        a.setSizeObservedAt(T15);
        a.setRetentionDays(30);
        a.setRetentionBasis(RetentionBasis.CREATED);
        a.setRetentionSource("POLICY");
        a.setRetentionPolicyId(UUID.randomUUID());
        a.setRetentionExpiresAt(T20);
        a.setRetentionEvaluatedAt(T20);
        a.setRetentionStatus(RetentionStatus.DUE);
        AssetStateRules.applyLifecycle(a, Transition.DELETE, T20, "AUDIT_LOG");

        LifecycleOutcome outcome = AssetStateRules.applyLifecycle(a, Transition.CREATE, T30,
                "AUDIT_LOG:GCP_CLOUD_AUDIT");

        assertThat(outcome).isEqualTo(LifecycleOutcome.NEW_INCARNATION);
        assertThat(a.getStatus()).isEqualTo("ACTIVE");
        assertThat(a.getRecreatedAt()).isEqualTo(T30);
        assertThat(a.getCloudCreatedAt()).isEqualTo(T30);
        assertThat(a.getCloudCreatedSource()).isEqualTo("AUDIT_LOG:GCP_CLOUD_AUDIT");
        assertThat(a.getLifecycleEventTime()).isEqualTo(T30);
        assertThat(a.getLastAccessedAt()).isNull();
        assertThat(a.getLastAccessSource()).isNull();
        assertThat(a.getLastModifiedAt()).isNull();
        assertThat(a.getLastPermissionChangeAt()).isNull();
        assertThat(a.getSizeBytes()).isNull();
        assertThat(a.getSizeObservedAt()).isNull();
        assertThat(a.getRetentionStatus()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(a.getRetentionDays()).isNull();
        assertThat(a.getRetentionBasis()).isNull();
        assertThat(a.getRetentionSource()).isNull();
        assertThat(a.getRetentionPolicyId()).isNull();
        assertThat(a.getRetentionExpiresAt()).isNull();
        assertThat(a.getRetentionEvaluatedAt()).isNull();
    }

    @Test
    void evidenceOlderThanTheIncarnationNeverChangesTheRow() {
        Asset a = asset();
        AssetStateRules.applyLifecycle(a, Transition.DELETE, T10, "AUDIT_LOG");
        AssetStateRules.applyLifecycle(a, Transition.CREATE, T20, "AUDIT_LOG");

        assertThat(AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_DATA_READ, T15, "OLD")).isFalse();
        assertThat(AssetStateRules.applyCreatedTime(a, T15, "OLD")).isFalse();
        assertThat(AssetStateRules.applySize(a, 7L, T15)).isFalse();
        assertThat(AssetStateRules.applyLifecycle(a, Transition.DELETE, T15, "OLD"))
                .isEqualTo(LifecycleOutcome.STALE);

        assertThat(a.getLastAccessedAt()).isNull();
        assertThat(a.getCloudCreatedAt()).isEqualTo(T20);
        assertThat(a.getSizeBytes()).isNull();
        assertThat(a.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void evidenceDeliveredBeforeTheCreateIsClearedEvenWhenNewer() {
        Asset a = asset();
        AssetStateRules.applyLifecycle(a, Transition.DELETE, T10, "AUDIT_LOG");
        // The read at T35, the write and permission change at T30 and the size at T30 arrive
        // before the CREATE at T25. ADR-0046 section 4 clears them all.
        AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_DATA_READ, T35, "AUDIT_LOG");
        AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_DATA_WRITE, T30, "AUDIT_LOG");
        AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_PERMISSION, T30, "AUDIT_LOG");
        AssetStateRules.applySize(a, 9L, T30);

        AssetStateRules.applyLifecycle(a, Transition.CREATE, T25, "AUDIT_LOG");

        assertThat(a.getRecreatedAt()).isEqualTo(T25);
        assertThat(a.getLastAccessedAt()).isNull();
        assertThat(a.getLastAccessSource()).isNull();
        assertThat(a.getLastModifiedAt()).isNull();
        assertThat(a.getLastPermissionChangeAt()).isNull();
        assertThat(a.getSizeBytes()).isNull();
        assertThat(a.getSizeObservedAt()).isNull();

        // Evidence delivered after the CREATE, at or after its start, applies again.
        assertThat(AssetStateRules.applyActivity(a, EventCategory.EVENT_CATEGORY_DATA_READ, T25, "AUDIT_LOG"))
                .isTrue();
        assertThat(a.getLastAccessedAt()).isEqualTo(T25);
    }

    @Test
    void aCreateOnADeletedRowStartsTheIncarnationAtItsEventTimeNotTheReportedCreationTime() {
        Asset a = asset();
        AssetStateRules.applyLifecycle(a, Transition.DELETE, T10, "AUDIT_LOG");

        // A crawl at T30 reports a creation time T20 after the delete.
        assertThat(AssetStateRules.applyLifecycle(a, Transition.CREATE, T30, "CRAWLER"))
                .isEqualTo(LifecycleOutcome.NEW_INCARNATION);

        assertThat(a.getRecreatedAt()).isEqualTo(T30);
        assertThat(a.getCloudCreatedAt()).isEqualTo(T30);
        assertThat(a.getCloudCreatedSource()).isEqualTo("CRAWLER");
        assertThat(a.getLifecycleEventTime()).isEqualTo(T30);
        // The reported creation time is older than the incarnation: it never changes the row.
        assertThat(AssetStateRules.applyCreatedTime(a, T20, "CRAWLER")).isFalse();
        assertThat(a.getCloudCreatedAt()).isEqualTo(T30);
    }

    @Test
    void aLaterCreateLeavesAnArchivedRowArchived() {
        assertACreateChangesNeitherStatusNorLifecycleTime("ARCHIVED");
    }

    @Test
    void aLaterCreateLeavesAPendingDeletionRowPendingDeletion() {
        assertACreateChangesNeitherStatusNorLifecycleTime("PENDING_DELETION");
    }

    @Test
    void aLaterCreateOnAnActiveRowDoesNotMoveTheLifecycleTime() {
        assertACreateChangesNeitherStatusNorLifecycleTime("ACTIVE");
    }

    /** Crawlers report every resource they see as a CREATE: on a row that exists, it is no transition. */
    private static void assertACreateChangesNeitherStatusNorLifecycleTime(String status) {
        Asset a = asset();
        a.setStatus(status);
        a.setLifecycleEventTime(T10);
        a.setLastAccessedAt(T15);

        LifecycleOutcome crawl = AssetStateRules.applyLifecycle(a, Transition.CREATE, T20, "CRAWLER");
        LifecycleOutcome log = AssetStateRules.applyLifecycle(a, Transition.CREATE, T25, "AUDIT_LOG");

        assertThat(crawl).isEqualTo(LifecycleOutcome.UNCHANGED);
        assertThat(log).isEqualTo(LifecycleOutcome.UNCHANGED);
        assertThat(a.getStatus()).isEqualTo(status);
        assertThat(a.getLifecycleEventTime()).isEqualTo(T10);
        assertThat(a.getRecreatedAt()).isNull();
        assertThat(a.getLastAccessedAt()).isEqualTo(T15);
    }

    @Test
    void aDelayedArchiveFromBeforeACrawlStillApplies() {
        Asset a = asset();
        a.setLifecycleEventTime(T10);

        // The crawl at T30 is delivered before the archive at T20, as audit-log delays allow.
        assertThat(AssetStateRules.applyLifecycle(a, Transition.CREATE, T30, "CRAWLER"))
                .isEqualTo(LifecycleOutcome.UNCHANGED);
        assertThat(AssetStateRules.applyLifecycle(a, Transition.ARCHIVE, T20, "AUDIT_LOG"))
                .isEqualTo(LifecycleOutcome.APPLIED);
        assertThat(a.getStatus()).isEqualTo("ARCHIVED");
        assertThat(a.getLifecycleEventTime()).isEqualTo(T20);

        // The next crawl leaves it archived.
        AssetStateRules.applyLifecycle(a, Transition.CREATE, T35, "CRAWLER");
        assertThat(a.getStatus()).isEqualTo("ARCHIVED");
        assertThat(a.getLifecycleEventTime()).isEqualTo(T20);
    }

    @Test
    void aDelayedDeleteFromBeforeACrawlAppliesAndTheNextCrawlStartsANewIncarnation() {
        Asset a = asset();
        a.setLifecycleEventTime(T10);

        AssetStateRules.applyLifecycle(a, Transition.CREATE, T20, "CRAWLER");
        assertThat(AssetStateRules.applyLifecycle(a, Transition.DELETE, T15, "AUDIT_LOG"))
                .as("a crawl is no transition, so it cannot make an older delete stale")
                .isEqualTo(LifecycleOutcome.APPLIED);
        assertThat(a.getStatus()).isEqualTo("DELETED");

        // The crawler saw the resource after the delete, so it was recreated: the next crawl
        // starts the new incarnation at its event time.
        assertThat(AssetStateRules.applyLifecycle(a, Transition.CREATE, T30, "CRAWLER"))
                .isEqualTo(LifecycleOutcome.NEW_INCARNATION);
        assertThat(a.getStatus()).isEqualTo("ACTIVE");
        assertThat(a.getRecreatedAt()).isEqualTo(T30);
    }

    @Test
    void aStaleCreateOnALiveRowIsStale() {
        Asset a = asset();
        a.setStatus("ARCHIVED");
        a.setLifecycleEventTime(T20);

        assertThat(AssetStateRules.applyLifecycle(a, Transition.CREATE, T10, "AUDIT_LOG"))
                .isEqualTo(LifecycleOutcome.STALE);
        assertThat(a.getStatus()).isEqualTo("ARCHIVED");
        assertThat(a.getLifecycleEventTime()).isEqualTo(T20);
    }

    @Test
    void legalHoldSurvivesANewIncarnation() {
        Asset a = asset();
        a.placeLegalHold();
        AssetStateRules.applyLifecycle(a, Transition.DELETE, T10, "AUDIT_LOG");

        AssetStateRules.applyLifecycle(a, Transition.CREATE, T20, "AUDIT_LOG");

        assertThat(a.isLegalHold()).isTrue();
        assertThat(a.getRetentionStatus()).isEqualTo(RetentionStatus.ON_HOLD);
    }

    @Test
    void channelsAreCutToTheColumnWidth() {
        String longChannel = "AUDIT_LOG:" + "X".repeat(100);
        assertThat(AssetStateRules.truncate(longChannel)).hasSize(AssetStateRules.MAX_CHANNEL_LENGTH);
        assertThat(AssetStateRules.truncate("CRAWLER")).isEqualTo("CRAWLER");
        assertThat(AssetStateRules.truncate(null)).isNull();
    }
}
