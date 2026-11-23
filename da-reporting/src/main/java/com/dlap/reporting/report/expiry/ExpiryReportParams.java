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

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The query string of the Asset Activity and Expiry report, exactly as the caller sent it. Every
 * value is text on purpose: a malformed number, boolean or time is rejected by
 * {@link ExpiryReportQuery} with a 400 and a message that names the parameter, and never reaches
 * Spring's converters, which would answer with a 500 in this service.
 *
 * @param provider        cloud providers, for example AWS or GCP; repeat the parameter or separate with commas
 * @param kind            asset kinds (service types), for example GCS_BUCKET or BIGQUERY_TABLE
 * @param status          lifecycle statuses: ACTIVE, ARCHIVED, PENDING_DELETION, DELETED
 * @param lastReadBefore  only assets last read strictly before this ISO-8601 instant
 * @param lastReadAfter   only assets last read strictly after this ISO-8601 instant
 * @param lastReadKnown   true: only assets with a recorded last read; false: only assets without one
 * @param idleDaysMin     only assets whose last recorded read is at least this many days before {@code asOf};
 *                        an asset with no recorded read never matches, because its idle time is unknown
 * @param retentionStatus stored retention status: UNKNOWN, NO_POLICY, RETAINED, DUE, ON_HOLD
 * @param legalHold       true: only assets on legal hold; false: only assets not held
 * @param readCoverage    read coverage: FULL, PARTIAL, NONE, or UNKNOWN for an asset with none recorded
 * @param asOf            the ISO-8601 instant the day counts are computed against; now when absent. Pin it to
 *                        reproduce a report
 * @param page            zero-based page number (JSON only); default 0
 * @param size            rows per page (JSON only); default 50
 * @param sort            sort as property or property,asc or property,desc (JSON only); default assetUri,asc
 */
public record ExpiryReportParams(
        @Schema(description = "Cloud providers, for example AWS or GCP; repeat or comma-separate")
        List<String> provider,
        @Schema(description = "Asset kinds (service types), for example GCS_BUCKET; repeat or comma-separate")
        List<String> kind,
        @Schema(description = "Lifecycle statuses: ACTIVE, ARCHIVED, PENDING_DELETION, DELETED")
        List<String> status,
        @Schema(description = "Only assets last read strictly before this ISO-8601 instant, for example 2026-07-19T00:00:00Z")
        String lastReadBefore,
        @Schema(description = "Only assets last read strictly after this ISO-8601 instant")
        String lastReadAfter,
        @Schema(description = "true: only assets with a recorded last read; false: only assets without one")
        String lastReadKnown,
        @Schema(description = "Only assets last read at least this many days before asOf; assets with no recorded read never match")
        String idleDaysMin,
        @Schema(description = "Stored retention status: UNKNOWN, NO_POLICY, RETAINED, DUE, ON_HOLD")
        List<String> retentionStatus,
        @Schema(description = "true: only assets on legal hold; false: only assets not held")
        String legalHold,
        @Schema(description = "Read coverage: FULL, PARTIAL, NONE, or UNKNOWN for none recorded")
        List<String> readCoverage,
        @Schema(description = "ISO-8601 instant the day counts are computed against; now when absent")
        String asOf,
        @Schema(description = "Zero-based page number (JSON only)", defaultValue = "0")
        String page,
        @Schema(description = "Rows per page, 1 to 200 (JSON only)", defaultValue = "50")
        String size,
        @Schema(description = "Sort: property or property,asc|desc (JSON only); default assetUri,asc")
        String sort) {
}
