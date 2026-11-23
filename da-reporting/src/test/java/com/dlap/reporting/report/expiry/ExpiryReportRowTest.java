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

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.reporting.client.CatalogServiceClient.ActivityRow;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

class ExpiryReportRowTest {

    @Test
    void theColumnListIsTheRecordComponentListInOrder() {
        List<String> components = Arrays.stream(ExpiryReportRow.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(ExpiryReportRow.COLUMNS).containsExactlyElementsOf(components);
        assertThat(ExpiryReportRow.class.getAnnotation(JsonPropertyOrder.class).value())
                .containsExactlyElementsOf(components);
    }

    @Test
    void everyRowHasOneCellPerColumn() {
        ExpiryReportRow row = ExpiryReportRow.from(ExpiryFixtures.all().content().get(3), "live");

        assertThat(row.csvCells()).hasSameSizeAs(ExpiryReportRow.COLUMNS);
    }

    @Test
    void theDerivedColumnsAreTheOnlyOnesTheServiceAdds() {
        ActivityRow catalogRow = ExpiryFixtures.all().content().get(0);

        ExpiryReportRow row = ExpiryReportRow.from(catalogRow, "shadow");

        assertThat(row.account()).isEqualTo("111122223333");
        assertThat(row.readSignalState()).isEqualTo(ReadSignalState.NO_COVERAGE);
        assertThat(row.mode()).isEqualTo("shadow");
        assertThat(row.assetUri()).isEqualTo(catalogRow.assetUri());
        assertThat(row.ageDays()).isEqualTo(catalogRow.ageDays());
        assertThat(row.idleDays()).isNull();
        assertThat(row.readCoverage()).isEqualTo("NONE");
    }

    @Test
    void anIdleTimeIsNeverInventedForAnAssetWithNoRecordedRead() {
        ActivityRow neverSeen = new ActivityRow(UUID.randomUUID(), "bq://p/d/t", "t", "GCP", "BIGQUERY_TABLE", "US",
                "ACTIVE", null, Instant.parse("2024-01-01T00:00:00Z"), "CRAWLER", 1000L, null, null, null, null,
                "FULL", null, null, null, "UNKNOWN", null, null, null, null, null, false, List.of(),
                Instant.parse("2026-10-19T12:00:00Z"));

        ExpiryReportRow row = ExpiryReportRow.from(neverSeen, "live");

        assertThat(row.idleDays()).isNull();
        assertThat(row.lastReadAt()).isNull();
        assertThat(row.readSignalState()).isEqualTo(ReadSignalState.NOT_READ);
    }

    @Test
    void aSemicolonInALabelIsWrittenAsACommaInTheCsvOnly() {
        ExpiryReportRow row = ExpiryReportRow.from(ExpiryFixtures.all().content().get(1), "live");

        assertThat(row.labels()).containsExactly("a;b");
        assertThat(row.csvCells().get(ExpiryReportRow.COLUMNS.indexOf("labels"))).isEqualTo("a,b");
    }

    private static ActivityRow row(Instant lastRead, String coverage, Instant observedAt, Instant since) {
        return new ActivityRow(UUID.randomUUID(), "bq://p/d/t", "t", "GCP", "BIGQUERY_TABLE", "US", "ACTIVE", null,
                Instant.parse("2024-01-01T00:00:00Z"), "CRAWLER", 1000L, null, lastRead, null, null, coverage,
                observedAt, since, null, "UNKNOWN", null, null, null, null, null, false, List.of(),
                Instant.parse("2026-10-19T12:00:00Z"));
    }

    @Test
    void aFullAssetWithNoRecordedReadPrintsNotReadSinceTheCoverageStart() {
        ExpiryReportRow row = ExpiryReportRow.from(row(null, "FULL", Instant.parse("2026-10-19T06:41:00Z"),
                Instant.parse("2026-09-20T23:59:59Z")), "live");

        assertThat(row.readSignalState()).isEqualTo(ReadSignalState.NOT_READ);
        assertThat(row.readSignalText()).isEqualTo("not read since 2026-09-20");
        assertThat(row.readCoverageSince()).isEqualTo(Instant.parse("2026-09-20T23:59:59Z"));
        assertThat(row.readCoverageObservedAt()).isEqualTo(Instant.parse("2026-10-19T06:41:00Z"));
        assertThat(row.csvCells().get(ExpiryReportRow.COLUMNS.indexOf("readSignalText")))
                .isEqualTo("not read since 2026-09-20");
    }

    @Test
    void theCoverageStartIsNeverInventedForAFullAssetThatHasNone() {
        ExpiryReportRow row = ExpiryReportRow.from(row(null, "FULL", Instant.parse("2026-10-19T06:41:00Z"), null),
                "live");

        assertThat(row.readSignalState()).isEqualTo(ReadSignalState.NOT_READ);
        assertThat(row.readSignalText()).as("without a start the report cannot say since when").isNull();
        assertThat(row.readCoverageSince()).isNull();
    }

    @Test
    void noSentenceIsPrintedForAnAssetThatWasReadOrIsNotFullyCovered() {
        Instant since = Instant.parse("2026-09-20T06:41:00Z");
        Instant observed = Instant.parse("2026-10-19T06:41:00Z");

        assertThat(ExpiryReportRow.from(row(Instant.parse("2026-10-01T00:00:00Z"), "FULL", observed, since), "live")
                .readSignalText()).as("a read is recorded").isNull();
        assertThat(ExpiryReportRow.from(row(null, "PARTIAL", observed, since), "live").readSignalText())
                .as("partial coverage").isNull();
        assertThat(ExpiryReportRow.from(row(null, "NONE", observed, since), "live").readSignalText())
                .as("no coverage").isNull();
        assertThat(ExpiryReportRow.from(row(null, null, null, null), "live").readSignalText())
                .as("coverage unknown").isNull();
    }
}
