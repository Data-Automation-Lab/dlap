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

package com.dlap.reporting.report.expiry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.dlap.reporting.client.CatalogServiceClient.ActivityRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the Asset Activity and Expiry report. The field order is the column order of the CSV
 * export, and the field names are its column names.
 *
 * <p>Every field except {@code assetUri}, {@code readSignalState}, {@code legalHold},
 * {@code labels} and {@code mode} can be {@code null}. A {@code null} (an empty CSV cell) is
 * unknown, never zero, stale or fresh. In particular a null {@code lastReadAt} is not "never
 * read": {@code readSignalState} says which of the five cases it is.
 *
 * @param assetUri             canonical URI of the asset (ADR-0047)
 * @param assetId              DLAP identifier, the join key to the catalog API
 * @param provider             cloud provider: AWS, GCP, AZURE, UNITY, DATABRICKS, SNOWFLAKE
 * @param type                 asset kind, for example GCS_BUCKET or BIGQUERY_TABLE
 * @param account              the provider account, project, subscription or workspace, when the
 *                             canonical URI carries it; null for a bucket
 * @param region               normalized region
 * @param name                 display name
 * @param owner                resolved owner (an email or a team), harvested from logs or entered
 * @param lifecycleStatus      ACTIVE, ARCHIVED, PENDING_DELETION or DELETED
 * @param sizeBytes            latest observed size in bytes; 0 is a real size
 * @param createdAt            provider creation time of the current incarnation
 * @param createdSource        evidence channel of {@code createdAt}
 * @param ageDays              whole days from {@code createdAt} to {@code asOf}
 * @param lastReadAt           event time of the latest successful read the logs recorded
 * @param lastReadSource       evidence channel of {@code lastReadAt}
 * @param idleDays             whole days from {@code lastReadAt} to {@code asOf}; null when no read is
 *                             recorded, because "idle since creation" needs complete read coverage
 * @param readSignalState      what the read signal allows to be said, see {@link ReadSignalState}
 * @param readCoverage         the read-logging coverage exactly as the catalog states it
 *                             (FULL, PARTIAL, NONE), or null when the catalog holds none
 * @param lastModifiedAt       event time of the latest successful write
 * @param retentionDays        period in days of the rule that decided the retention result
 * @param retentionBasis       basis of that rule: CREATED, LAST_MODIFIED, LAST_ACCESSED or FIXED_DATE
 * @param retentionSource      source of that rule: POLICY, LABEL_DEFAULT, PROVIDER or MANUAL
 * @param retentionExpiresAt   the expiry the last evaluation found (exact, or the bound that proves
 *                             the status)
 * @param retentionStatus      UNKNOWN, NO_POLICY, RETAINED, DUE or ON_HOLD
 * @param retentionEvaluatedAt as-of instant of the last evaluation; compare with {@code asOf}
 * @param legalHold            DLAP legal hold; while true the retention status is ON_HOLD
 * @param labels               label names, sorted
 * @param mode                 deployment mode of the report: {@code live}, or {@code shadow} when
 *                             the deployment only observes and acts on nothing
 * @param readCoverageSince    first observation of the current unbroken run of FULL coverage: reads
 *                             are known to be recorded from here on (Q-434, Q-444); null when the
 *                             coverage is not FULL or the catalog holds no start. Added in 1.1
 * @param readCoverageObservedAt when the catalog last read the provider's logging configuration
 *                             behind {@code readCoverage} (Q-475); an old value shows a FULL that
 *                             collectors have not confirmed lately. Added in 1.1
 * @param readSignalText       "not read since YYYY-MM-DD" for a {@code NOT_READ} asset whose
 *                             coverage start is known, otherwise null (see
 *                             {@link ReadSignalState#text}). Added in 1.1
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"assetUri", "assetId", "provider", "type", "account", "region", "name", "owner",
        "lifecycleStatus", "sizeBytes", "createdAt", "createdSource", "ageDays", "lastReadAt", "lastReadSource",
        "idleDays", "readSignalState", "readCoverage", "lastModifiedAt", "retentionDays", "retentionBasis",
        "retentionSource", "retentionExpiresAt", "retentionStatus", "retentionEvaluatedAt", "legalHold",
        "labels", "mode", "readCoverageSince", "readCoverageObservedAt", "readSignalText"})
public record ExpiryReportRow(
        String assetUri,
        UUID assetId,
        String provider,
        String type,
        String account,
        String region,
        String name,
        String owner,
        String lifecycleStatus,
        Long sizeBytes,
        Instant createdAt,
        String createdSource,
        Long ageDays,
        Instant lastReadAt,
        String lastReadSource,
        Long idleDays,
        @Schema(description = "READ, NOT_READ (only with FULL read coverage), PARTIAL_COVERAGE, NO_COVERAGE or UNKNOWN")
        ReadSignalState readSignalState,
        @Schema(description = "Read-logging coverage as the catalog states it: FULL, PARTIAL, NONE, or null")
        String readCoverage,
        Instant lastModifiedAt,
        Integer retentionDays,
        String retentionBasis,
        String retentionSource,
        Instant retentionExpiresAt,
        String retentionStatus,
        Instant retentionEvaluatedAt,
        boolean legalHold,
        List<String> labels,
        String mode,
        @Schema(description = "First observation of the current unbroken run of FULL read coverage, or null")
        Instant readCoverageSince,
        @Schema(description = "When the catalog last read the logging configuration behind readCoverage, or null")
        Instant readCoverageObservedAt,
        @Schema(description = "'not read since YYYY-MM-DD' for a NOT_READ asset whose coverage start is known, else null")
        String readSignalText) {

    /** The column names in file order. {@code ExpiryReportRowTest} pins them to the record components, so the CSV and the JSON cannot drift apart. */
    public static final List<String> COLUMNS = List.of(
            "assetUri", "assetId", "provider", "type", "account", "region", "name", "owner",
            "lifecycleStatus", "sizeBytes", "createdAt", "createdSource", "ageDays", "lastReadAt", "lastReadSource",
            "idleDays", "readSignalState", "readCoverage", "lastModifiedAt", "retentionDays", "retentionBasis",
            "retentionSource", "retentionExpiresAt", "retentionStatus", "retentionEvaluatedAt", "legalHold",
            "labels", "mode", "readCoverageSince", "readCoverageObservedAt", "readSignalText");

    /**
     * Makes the label list immutable and never null.
     *
     * @param assetUri             canonical URI
     * @param assetId              identifier
     * @param provider             provider
     * @param type                 kind
     * @param account              account
     * @param region               region
     * @param name                 name
     * @param owner                owner
     * @param lifecycleStatus      lifecycle status
     * @param sizeBytes            size
     * @param createdAt            creation time
     * @param createdSource        creation channel
     * @param ageDays              age
     * @param lastReadAt           last read
     * @param lastReadSource       read channel
     * @param idleDays             idle days
     * @param readSignalState      read signal state
     * @param readCoverage         read coverage
     * @param lastModifiedAt       last write
     * @param retentionDays        rule period
     * @param retentionBasis       rule basis
     * @param retentionSource      rule source
     * @param retentionExpiresAt   expiry
     * @param retentionStatus      retention status
     * @param retentionEvaluatedAt evaluation instant
     * @param legalHold            hold
     * @param labels               labels; null means none
     * @param mode                 mode label
     * @param readCoverageSince    start of the current FULL run
     * @param readCoverageObservedAt coverage observation time
     * @param readSignalText       the sentence under the read signal state
     */
    public ExpiryReportRow {
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    /**
     * Builds the report row of a catalog row. The only derived values are the account (read from
     * the canonical URI), the read signal state and its sentence; every other value is the
     * catalog's, unchanged.
     *
     * @param row  the catalog's activity row
     * @param mode the deployment mode label
     * @return the report row
     */
    static ExpiryReportRow from(ActivityRow row, String mode) {
        ReadSignalState state = ReadSignalState.of(row.lastAccessedAt(), row.readCoverage());
        return new ExpiryReportRow(
                row.assetUri(),
                row.assetId(),
                row.cloudProvider(),
                row.serviceType(),
                AssetScope.accountOf(row.assetUri()).orElse(null),
                row.region(),
                row.assetName(),
                row.ownerInfo(),
                row.status(),
                row.sizeBytes(),
                row.cloudCreatedAt(),
                row.cloudCreatedSource(),
                row.ageDays(),
                row.lastAccessedAt(),
                row.lastAccessSource(),
                row.idleDays(),
                state,
                row.readCoverage(),
                row.lastModifiedAt(),
                row.retentionDays(),
                row.retentionBasis(),
                row.retentionSource(),
                row.retentionExpiresAt(),
                row.retentionStatus(),
                row.retentionEvaluatedAt(),
                row.legalHold(),
                row.labels(),
                mode,
                row.readCoverageSince(),
                row.readCoverageObservedAt(),
                ReadSignalState.text(state, row.readCoverageSince()));
    }

    /**
     * The cells of the CSV row, in {@link #COLUMNS} order. Instants are ISO-8601 in UTC, a null is
     * an empty cell, and the labels are joined with a semicolon (a semicolon inside a label name
     * is written as a comma, in the CSV only).
     *
     * @return the cell values, ready for {@code CsvWriter.row}
     */
    List<Object> csvCells() {
        List<Object> cells = new ArrayList<>(COLUMNS.size());
        cells.add(assetUri);
        cells.add(assetId);
        cells.add(provider);
        cells.add(type);
        cells.add(account);
        cells.add(region);
        cells.add(name);
        cells.add(owner);
        cells.add(lifecycleStatus);
        cells.add(sizeBytes);
        cells.add(createdAt);
        cells.add(createdSource);
        cells.add(ageDays);
        cells.add(lastReadAt);
        cells.add(lastReadSource);
        cells.add(idleDays);
        cells.add(readSignalState);
        cells.add(readCoverage);
        cells.add(lastModifiedAt);
        cells.add(retentionDays);
        cells.add(retentionBasis);
        cells.add(retentionSource);
        cells.add(retentionExpiresAt);
        cells.add(retentionStatus);
        cells.add(retentionEvaluatedAt);
        cells.add(legalHold);
        cells.add(labels.isEmpty() ? null : String.join(";", labels.stream().map(l -> l.replace(';', ',')).toList()));
        cells.add(mode);
        cells.add(readCoverageSince);
        cells.add(readCoverageObservedAt);
        cells.add(readSignalText);
        return cells;
    }
}
