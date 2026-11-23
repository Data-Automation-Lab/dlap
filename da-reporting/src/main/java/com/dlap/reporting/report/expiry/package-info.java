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

/**
 * The Asset Activity and Expiry report (RPT-01, contract version 1.1).
 *
 * <h2>What it is</h2>
 * One row per asset: identity, age, size, last read, last write and retention state, as of one
 * instant ({@code asOf}). The catalog stores and evaluates all of it
 * ({@code GET /api/v1/catalog/assets/activity}, CAT-02b and CAT-03); this package serves it
 * to people, adds the access rules, and exports it.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/v1/reporting/reports/expiry}: JSON, paged ({@code page}, {@code size} 1 to 200,
 *       {@code sort}).</li>
 *   <li>{@code GET /api/v1/reporting/reports/expiry.csv}: every matching row, streamed, in
 *       {@code assetUri} order.</li>
 *   <li>{@code GET /api/v1/reporting/reports/expiry/exports/{exportId}/manifest}: the manifest of a
 *       completed export (id from the {@code X-Export-Id} response header).</li>
 * </ul>
 * Roles: ADMIN, CDO, DIRECTOR, POLICY_MANAGER, DATA_ENGINEER, DATA_SCIENTIST, DATA_ANALYST, AUDITOR
 * and VIEWER (every tier may view reports). Nothing else, including the machine principal.
 *
 * <h2>Filters</h2>
 * {@code provider}, {@code kind}, {@code status}, {@code lastReadBefore}, {@code lastReadAfter},
 * {@code lastReadKnown}, {@code idleDaysMin}, {@code retentionStatus}, {@code legalHold},
 * {@code readCoverage} and {@code asOf}. Repeat a parameter or separate values with commas. Time
 * values are ISO-8601 instants. An asset whose last read is unknown matches no time filter: unknown
 * is never before or after anything. {@code asOf} defaults to now (to the second) and is sent to
 * the catalog with every page, so one export has one instant. Pin it to reproduce a report.
 *
 * <h2>Columns</h2>
 * The CSV header and the JSON field names are the same, in this order.
 * <pre>
 * identity    assetUri, assetId, provider, type, account, region, name, owner, lifecycleStatus
 * activity    sizeBytes, createdAt, createdSource, ageDays, lastReadAt, lastReadSource, idleDays,
 *             readSignalState, readCoverage, lastModifiedAt
 * retention   retentionDays, retentionBasis, retentionSource, retentionExpiresAt, retentionStatus,
 *             retentionEvaluatedAt, legalHold, labels
 * mode        mode
 * coverage    readCoverageSince, readCoverageObservedAt, readSignalText   (added in 1.1, after mode so
 *             that every 1.0 column keeps its position)
 * </pre>
 * Every value is the catalog's, unchanged, except {@code account}, {@code readSignalState},
 * {@code readSignalText} and {@code mode}:
 * <ul>
 *   <li>{@code account} is read from the canonical URI when it carries one: the AWS account of an
 *       ARN, the GCP project of a BigQuery or Cloud SQL identifier, the Azure storage account or
 *       subscription, the Unity Catalog metastore, the Databricks workspace, the Snowflake
 *       account. A bucket has none; the cell is empty.</li>
 *   <li>{@code mode} is {@code dlap.mode}: {@code live} by default, {@code shadow} for a deployment
 *       that only observes. The report is read-only in either mode.</li>
 * </ul>
 *
 * <h2>Unknown is not "not read"</h2>
 * A null or empty value is unknown, never zero, stale or fresh. {@code idleDays} is empty when no read
 * is recorded: "idle since creation" is a claim that needs complete read logging. {@code readSignalState}
 * says what a missing last read means, and {@code readCoverage} shows the catalog's coverage text
 * unchanged:
 * <pre>
 * READ              a read is recorded (with PARTIAL or NONE coverage it is the latest the logs saw)
 * NOT_READ          no read recorded, and readCoverage is FULL: the asset was not read
 * PARTIAL_COVERAGE  no read recorded, readCoverage is PARTIAL: reads may have gone unseen
 * NO_COVERAGE       no read recorded, readCoverage is NONE: read logging does not watch this asset
 * UNKNOWN           no read recorded, and the catalog holds no coverage (null) or one this report does not know
 * </pre>
 * {@code readCoverageSince} is the first observation of the current unbroken run of FULL coverage:
 * reads are known to be recorded from there on (Q-434, Q-444). {@code readCoverageObservedAt} is when
 * the catalog last read the provider's logging configuration (Q-475); a FULL with an old value has not
 * been confirmed lately, because a collector run that fails as a whole leaves the last value in place.
 * For a {@code NOT_READ} row whose start is known, {@code readSignalText} is
 * {@code "not read since YYYY-MM-DD"} (the UTC day of {@code readCoverageSince}); for every other row,
 * and for a {@code NOT_READ} row whose start the catalog does not hold, it is empty, because the
 * report cannot say since when.
 *
 * <p>The words "not read" appear for FULL coverage only. Known gaps in what a read is (a cached BigQuery
 * result is not recorded; an Azure properties call is not a read) are in the log specs, not in the
 * state.
 *
 * <h2>CSV format</h2>
 * UTF-8 without a byte order mark, LF line ends, RFC 4180 quoting. A text cell that starts with
 * {@code = + - @}, a tab or a carriage return is prefixed with a single quote, so a spreadsheet
 * cannot run it as a formula. Instants are ISO-8601 in UTC. {@code labels} are joined with a
 * semicolon (a semicolon inside a label is written as a comma). Empty cells are unknown values.
 *
 * <h2>Manifest</h2>
 * Each completed export has a manifest: {@code sha256} and {@code bytes} of the exact file,
 * {@code rows}, {@code columns}, {@code asOf}, {@code mode}, the {@code filters} and the source. Check a
 * download with {@code sha256sum}. The manifest is kept in the memory of the instance that served the
 * export (24 hours, 500 manifests, both settable); the SHA-256 is also logged at INFO.
 *
 * <h2>Failure rules</h2>
 * <ul>
 *   <li>The report never returns a partial or empty result for a catalog it could not read: 503.</li>
 *   <li>A malformed filter, time, page or sort is 400, with the parameter named. A result larger than
 *       {@code dlap.reporting.expiry.export.max-rows} (250000) is 413 for the CSV.</li>
 *   <li>An export that fails after it started (the catalog fails at a later page, a row repeats, the
 *       catalog stops advancing its cursor, or the export grows past the row limit) ends with a line
 *       that opens an unterminated quoted field, so a CSV reader reports an error, and it gets no
 *       manifest. Run it again.</li>
 * </ul>
 *
 * <h2>Paging of an export</h2>
 * The CSV walks the catalog by keyset (Q-445): the first request is page 0 in {@code assetUri} order,
 * and each next request sends {@code after=<nextAfter of the previous page>} until {@code nextAfter}
 * is null. A write to the catalog during the export cannot shift a page, so no asset that stays in the
 * catalog is skipped or repeated. The rows are not a snapshot: an asset written ahead of the cursor is
 * exported and one written behind it is not, so {@code rows} in the manifest can differ from
 * {@code source.totalElements}, the count announced at the start. The repeat check stays as a guard.
 * The manifest marks the walk with {@code source.paging = "keyset"}.
 */
package com.dlap.reporting.report.expiry;
