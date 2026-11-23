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

package com.dlap.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of {@code GET /api/v1/catalog/assets/activity}: what the catalog knows about an
 * asset's age, size, use and retention at one instant ({@code asOf}).
 *
 * <p>Every field except {@code assetId}, {@code assetUri}, {@code cloudProvider},
 * {@code serviceType}, {@code retentionStatus}, {@code legalHold}, {@code labels} and
 * {@code asOf} can be {@code null}, and a {@code null} always means unknown, never zero, stale
 * or fresh (ADR-0046). Nulls are written out so the row has one shape that a report or a CSV
 * export can rely on.
 *
 * <p>The row is a view of the stored descriptor. The times are business times from provider
 * evidence; {@code retentionEvaluatedAt} is the only DLAP time. The derived day counts are
 * computed against {@code asOf}, not against the wall clock, so a report is reproducible.
 *
 * @param assetId               DLAP identifier
 * @param assetUri              canonical URI (ADR-0047), or the older form of a row not yet re-keyed
 * @param assetName             display name
 * @param cloudProvider         provider ({@code AWS}, {@code GCP}, ...)
 * @param serviceType           asset kind ({@code GCS_BUCKET}, {@code BIGQUERY_TABLE}, ...)
 * @param assetType             free classification
 * @param region                normalized region
 * @param status                lifecycle status: ACTIVE, ARCHIVED, PENDING_DELETION or DELETED
 * @param ownerInfo             resolved owner (email or team), harvested from logs or entered
 * @param discoveredAt          when DLAP first saw the asset; an upper bound for its age
 * @param discoverySource       channel through which DLAP first learned of the asset
 * @param cloudCreatedAt        provider creation time of the current incarnation
 * @param cloudCreatedSource    evidence channel for {@code cloudCreatedAt}
 * @param ageDays               whole days from {@code cloudCreatedAt} to {@code asOf}; null when
 *                              the creation time is unknown or later than {@code asOf}
 * @param sizeBytes             latest observed size; null is unknown, 0 is a real size
 * @param sizeObservedAt        business time at which {@code sizeBytes} was true
 * @param lastAccessedAt        latest event time of a successful read; null when no read is recorded
 * @param lastAccessSource      evidence channel for {@code lastAccessedAt}
 * @param idleDays              whole days from {@code lastAccessedAt} to {@code asOf}; null when no
 *                              read is recorded or the last read is later than {@code asOf}. Never
 *                              "idle since creation": that claim needs read coverage (ADR-0046)
 * @param readCoverage          read-logging coverage of the asset ({@code FULL}, {@code PARTIAL},
 *                              {@code NONE}) from {@code dlap_assets.read_coverage} (COV-01); null
 *                              means unknown. Only {@code FULL} lets a reader call a missing read
 *                              "not read"; a null last read with any other coverage is not "never read"
 * @param readCoverageReasons   why the coverage is what it is: the gaps for {@code PARTIAL},
 *                              {@code NONE} and unknown (an unknown names the missing permission),
 *                              the evidence for {@code FULL}; null when no observation was applied
 * @param readCoverageObservedAt when the provider configuration behind {@code readCoverage} was
 *                              read, by the collector's clock (Q-475); null when no observation was
 *                              applied. A reader that needs a fresh proof compares it with
 *                              {@code asOf}: a {@code FULL} whose observation is old may be stale
 *                              (a collector run that failed as a whole leaves the last value)
 * @param readCoverageSince     first observation of the current unbroken run of {@code FULL}
 *                              observations: reads are known to be recorded from here on (Q-434,
 *                              Q-444); null unless {@code readCoverage} is {@code FULL}. An asset
 *                              with {@code FULL} coverage and no recorded read was "not read since"
 *                              this instant
 * @param lastModifiedAt        latest event time of a successful content write
 * @param lastPermissionChangeAt latest event time of a successful access-control change
 * @param recreatedAt           event time of a CREATE applied to a DELETED asset (name reuse)
 * @param retentionStatus       stored result of the last retention evaluation; UNKNOWN by default
 * @param retentionExpiresAt    the expiry the last evaluation found, exact or the proving bound
 * @param retentionEvaluatedAt  as-of instant of the last evaluation; compare with {@code asOf}
 *                              to see how old the result is
 * @param retentionDays         period of the deciding rule
 * @param retentionBasis        basis of the deciding rule
 * @param retentionSource       source of the deciding rule
 * @param retentionPolicyId     policy of the deciding rule, when the source is POLICY
 * @param legalHold             DLAP legal hold; while true the retention status is ON_HOLD
 * @param labels                names of the labels assigned to the asset, sorted
 * @param asOf                  the instant the derived values are computed against
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AssetActivityRowDTO(
        UUID assetId,
        String assetUri,
        String assetName,
        String cloudProvider,
        String serviceType,
        String assetType,
        String region,
        String status,
        String ownerInfo,
        Instant discoveredAt,
        String discoverySource,
        Instant cloudCreatedAt,
        String cloudCreatedSource,
        Long ageDays,
        Long sizeBytes,
        Instant sizeObservedAt,
        Instant lastAccessedAt,
        String lastAccessSource,
        Long idleDays,
        String readCoverage,
        List<String> readCoverageReasons,
        Instant readCoverageObservedAt,
        Instant readCoverageSince,
        Instant lastModifiedAt,
        Instant lastPermissionChangeAt,
        Instant recreatedAt,
        RetentionStatus retentionStatus,
        Instant retentionExpiresAt,
        Instant retentionEvaluatedAt,
        Integer retentionDays,
        RetentionBasis retentionBasis,
        String retentionSource,
        UUID retentionPolicyId,
        boolean legalHold,
        List<String> labels,
        Instant asOf) {

    /**
     * Makes the label list immutable and never null.
     *
     * @param labels label names; null means none
     */
    public AssetActivityRowDTO {
        labels = labels == null ? List.of() : List.copyOf(labels);
    }
}
