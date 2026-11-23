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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.reporting.client.ResilientCatalogClient.ActivityRequest;

class ExpiryReportQueryTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00.987Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static ExpiryReportParams params(String provider, String lastReadBefore, String idleDaysMin, String asOf,
            String page, String size, String sort) {
        return new ExpiryReportParams(provider == null ? null : List.of(provider), null, null, lastReadBefore, null, null,
                idleDaysMin, null, null, null, asOf, page, size, sort);
    }

    private static ExpiryReportQuery parse(ExpiryReportParams p) {
        return ExpiryReportQuery.parse(p, CLOCK, 200);
    }

    @Test
    void anEmptyQueryAsksForTheFirstPageOfFiftySortedByUriAsOfNowToTheSecond() {
        ExpiryReportQuery q = parse(null);

        assertThat(q.asOf()).isEqualTo(Instant.parse("2026-10-19T12:00:00Z"));
        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(50);
        assertThat(q.sort()).isEqualTo("assetUri,asc");
        ActivityRequest request = q.toCatalogRequest();
        assertThat(request.provider()).isEmpty();
        assertThat(request.lastReadBefore()).isNull();
        assertThat(request.asOf()).isEqualTo("2026-10-19T12:00:00Z");
        assertThat(q.filterEcho()).isEmpty();
    }

    @Test
    void normalizesProviderAliasesAndCollapsesRepeats() {
        ExpiryReportParams p = new ExpiryReportParams(List.of("gcp", "Google", " aws ", ""), List.of("gcs_bucket"),
                List.of("active"), null, null, "TRUE", null, List.of("due", "on_hold"), "False", List.of("full", "unknown"),
                "2026-07-19T00:00:00Z", "2", "25", "lastAccessedAt,desc");

        ExpiryReportQuery q = parse(p);

        assertThat(q.provider()).containsExactly("GCP", "AWS");
        assertThat(q.kind()).containsExactly("GCS_BUCKET");
        assertThat(q.status()).containsExactly("ACTIVE");
        assertThat(q.retentionStatus()).containsExactly("DUE", "ON_HOLD");
        assertThat(q.readCoverage()).containsExactly("FULL", "UNKNOWN");
        assertThat(q.lastReadKnown()).isTrue();
        assertThat(q.legalHold()).isFalse();
        assertThat(q.asOf()).isEqualTo(Instant.parse("2026-07-19T00:00:00Z"));
        assertThat(q.page()).isEqualTo(2);
        assertThat(q.size()).isEqualTo(25);
        assertThat(q.sort()).isEqualTo("lastAccessedAt,desc");
    }

    @Test
    void aFilterCanBeRepeatedCommaSeparatedOrBoth() {
        ExpiryReportParams p = new ExpiryReportParams(List.of("gcp,aws", "azure", "GCP", " , "), null, null, null, null,
                null, null, null, null, List.of("none,partial"), null, null, null, null);

        ExpiryReportQuery q = parse(p);

        assertThat(q.provider()).containsExactly("GCP", "AWS", "AZURE");
        assertThat(q.readCoverage()).containsExactly("NONE", "PARTIAL");
    }

    @Test
    void idleDaysMinBecomesAnExclusiveLastReadBoundOneNanosecondPastAsOfMinusTheDays() {
        ExpiryReportQuery q = parse(params(null, null, "180", "2026-10-19T12:00:00Z", null, null, null));

        assertThat(q.toCatalogRequest().lastReadBefore()).isEqualTo("2026-04-22T12:00:00.000000001Z");
        assertThat(q.filterEcho()).containsEntry("idleDaysMin", 180).doesNotContainKey("lastReadBefore");
    }

    @Test
    void idleDaysMinAndAnExplicitBoundCombineByTheEarlierOne() {
        ExpiryReportQuery earlierExplicit = parse(
                params(null, "2026-01-01T00:00:00Z", "30", "2026-10-19T12:00:00Z", null, null, null));
        ExpiryReportQuery earlierIdle = parse(
                params(null, "2026-10-01T00:00:00Z", "30", "2026-10-19T12:00:00Z", null, null, null));

        assertThat(earlierExplicit.toCatalogRequest().lastReadBefore()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(earlierIdle.toCatalogRequest().lastReadBefore()).isEqualTo("2026-09-19T12:00:00.000000001Z");
        assertThat(earlierExplicit.filterEcho()).containsEntry("lastReadBefore", "2026-01-01T00:00:00Z");
    }

    @Test
    void acceptsAnOffsetInstant() {
        ExpiryReportQuery q = parse(params(null, null, null, "2026-10-19T14:00:00+02:00", null, null, null));

        assertThat(q.asOf()).isEqualTo(Instant.parse("2026-10-19T12:00:00Z"));
    }

    @Test
    void anExportKeepsTheFiltersAndTheInstantAndTakesTheExportSort() {
        ExpiryReportQuery q = parse(params("gcp", null, null, "2026-10-19T12:00:00Z", "7", "10", "sizeBytes,desc"));

        ExpiryReportQuery page = q.forExportPage(3, 200);

        assertThat(page.provider()).containsExactly("GCP");
        assertThat(page.asOf()).isEqualTo(q.asOf());
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(200);
        assertThat(page.sort()).isEqualTo("assetUri,asc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"yesterday", "2026-13-01T00:00:00Z", "2026-10-19", "1760875200"})
    void rejectsAMalformedTimeAndNamesTheParameter(String value) {
        assertThatThrownBy(() -> parse(params(null, value, null, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class)
                .hasMessageContaining("lastReadBefore");
        assertThatThrownBy(() -> parse(params(null, null, null, value, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class)
                .hasMessageContaining("asOf");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "abc", "1.5", "36501", "99999999999"})
    void rejectsABadIdleDaysMin(String value) {
        assertThatThrownBy(() -> parse(params(null, null, value, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class)
                .hasMessageContaining("idleDaysMin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "201", "many"})
    void rejectsAPageSizeOutsideOneToTwoHundred(String value) {
        assertThatThrownBy(() -> parse(params(null, null, null, null, null, value, null)))
                .isInstanceOf(ExpiryReportRequestException.class)
                .hasMessageContaining("size");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "x", "1000001"})
    void rejectsABadPage(String value) {
        assertThatThrownBy(() -> parse(params(null, null, null, null, value, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class)
                .hasMessageContaining("page");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "assetUri;drop", "assetUri,sideways", "assetUri,asc,extra", "labels", ",asc"})
    void rejectsASortOutsideTheCatalogsSortableProperties(String sort) {
        assertThatThrownBy(() -> parse(params(null, null, null, null, null, null, sort)))
                .isInstanceOf(ExpiryReportRequestException.class)
                .hasMessageContaining("sort");
    }

    @Test
    void rejectsUnknownFilterValuesWithTheAllowedOnesInTheMessage() {
        assertThatThrownBy(() -> parse(new ExpiryReportParams(List.of("mars"), null, null, null, null, null, null,
                null, null, null, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class).hasMessageContaining("provider");
        assertThatThrownBy(() -> parse(new ExpiryReportParams(null, List.of("gcs bucket"), null, null, null, null, null,
                null, null, null, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class).hasMessageContaining("kind");
        assertThatThrownBy(() -> parse(new ExpiryReportParams(null, null, List.of("GONE"), null, null, null, null,
                null, null, null, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class).hasMessageContaining("ACTIVE");
        assertThatThrownBy(() -> parse(new ExpiryReportParams(null, null, null, null, null, null, null,
                List.of("stale"), null, null, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class).hasMessageContaining("DUE");
        assertThatThrownBy(() -> parse(new ExpiryReportParams(null, null, null, null, null, null, null,
                null, null, List.of("MOST"), null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class).hasMessageContaining("PARTIAL");
        assertThatThrownBy(() -> parse(new ExpiryReportParams(null, null, null, null, null, "maybe", null,
                null, null, null, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class).hasMessageContaining("lastReadKnown");
        assertThatThrownBy(() -> parse(new ExpiryReportParams(null, null, null, null, null, null, null,
                null, "1", null, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class).hasMessageContaining("legalHold");
    }

    @Test
    void aValueInAnErrorMessageIsBoundedAndHasNoControlCharacters() {
        String hostile = "x".repeat(500) + "\r\nInjected: header";

        assertThatThrownBy(() -> parse(new ExpiryReportParams(List.of(hostile), null, null, null, null, null, null,
                null, null, null, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("\r").doesNotContain("\n");
                    assertThat(e.getMessage().length()).isLessThan(120);
                });
    }

    @Test
    void boundsTheNumberOfValuesOfOneFilter() {
        List<String> many = IntStream.range(0, 25).mapToObj(i -> "KIND_" + i).toList();

        assertThatThrownBy(() -> parse(new ExpiryReportParams(null, many, null, null, null, null, null,
                null, null, null, null, null, null, null)))
                .isInstanceOf(ExpiryReportRequestException.class)
                .hasMessageContaining("At most 20");
    }

    @Test
    void theDefaultPageSizeNeverExceedsTheConfiguredMaximum() {
        ExpiryReportQuery q = ExpiryReportQuery.parse(null, CLOCK, 20);

        assertThat(q.size()).isEqualTo(20);
    }
}
