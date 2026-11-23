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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.model.RetentionStatus;
import com.dlap.reporting.client.ResilientCatalogClient.ActivityRequest;

/**
 * The validated query of the Asset Activity and Expiry report: filters, the report instant and
 * the paging of the JSON form.
 *
 * <p>Validation happens here, before any call to the catalog, for two reasons. A malformed value
 * becomes a 400 that names the parameter. And a bad request can never count as a failure of the
 * catalog circuit breaker. The accepted values are the ones the catalog accepts, so a value that
 * passes here is not rejected there.
 *
 * <p>{@code asOf} is resolved once, from the injected clock when the caller gave none, and sent
 * to the catalog with every page. All pages of one export and the manifest therefore agree on one
 * instant.
 *
 * @param provider        normalized provider names
 * @param kind            asset kinds, upper case
 * @param status          lifecycle statuses, upper case
 * @param lastReadBefore  upper bound of the last read, exclusive, as the caller gave it
 * @param lastReadAfter   lower bound of the last read, exclusive
 * @param lastReadKnown   whether a last read must exist (true) or must not (false); null for either
 * @param idleDaysMin     at least this many idle days before {@code asOf}; null when absent. Combined with
 *                        {@code lastReadBefore} when the request goes to the catalog
 * @param retentionStatus retention statuses, upper case
 * @param legalHold       hold filter; null for either
 * @param readCoverage    read coverage values, upper case
 * @param asOf            the report instant
 * @param page            zero-based page (JSON)
 * @param size            page size (JSON)
 * @param sort            {@code property,direction} (JSON)
 */
public record ExpiryReportQuery(
        List<String> provider,
        List<String> kind,
        List<String> status,
        Instant lastReadBefore,
        Instant lastReadAfter,
        Boolean lastReadKnown,
        Integer idleDaysMin,
        List<String> retentionStatus,
        Boolean legalHold,
        List<String> readCoverage,
        Instant asOf,
        int page,
        int size,
        String sort) {

    /** Default rows per page of the JSON form. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** The sort of an export: the catalog's unique key, so pages never repeat or drop a row. */
    public static final String EXPORT_SORT = "assetUri,asc";

    /** Properties the catalog sorts the activity report by. */
    static final Set<String> SORTABLE = Set.of(
            "assetUri", "assetName", "cloudProvider", "serviceType", "status", "discoveredAt", "cloudCreatedAt",
            "sizeBytes", "lastAccessedAt", "lastModifiedAt", "retentionStatus", "retentionExpiresAt");

    private static final Set<String> ASSET_STATUSES = Set.of("ACTIVE", "ARCHIVED", "PENDING_DELETION", "DELETED");
    private static final Set<String> READ_COVERAGE_VALUES = Set.of("FULL", "PARTIAL", "NONE", "UNKNOWN");
    private static final Pattern KIND = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final int MAX_FILTER_VALUES = 20;
    private static final int MAX_IDLE_DAYS = 36_500;
    private static final int MAX_PAGE = 1_000_000;

    /**
     * Validates the parameters.
     *
     * @param params      the query string as sent
     * @param clock       the time source for an absent {@code asOf}
     * @param maxPageSize the largest page size accepted, at most the catalog's own maximum
     * @return the validated query
     * @throws ExpiryReportRequestException when a value is malformed or out of range
     */
    public static ExpiryReportQuery parse(ExpiryReportParams params, Clock clock, int maxPageSize) {
        ExpiryReportParams p = params == null
                ? new ExpiryReportParams(null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                : params;

        Instant asOf = instant("asOf", p.asOf());
        if (asOf == null) {
            asOf = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        }

        List<String> providers = checked("provider", p.provider(), v -> AssetProvider.fromString(v)
                .map(AssetProvider::name)
                .orElseThrow(() -> new ExpiryReportRequestException("Unknown provider '" + shown(v) + "'")));
        List<String> kinds = checked("kind", p.kind(), v -> {
            String kind = v.strip().toUpperCase(Locale.ROOT);
            if (!KIND.matcher(kind).matches()) {
                throw new ExpiryReportRequestException(
                        "Malformed kind '" + shown(v) + "'; a kind is an upper-case name such as GCS_BUCKET");
            }
            return kind;
        });
        List<String> statuses = checked("status", p.status(), v -> {
            String status = v.strip().toUpperCase(Locale.ROOT);
            if (!ASSET_STATUSES.contains(status)) {
                throw new ExpiryReportRequestException(
                        "Unknown status '" + shown(v) + "'; allowed: " + new TreeSet<>(ASSET_STATUSES));
            }
            return status;
        });
        List<String> retentionStatuses = checked("retentionStatus", p.retentionStatus(), v -> {
            try {
                return RetentionStatus.valueOf(v.strip().toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException e) {
                throw new ExpiryReportRequestException("Unknown retentionStatus '" + shown(v) + "'; allowed: "
                        + Arrays.toString(RetentionStatus.values()));
            }
        });
        List<String> coverages = checked("readCoverage", p.readCoverage(), v -> {
            String coverage = v.strip().toUpperCase(Locale.ROOT);
            if (!READ_COVERAGE_VALUES.contains(coverage)) {
                throw new ExpiryReportRequestException("Unknown readCoverage '" + shown(v) + "'; allowed: "
                        + new TreeSet<>(READ_COVERAGE_VALUES));
            }
            return coverage;
        });

        Instant before = instant("lastReadBefore", p.lastReadBefore());
        Instant after = instant("lastReadAfter", p.lastReadAfter());
        Integer idleDaysMin = wholeNumber("idleDaysMin", p.idleDaysMin(), 0, MAX_IDLE_DAYS);

        Integer requestedPage = wholeNumber("page", p.page(), 0, MAX_PAGE);
        int page = requestedPage == null ? 0 : requestedPage;
        Integer requestedSize = wholeNumber("size", p.size(), 1, maxPageSize);
        int size = requestedSize == null ? Math.min(DEFAULT_PAGE_SIZE, maxPageSize) : requestedSize;

        return new ExpiryReportQuery(providers, kinds, statuses, before, after, bool("lastReadKnown", p.lastReadKnown()),
                idleDaysMin, retentionStatuses, bool("legalHold", p.legalHold()), coverages, asOf, page, size,
                sort(p.sort()));
    }

    /**
     * A copy for one page of an export: the same filters and instant, the export sort, the given
     * page and size.
     *
     * @param pageNumber zero-based page
     * @param pageSize   rows requested per page
     * @return the query for that page
     */
    ExpiryReportQuery forExportPage(int pageNumber, int pageSize) {
        return new ExpiryReportQuery(provider, kind, status, lastReadBefore, lastReadAfter, lastReadKnown, idleDaysMin,
                retentionStatus, legalHold, readCoverage, asOf, pageNumber, pageSize, EXPORT_SORT);
    }

    /**
     * The request for the catalog.
     *
     * @return the page, sort and filters in the catalog's terms
     */
    ActivityRequest toCatalogRequest() {
        return toCatalogRequest(null);
    }

    /**
     * The request for the catalog, as a keyset page when {@code after} is set (Q-445). A keyset
     * request is always page 0 in {@code assetUri} ascending order, which is the only order the
     * cursor means.
     *
     * @param after the {@code assetUri} of the last row already seen, or null for an offset request
     * @return the page, sort and filters in the catalog's terms
     */
    ActivityRequest toCatalogRequest(String after) {
        Instant before = effectiveLastReadBefore();
        return new ActivityRequest(after == null ? page : 0, size, after == null ? sort : EXPORT_SORT, provider, kind, status,
                before == null ? null : before.toString(),
                lastReadAfter == null ? null : lastReadAfter.toString(),
                lastReadKnown == null ? null : lastReadKnown.toString(),
                retentionStatus,
                legalHold == null ? null : legalHold.toString(),
                readCoverage,
                asOf.toString(),
                after);
    }

    /**
     * The upper bound of the last read that goes to the catalog. "Idle at least N days" is a last
     * read at or before {@code asOf} minus N days; the catalog's bound is exclusive, so one
     * nanosecond is added. Together with an explicit {@code lastReadBefore} the earlier bound wins.
     *
     * @return the exclusive upper bound, or null when neither filter is set
     */
    Instant effectiveLastReadBefore() {
        if (idleDaysMin == null) {
            return lastReadBefore;
        }
        Instant idleBound = asOf.minus(Duration.ofDays(idleDaysMin)).plusNanos(1);
        return lastReadBefore == null || idleBound.isBefore(lastReadBefore) ? idleBound : lastReadBefore;
    }

    /**
     * The filters the caller gave, normalized, in a fixed order, for the response and the manifest.
     * Absent filters are left out. {@code asOf} is not a filter and is reported separately.
     *
     * @return an ordered map
     */
    Map<String, Object> filterEcho() {
        Map<String, Object> echo = new LinkedHashMap<>();
        putIfAny(echo, "provider", provider);
        putIfAny(echo, "kind", kind);
        putIfAny(echo, "status", status);
        if (lastReadBefore != null) {
            echo.put("lastReadBefore", lastReadBefore.toString());
        }
        if (lastReadAfter != null) {
            echo.put("lastReadAfter", lastReadAfter.toString());
        }
        if (lastReadKnown != null) {
            echo.put("lastReadKnown", lastReadKnown);
        }
        if (idleDaysMin != null) {
            echo.put("idleDaysMin", idleDaysMin);
        }
        putIfAny(echo, "retentionStatus", retentionStatus);
        if (legalHold != null) {
            echo.put("legalHold", legalHold);
        }
        putIfAny(echo, "readCoverage", readCoverage);
        return echo;
    }

    private static void putIfAny(Map<String, Object> echo, String name, List<String> values) {
        if (!values.isEmpty()) {
            echo.put(name, values);
        }
    }

    private static String sort(String raw) {
        if (raw == null || raw.isBlank()) {
            return EXPORT_SORT;
        }
        String[] parts = raw.strip().split(",", -1);
        String property = parts[0].strip();
        String direction = parts.length > 1 ? parts[1].strip().toLowerCase(Locale.ROOT) : "asc";
        if (parts.length > 2 || !SORTABLE.contains(property)) {
            throw new ExpiryReportRequestException("Cannot sort the report by '" + shown(property) + "'; allowed: "
                    + new TreeSet<>(SORTABLE) + ", with an optional ,asc or ,desc");
        }
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new ExpiryReportRequestException("The sort direction must be asc or desc");
        }
        return property + "," + direction;
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notZulu) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException e) {
                throw new ExpiryReportRequestException(
                        name + " must be an ISO-8601 instant such as 2026-07-19T00:00:00Z");
            }
        }
    }

    private static Boolean bool(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw new ExpiryReportRequestException(name + " must be true or false");
    }

    private static Integer wholeNumber(String name, String value, int min, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int number;
        try {
            number = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new ExpiryReportRequestException(name + " must be a whole number from " + min + " to " + max);
        }
        if (number < min || number > max) {
            throw new ExpiryReportRequestException(name + " must be a whole number from " + min + " to " + max);
        }
        return number;
    }

    /** Checks one repeatable filter: blanks dropped, repeats collapsed, count bounded, each value validated. */
    private static List<String> checked(String name, List<String> values, Function<String, String> check) {
        if (values == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null) {
                continue;
            }
            // A parameter can be repeated, comma-separated, or both: the data binder does not split commas
            // when the parameter is repeated.
            for (String value : raw.split(",")) {
                if (value.isBlank()) {
                    continue;
                }
                out.add(check.apply(value));
                if (out.size() > MAX_FILTER_VALUES) {
                    throw new ExpiryReportRequestException(
                            "At most " + MAX_FILTER_VALUES + " values are accepted for " + name);
                }
            }
        }
        return List.copyOf(new ArrayList<>(out));
    }

    /** A caller's value as it appears in an error message: bounded, with control characters removed. */
    private static String shown(String value) {
        String text = value.length() > 40 ? value.substring(0, 40) + "..." : value;
        return text.replaceAll("\\p{Cntrl}", "?");
    }
}
