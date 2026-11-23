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

package com.dlap.catalog.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.catalog.dto.AssetActivityRowDTO;
import com.dlap.catalog.dto.AssetOutputDTO;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.repository.IAssetLabelAssignmentRepository;

/**
 * CAT-02b: the asset API carries the ADR-0046 time, size and retention fields exactly as stored,
 * and the activity row derives day counts from the report instant without inventing a value.
 */
class AssetMapperTest {

    private static final Instant AS_OF = Instant.parse("2026-10-19T12:00:00Z");

    private final AssetMapper mapper = new AssetMapper(mock(IAssetLabelAssignmentRepository.class));

    private static Asset bucket() {
        Asset asset = new Asset("gs://orders", "GCP", "GCS_BUCKET", "orders");
        asset.setAssetId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        return asset;
    }

    // ---- asset output ---------------------------------------------------------------------------

    @Test
    void assetOutputCarriesEveryStoredTimeSizeAndRetentionField() {
        Asset asset = bucket();
        UUID policy = UUID.randomUUID();
        asset.setStatus("ARCHIVED");
        asset.setCloudCreatedAt(Instant.parse("2025-01-02T03:04:05Z"));
        asset.setCloudCreatedSource("CRAWLER");
        asset.setSizeBytes(1_234_567_890_123L);
        asset.setSizeObservedAt(Instant.parse("2026-10-01T00:00:00Z"));
        asset.setLastAccessedAt(Instant.parse("2026-09-30T10:00:00Z"));
        asset.setLastAccessSource("AUDIT_LOG:GCP_CLOUD_AUDIT");
        asset.setLastModifiedAt(Instant.parse("2026-09-29T09:00:00Z"));
        asset.setLastPermissionChangeAt(Instant.parse("2026-09-28T08:00:00Z"));
        asset.setLifecycleEventTime(Instant.parse("2026-09-27T07:00:00Z"));
        asset.setRecreatedAt(Instant.parse("2026-09-26T06:00:00Z"));
        asset.setDiscoverySource("CRAWLER");
        asset.setRetentionDays(365);
        asset.setRetentionBasis(RetentionBasis.CREATED);
        asset.setRetentionSource("POLICY");
        asset.setRetentionPolicyId(policy);
        asset.setRetentionExpiresAt(Instant.parse("2026-01-02T03:04:05Z"));
        asset.setRetentionStatus(RetentionStatus.DUE);
        asset.setRetentionEvaluatedAt(Instant.parse("2026-10-18T00:00:00Z"));

        AssetOutputDTO dto = mapper.toAssetOutputDTO(asset);

        assertThat(dto.getStatus()).isEqualTo("ARCHIVED");
        assertThat(dto.getCloudCreatedAt()).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));
        assertThat(dto.getCloudCreatedSource()).isEqualTo("CRAWLER");
        assertThat(dto.getSizeBytes()).isEqualTo(1_234_567_890_123L);
        assertThat(dto.getSizeObservedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(dto.getLastAccessedAt()).isEqualTo(Instant.parse("2026-09-30T10:00:00Z"));
        assertThat(dto.getLastAccessSource()).isEqualTo("AUDIT_LOG:GCP_CLOUD_AUDIT");
        assertThat(dto.getLastModifiedAt()).isEqualTo(Instant.parse("2026-09-29T09:00:00Z"));
        assertThat(dto.getLastPermissionChangeAt()).isEqualTo(Instant.parse("2026-09-28T08:00:00Z"));
        assertThat(dto.getLifecycleEventTime()).isEqualTo(Instant.parse("2026-09-27T07:00:00Z"));
        assertThat(dto.getRecreatedAt()).isEqualTo(Instant.parse("2026-09-26T06:00:00Z"));
        assertThat(dto.getDiscoverySource()).isEqualTo("CRAWLER");
        assertThat(dto.getRetentionDays()).isEqualTo(365);
        assertThat(dto.getRetentionBasis()).isEqualTo(RetentionBasis.CREATED);
        assertThat(dto.getRetentionSource()).isEqualTo("POLICY");
        assertThat(dto.getRetentionPolicyId()).isEqualTo(policy);
        assertThat(dto.getRetentionExpiresAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(dto.getRetentionStatus()).isEqualTo(RetentionStatus.DUE);
        assertThat(dto.getRetentionEvaluatedAt()).isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
        assertThat(dto.isLegalHold()).isFalse();
    }

    @Test
    void assetOutputKeepsUnknownAsNullAndZeroAsZero() {
        Asset asset = bucket();
        asset.setSizeBytes(0L);

        AssetOutputDTO dto = mapper.toAssetOutputDTO(asset);

        assertThat(dto.getSizeBytes()).as("0 is a real size").isZero();
        assertThat(dto.getCloudCreatedAt()).isNull();
        assertThat(dto.getLastAccessedAt()).isNull();
        assertThat(dto.getLastModifiedAt()).isNull();
        assertThat(dto.getSizeObservedAt()).isNull();
        assertThat(dto.getRetentionExpiresAt()).isNull();
        assertThat(dto.getRetentionDays()).isNull();
        assertThat(dto.getRetentionStatus()).as("never null, UNKNOWN by default").isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(dto.isLegalHold()).isFalse();
    }

    @Test
    void aLegalHoldShowsAsOnHold() {
        Asset asset = bucket();
        asset.placeLegalHold();

        AssetOutputDTO dto = mapper.toAssetOutputDTO(asset);

        assertThat(dto.isLegalHold()).isTrue();
        assertThat(dto.getRetentionStatus()).isEqualTo(RetentionStatus.ON_HOLD);
    }

    // ---- activity row ---------------------------------------------------------------------------

    @Test
    void activityRowDerivesWholeDaysAgainstAsOf() {
        Asset asset = bucket();
        asset.setCloudCreatedAt(AS_OF.minusSeconds(400L * 86_400 + 3_600));
        asset.setLastAccessedAt(AS_OF.minusSeconds(90L * 86_400 - 1));

        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(asset, List.of("PII"), AS_OF);

        assertThat(row.ageDays()).isEqualTo(400L);
        assertThat(row.idleDays()).as("89 days and 23:59:59 rounds down").isEqualTo(89L);
        assertThat(row.asOf()).isEqualTo(AS_OF);
        assertThat(row.labels()).containsExactly("PII");
    }

    @Test
    void activityRowCopiesTheStoredFieldsAndInventsNone() {
        Asset asset = bucket();
        asset.setAssetType("BUCKET");
        asset.setRegion("us-central1");
        asset.setOwnerInfo("data-eng@example.com");
        asset.setStatus("ACTIVE");
        asset.setSizeBytes(0L);

        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(asset, null, AS_OF);

        assertThat(row.assetId()).isEqualTo(asset.getAssetId());
        assertThat(row.assetUri()).isEqualTo("gs://orders");
        assertThat(row.cloudProvider()).isEqualTo("GCP");
        assertThat(row.serviceType()).isEqualTo("GCS_BUCKET");
        assertThat(row.assetType()).isEqualTo("BUCKET");
        assertThat(row.region()).isEqualTo("us-central1");
        assertThat(row.ownerInfo()).isEqualTo("data-eng@example.com");
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.sizeBytes()).isZero();
        assertThat(row.cloudCreatedAt()).isNull();
        assertThat(row.ageDays()).as("no creation time, no age").isNull();
        assertThat(row.lastAccessedAt()).isNull();
        assertThat(row.idleDays()).as("no recorded read, no idle days").isNull();
        assertThat(row.lastModifiedAt()).isNull();
        assertThat(row.readCoverage()).isNull();
        assertThat(row.retentionStatus()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(row.retentionExpiresAt()).isNull();
        assertThat(row.legalHold()).isFalse();
        assertThat(row.labels()).isEmpty();
    }

    @Test
    void idleDaysAreNeverDerivedFromTheCreationTime() {
        Asset asset = bucket();
        asset.setCloudCreatedAt(AS_OF.minusSeconds(500L * 86_400));
        // Created long ago, no read recorded: "idle since creation" needs read coverage to claim.

        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(asset, List.of(), AS_OF);

        assertThat(row.ageDays()).isEqualTo(500L);
        assertThat(row.idleDays()).isNull();
    }

    @Test
    void evidenceNewerThanTheReportInstantGivesNoDerivedValue() {
        Asset asset = bucket();
        asset.setCloudCreatedAt(AS_OF.plusSeconds(1));
        asset.setLastAccessedAt(AS_OF.plusSeconds(86_400));

        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(asset, List.of(), AS_OF);

        assertThat(row.cloudCreatedAt()).as("the stored time is shown as it is").isEqualTo(AS_OF.plusSeconds(1));
        assertThat(row.ageDays()).isNull();
        assertThat(row.lastAccessedAt()).isEqualTo(AS_OF.plusSeconds(86_400));
        assertThat(row.idleDays()).isNull();
    }

    @Test
    void aBaseTimeEqualToAsOfIsZeroDays() {
        Asset asset = bucket();
        asset.setLastAccessedAt(AS_OF);

        assertThat(mapper.toAssetActivityRowDTO(asset, List.of(), AS_OF).idleDays()).isZero();
    }

    @Test
    void readCoverageAndItsReasonsComeFromTheColumnsNotTechnicalMetadata() {
        Asset asset = bucket();
        asset.setReadCoverage("PARTIAL");
        asset.setReadCoverageReasons(List.of("CloudTrail data events cover a prefix subset"));
        asset.setTechnicalMetadata(Map.of("readCoverage", "FULL", "storageClass", "STANDARD"));

        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(asset, List.of(), AS_OF);

        assertThat(row.readCoverage()).as("the COV-01 column, not the pre-COV-01 metadata key").isEqualTo("PARTIAL");
        assertThat(row.readCoverageReasons()).containsExactly("CloudTrail data events cover a prefix subset");
    }

    @Test
    void assetOutputCarriesTheReadCoverageColumnsAndNotTechnicalMetadata() {
        Asset asset = bucket();
        asset.setReadCoverage("full");
        asset.setReadCoverageReasons(List.of("CloudTrail data events cover the bucket"));
        asset.setTechnicalMetadata(Map.of("readCoverage", "NONE"));

        AssetOutputDTO dto = mapper.toAssetOutputDTO(asset);

        assertThat(dto.getReadCoverage()).as("the COV-01 column, upper-cased").isEqualTo("FULL");
        assertThat(dto.getReadCoverageReasons()).containsExactly("CloudTrail data events cover the bucket");
    }

    @Test
    void theActivityRowCarriesWhenCoverageWasObservedAndWhenFullCoverageStarted() {
        Asset asset = bucket();
        asset.setReadCoverage("FULL");
        asset.setReadCoverageObservedAt(Instant.parse("2026-10-19T06:41:00Z"));
        asset.setReadCoverageSince(Instant.parse("2026-09-20T06:41:00Z"));

        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(asset, List.of(), AS_OF);

        assertThat(row.readCoverageObservedAt()).isEqualTo(Instant.parse("2026-10-19T06:41:00Z"));
        assertThat(row.readCoverageSince()).isEqualTo(Instant.parse("2026-09-20T06:41:00Z"));
    }

    @Test
    void assetOutputCarriesWhenCoverageWasObservedAndWhenFullCoverageStarted() {
        Asset asset = bucket();
        asset.setReadCoverage("FULL");
        asset.setReadCoverageObservedAt(Instant.parse("2026-10-19T06:41:00Z"));
        asset.setReadCoverageSince(Instant.parse("2026-09-20T06:41:00Z"));

        AssetOutputDTO dto = mapper.toAssetOutputDTO(asset);

        assertThat(dto.getReadCoverageObservedAt()).isEqualTo(Instant.parse("2026-10-19T06:41:00Z"));
        assertThat(dto.getReadCoverageSince()).isEqualTo(Instant.parse("2026-09-20T06:41:00Z"));
    }

    @Test
    void theCoverageStartIsOnlyEverReportedWhileCoverageIsFull() {
        for (String level : new String[] {"PARTIAL", "NONE", null}) {
            Asset asset = bucket();
            asset.setReadCoverage(level);
            asset.setReadCoverageObservedAt(Instant.parse("2026-10-19T06:41:00Z"));
            asset.setReadCoverageSince(Instant.parse("2026-09-20T06:41:00Z"));

            assertThat(mapper.toAssetActivityRowDTO(asset, List.of(), AS_OF).readCoverageSince())
                    .as("activity row, coverage " + level).isNull();
            assertThat(mapper.toAssetOutputDTO(asset).getReadCoverageSince())
                    .as("asset, coverage " + level).isNull();
            assertThat(mapper.toAssetActivityRowDTO(asset, List.of(), AS_OF).readCoverageObservedAt())
                    .as("the observation time is kept for every level").isNotNull();
        }
    }

    @Test
    void anAssetWithNoObservationHasNeitherCoverageTime() {
        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(bucket(), List.of(), AS_OF);

        assertThat(row.readCoverageObservedAt()).isNull();
        assertThat(row.readCoverageSince()).isNull();
    }

    @Test
    void assetOutputLeavesUnknownReadCoverageNull() {
        AssetOutputDTO dto = mapper.toAssetOutputDTO(bucket());

        assertThat(dto.getReadCoverage()).as("never defaulted to NONE").isNull();
        assertThat(dto.getReadCoverageReasons()).isNull();
    }

    @Test
    void readCoverageIsNullWhenTheColumnIsNullAndNeverDefaultedToNone() {
        Asset none = bucket();
        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(none, List.of(), AS_OF);
        assertThat(row.readCoverage()).as("coverage is never defaulted to NONE").isNull();
        assertThat(row.readCoverageReasons()).isNull();

        Asset unknown = bucket();
        unknown.setReadCoverageReasons(List.of("missing permission cloudtrail:GetEventSelectors"));
        AssetActivityRowDTO unknownRow = mapper.toAssetActivityRowDTO(unknown, List.of(), AS_OF);
        assertThat(unknownRow.readCoverage()).as("unknown with a named permission").isNull();
        assertThat(unknownRow.readCoverageReasons()).containsExactly("missing permission cloudtrail:GetEventSelectors");

        Asset legacyKey = bucket();
        legacyKey.setTechnicalMetadata(Map.of("readCoverage", "FULL"));
        assertThat(mapper.toAssetActivityRowDTO(legacyKey, List.of(), AS_OF).readCoverage())
                .as("the old technical_metadata key is ignored").isNull();
    }

    @Test
    void theRetentionResultIsCopiedForReportsThatCompareItWithAsOf() {
        Asset asset = bucket();
        UUID policy = UUID.randomUUID();
        asset.setRetentionDays(30);
        asset.setRetentionBasis(RetentionBasis.LAST_ACCESSED);
        asset.setRetentionSource("LABEL_DEFAULT");
        asset.setRetentionPolicyId(policy);
        asset.setRetentionStatus(RetentionStatus.RETAINED);
        asset.setRetentionExpiresAt(Instant.parse("2026-11-01T00:00:00Z"));
        asset.setRetentionEvaluatedAt(Instant.parse("2026-10-18T00:00:00Z"));

        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(asset, List.of(), AS_OF);

        assertThat(row.retentionStatus()).isEqualTo(RetentionStatus.RETAINED);
        assertThat(row.retentionDays()).isEqualTo(30);
        assertThat(row.retentionBasis()).isEqualTo(RetentionBasis.LAST_ACCESSED);
        assertThat(row.retentionSource()).isEqualTo("LABEL_DEFAULT");
        assertThat(row.retentionPolicyId()).isEqualTo(policy);
        assertThat(row.retentionExpiresAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(row.retentionEvaluatedAt()).isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
    }

    @Test
    void aHeldAssetRowShowsTheHoldAndOnHold() {
        Asset asset = bucket();
        asset.placeLegalHold();

        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(asset, List.of(), AS_OF);

        assertThat(row.legalHold()).isTrue();
        assertThat(row.retentionStatus()).isEqualTo(RetentionStatus.ON_HOLD);
    }

    @Test
    void theRowIsImmutableAndNeverHasNullLabels() {
        AssetActivityRowDTO row = mapper.toAssetActivityRowDTO(bucket(), null, AS_OF);

        assertThat(row.labels()).isNotNull().isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> row.labels().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
