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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dlap.reporting.client.CatalogServiceClient.ActivityPage;
import com.dlap.reporting.client.CatalogServiceClient.ActivityRow;
import com.dlap.reporting.client.ResilientCatalogClient;
import com.dlap.reporting.client.ResilientCatalogClient.ActivityRequest;
import com.dlap.reporting.export.ExportManifest;
import com.dlap.reporting.export.ExportManifestRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The report service against a stubbed catalog: the row contract, the export stream and its
 * manifest, and every way an export can fail. The catalog pages are the fixture files, in the wire
 * shape of the catalog's activity endpoint.
 */
class ExpiryReportServiceTest {

    private static final UUID EXPORT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private ResilientCatalogClient catalog;
    private ExportManifestRegistry registry;
    private SimpleMeterRegistry meters;
    private ExpiryReportService service;

    @BeforeEach
    void setUp() {
        catalog = mock(ResilientCatalogClient.class);
        registry = new ExportManifestRegistry(ExpiryFixtures.CLOCK, Duration.ofHours(24), 10);
        meters = new SimpleMeterRegistry();
        service = new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, meters, "live", 2, 100,
                () -> EXPORT_ID);
        // Null-safe: re-stubbing with when(...) calls the previous answer once with a null matcher argument.
        when(catalog.getAssetActivity(any())).thenAnswer(inv -> inv.getArgument(0) == null
                ? null
                : ExpiryFixtures.keyset((ActivityRequest) inv.getArgument(0)));
    }

    private ExpiryReportQuery query(String asOf) {
        return service.parse(new ExpiryReportParams(null, null, null, null, null, null, null, null, null, null,
                asOf, null, null, null));
    }

    private double count(String outcome) {
        var counter = meters.find("dlap_reporting_expiry_exports_total").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---- JSON page ----------------------------------------------------------------------------

    @Test
    void pageMapsEveryCatalogRowAndPinsTheInstantItSendsToTheCatalog() {
        when(catalog.getAssetActivity(any())).thenReturn(ExpiryFixtures.all());

        ExpiryReportPage page = service.page(service.parse(new ExpiryReportParams(List.of("gcp"), null, null, null,
                null, null, "30", null, null, List.of("partial"), "2026-10-19T12:00:00Z", "0", "2", null)));

        ArgumentCaptor<ActivityRequest> sent = ArgumentCaptor.forClass(ActivityRequest.class);
        verify(catalog).getAssetActivity(sent.capture());
        assertThat(sent.getValue().asOf()).isEqualTo("2026-10-19T12:00:00Z");
        assertThat(sent.getValue().provider()).containsExactly("GCP");
        assertThat(sent.getValue().readCoverage()).containsExactly("PARTIAL");
        assertThat(sent.getValue().lastReadBefore()).isEqualTo("2026-09-19T12:00:00.000000001Z");
        assertThat(sent.getValue().sort()).isEqualTo("assetUri,asc");

        assertThat(page.report()).isEqualTo("asset-activity-expiry");
        assertThat(page.contractVersion()).isEqualTo("1.1");
        assertThat(page.mode()).isEqualTo("live");
        assertThat(page.asOf()).isEqualTo(ExpiryFixtures.AS_OF);
        assertThat(page.filters()).containsOnlyKeys("provider", "idleDaysMin", "readCoverage");
        assertThat(page.page().totalElements()).isEqualTo(5);
        assertThat(page.page().size()).isEqualTo(50); // the size the catalog applied, as it reports it
        assertThat(page.items()).extracting(ExpiryReportRow::readSignalState).containsExactly(
                ReadSignalState.NO_COVERAGE, ReadSignalState.PARTIAL_COVERAGE, ReadSignalState.NOT_READ,
                ReadSignalState.READ, ReadSignalState.UNKNOWN);
    }

    @Test
    void noRowWithoutFullCoverageIsEverNotRead() {
        when(catalog.getAssetActivity(any())).thenReturn(ExpiryFixtures.all());

        ExpiryReportPage page = service.page(query("2026-10-19T12:00:00Z"));

        for (ExpiryReportRow row : page.items()) {
            if (row.lastReadAt() == null && !"FULL".equals(row.readCoverage())) {
                assertThat(row.readSignalState()).as(row.assetUri()).isNotEqualTo(ReadSignalState.NOT_READ);
                assertThat(row.idleDays()).as(row.assetUri()).isNull();
            }
        }
        assertThat(page.items()).filteredOn(r -> r.readSignalState() == ReadSignalState.NOT_READ)
                .extracting(ExpiryReportRow::readCoverage).containsOnly("FULL");
    }

    @Test
    void theShadowModeLabelIsOnEveryRowAndTheEnvelope() {
        ExpiryReportService shadow = new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, null, " Shadow ", 2,
                100, () -> EXPORT_ID);
        when(catalog.getAssetActivity(any())).thenReturn(ExpiryFixtures.all());

        ExpiryReportPage page = shadow.page(shadow.parse(null));

        assertThat(page.mode()).isEqualTo("shadow");
        assertThat(page.items()).extracting(ExpiryReportRow::mode).containsOnly("shadow");
    }

    @Test
    void anUnsetModeIsLiveAndAnInvalidOneStopsTheService() {
        assertThat(new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, null, "", 2, 100, () -> EXPORT_ID)
                .mode()).isEqualTo("live");
        assertThatThrownBy(() -> new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, null, "Shadow Mode!", 2,
                100, () -> EXPORT_ID)).isInstanceOf(IllegalStateException.class).hasMessageContaining("dlap.mode");
        assertThatThrownBy(() -> new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, null, "live", 201,
                100, () -> EXPORT_ID)).isInstanceOf(IllegalStateException.class).hasMessageContaining("max-page-size");
        assertThatThrownBy(() -> new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, null, "live", 2, 0,
                () -> EXPORT_ID)).isInstanceOf(IllegalStateException.class).hasMessageContaining("max-rows");
    }

    // ---- the catalog is unavailable -----------------------------------------------------------

    private static FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://da-catalog/x", Map.of(), null,
                StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("getAssetActivity",
                Response.builder().status(status).reason("x").request(request).headers(Map.of()).build());
    }

    @Test
    void aCatalogFailureIsReportedAsUnavailableAndNeverAsAnEmptyReport() {
        List<Throwable> failures = List.of(feignError(503), feignError(500), feignError(403),
                CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("da-catalog")),
                new IllegalStateException("could not decode"));
        for (Throwable failure : failures) {
            org.mockito.Mockito.doThrow((RuntimeException) failure).when(catalog).getAssetActivity(any());

            assertThatThrownBy(() -> service.page(query(null)))
                    .as(failure.getClass().getSimpleName())
                    .isInstanceOf(CatalogUnavailableException.class);
            assertThatThrownBy(() -> service.startExport(query(null)))
                    .isInstanceOf(CatalogUnavailableException.class);
        }
    }

    @Test
    void aNullAnswerIsUnavailableToo() {
        when(catalog.getAssetActivity(any())).thenReturn(null);

        assertThatThrownBy(() -> service.page(query(null))).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void theUnavailableMessageDoesNotCarryTheCatalogResponseBody() {
        Request request = Request.create(Request.HttpMethod.GET, "http://da-catalog/x", Map.of(), null,
                StandardCharsets.UTF_8, null);
        FeignException leaky = FeignException.errorStatus("getAssetActivity",
                Response.builder().status(500).reason("x").request(request).headers(Map.of())
                        .body("secret-stack-trace", StandardCharsets.UTF_8).build());
        when(catalog.getAssetActivity(any())).thenThrow(leaky);

        assertThatThrownBy(() -> service.page(query(null)))
                .hasMessage("The catalog answered HTTP 500");
    }

    // ---- export -------------------------------------------------------------------------------

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void anExportStreamsTheGoldenCsvAndRegistersItsManifest() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query("2026-10-19T12:00:00Z"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ExportManifest manifest = session.writeTo(out);

        assertThat(out.toByteArray()).isEqualTo(ExpiryFixtures.goldenCsv());
        assertThat(session.exportId()).isEqualTo(EXPORT_ID);
        assertThat(session.fileName()).isEqualTo("expiry-report-20261019T120000Z.csv");
        assertThat(manifest.sha256()).isEqualTo(sha256(out.toByteArray()));
        assertThat(manifest.bytes()).isEqualTo(out.size());
        assertThat(manifest.rows()).isEqualTo(5);
        JsonNode expected = JSON.readTree(ExpiryFixtures.goldenJson("expiry-export-manifest.json"));
        JsonNode actual = JSON.readTree(JSON.writeValueAsString(manifest));
        assertThat(actual).isEqualTo(expected);
        assertThat(service.manifest(EXPORT_ID)).contains(manifest);
        assertThat(count("completed")).isEqualTo(1);
    }

    @Test
    void theExportUsesKeysetPagingEachRequestCarryingTheCursorOfThePageBefore() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query("2026-10-19T12:00:00Z"));
        session.writeTo(new ByteArrayOutputStream());

        ArgumentCaptor<ActivityRequest> sent = ArgumentCaptor.forClass(ActivityRequest.class);
        verify(catalog, org.mockito.Mockito.times(3)).getAssetActivity(sent.capture());
        // The first request has no cursor; each later one carries the nextAfter of the page before.
        assertThat(sent.getAllValues()).extracting(ActivityRequest::after)
                .containsExactly(null, ExpiryFixtures.split(0).nextAfter(), ExpiryFixtures.split(1).nextAfter());
        // A keyset request never names a page number: the cursor is the position.
        assertThat(sent.getAllValues()).extracting(ActivityRequest::page).containsOnly(0);
    }

    @Test
    void everyPageOfAnExportCarriesTheSameInstantAndTheExportSort() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query("2026-10-19T12:00:00Z"));
        session.writeTo(new ByteArrayOutputStream());

        ArgumentCaptor<ActivityRequest> sent = ArgumentCaptor.forClass(ActivityRequest.class);
        verify(catalog, org.mockito.Mockito.times(3)).getAssetActivity(sent.capture());
        assertThat(sent.getAllValues()).extracting(ActivityRequest::asOf).containsOnly("2026-10-19T12:00:00Z");
        assertThat(sent.getAllValues()).extracting(ActivityRequest::sort).containsOnly("assetUri,asc");
        assertThat(sent.getAllValues()).extracting(ActivityRequest::size).containsOnly(2);
    }

    @Test
    void anExportIgnoresTheCallersPagingAndSort() throws Exception {
        ExpiryReportQuery paged = service.parse(new ExpiryReportParams(null, null, null, null, null, null, null, null,
                null, null, "2026-10-19T12:00:00Z", "4", "1", "sizeBytes,desc"));

        service.startExport(paged).writeTo(new ByteArrayOutputStream());

        ArgumentCaptor<ActivityRequest> sent = ArgumentCaptor.forClass(ActivityRequest.class);
        verify(catalog, org.mockito.Mockito.atLeastOnce()).getAssetActivity(sent.capture());
        assertThat(sent.getAllValues().get(0).page()).isZero();
        assertThat(sent.getAllValues()).extracting(ActivityRequest::sort).containsOnly("assetUri,asc");
    }

    @Test
    void anEmptyResultIsAHeaderOnlyFileWithAManifestOfZeroRows() throws Exception {
        when(catalog.getAssetActivity(any())).thenReturn(new ActivityPage(List.of(), 0, 0, 0, 2));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ExportManifest manifest = service.startExport(query(null)).writeTo(out);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("assetUri,assetId,").endsWith(",readSignalText\n");
        assertThat(csv.lines().count()).isEqualTo(1);
        assertThat(manifest.rows()).isZero();
    }

    @Test
    void anOversizedExportIsRefusedBeforeAnyByteIsSent() {
        ExpiryReportService small = new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, meters, "live", 2, 4,
                () -> EXPORT_ID);

        assertThatThrownBy(() -> small.startExport(query(null)))
                .isInstanceOf(ExportRejectedException.class)
                .hasMessageContaining("5 rows")
                .hasMessageContaining("limit of 4");
        assertThat(count("rejected")).isEqualTo(1);
    }

    @Test
    void anExportThatFailsMidStreamIsMarkedIncompleteAndHasNoManifest() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query("2026-10-19T12:00:00Z"));
        when(catalog.getAssetActivity(any())).thenAnswer(inv -> {
            int page = ExpiryFixtures.fixturePage(((ActivityRequest) inv.getArgument(0)).after());
            if (page == 2) {
                throw feignError(503);
            }
            return ExpiryFixtures.split(page);
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatThrownBy(() -> session.writeTo(out)).isInstanceOf(ExportAbortedException.class)
                .hasMessageContaining("page 2");

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"EXPORT INCOMPLETE, DO NOT USE: ");
        assertThat(csv).doesNotEndWith("\n");
        assertThat(service.manifest(EXPORT_ID)).isEmpty();
        assertThat(count("aborted")).isEqualTo(1);
        assertThat(count("completed")).isZero();
    }

    @Test
    void anExportWhoseRowCountChangesWhileItRunsStillCompletesAndSkipsAndRepeatsNothing() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query(null));
        when(catalog.getAssetActivity(any())).thenAnswer(inv -> {
            ActivityPage page = ExpiryFixtures.keyset((ActivityRequest) inv.getArgument(0));
            // Rows were written behind the cursor: the catalog's total grows, the walk is unaffected.
            return new ActivityPage(page.content(), page.totalElements() + 1, page.totalPages(), page.number(),
                    page.size(), page.nextAfter(), page.last());
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ExportManifest manifest = session.writeTo(out);

        assertThat(manifest.rows()).isEqualTo(5);
        assertThat(manifest.source()).containsEntry("totalElements", 5L).containsEntry("paging", "keyset");
        assertThat(out.toByteArray()).isEqualTo(ExpiryFixtures.goldenCsv());
        assertThat(service.manifest(EXPORT_ID)).contains(manifest);
    }

    /**
     * A catalog that serves keyset pages of a table another writer is inserting into: the answer to
     * each request is computed from the rows present at that moment, as the real endpoint does.
     */
    private final class GrowingCatalog {
        private final java.util.TreeMap<String, ActivityRow> table = new java.util.TreeMap<>();
        private final java.util.function.IntConsumer beforeRequest;
        private int requests;

        GrowingCatalog(int initial, java.util.function.IntConsumer beforeRequest) {
            ActivityRow template = ExpiryFixtures.all().content().get(0);
            for (int n = 2; n <= initial * 2; n += 2) {
                insert(ExpiryFixtures.rowNumbered(template, n));
            }
            this.beforeRequest = beforeRequest;
        }

        void insert(ActivityRow row) {
            table.put(row.assetUri(), row);
        }

        ActivityRow row(int n) {
            return ExpiryFixtures.rowNumbered(ExpiryFixtures.all().content().get(0), n);
        }

        ActivityPage answer(ActivityRequest request) {
            beforeRequest.accept(requests++);
            java.util.NavigableMap<String, ActivityRow> rest = request.after() == null
                    ? table : table.tailMap(request.after(), false);
            List<ActivityRow> rows = rest.values().stream().limit(request.size()).toList();
            boolean more = rest.size() > rows.size();
            return new ActivityPage(rows, table.size(), (table.size() + request.size() - 1) / request.size(), 0,
                    request.size(), more ? rows.get(rows.size() - 1).assetUri() : null, !more);
        }
    }

    @Test
    void concurrentInsertsDuringAnExportNeitherSkipNorRepeatAnAsset() throws Exception {
        // 10 assets numbered 2, 4 .. 20, two per page. Before the second and the third request another
        // writer inserts an asset behind the cursor and one ahead of it.
        GrowingCatalog[] holder = new GrowingCatalog[1];
        holder[0] = new GrowingCatalog(10, request -> {
            if (request == 1) {
                holder[0].insert(holder[0].row(3));  // behind the cursor (page 0 ended at asset-004)
                holder[0].insert(holder[0].row(7));  // ahead of it
            }
            if (request == 2) {
                holder[0].insert(holder[0].row(5));  // behind the cursor (page 1 ended at asset-008)
                holder[0].insert(holder[0].row(15)); // ahead of it
            }
        });
        when(catalog.getAssetActivity(any())).thenAnswer(inv -> holder[0].answer((ActivityRequest) inv.getArgument(0)));
        ExpiryReportService.ExportSession session = service.startExport(query("2026-10-19T12:00:00Z"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ExportManifest manifest = session.writeTo(out);

        List<String> uris = out.toString(StandardCharsets.UTF_8).lines().skip(1)
                .map(line -> line.substring(0, line.indexOf(','))).toList();
        // Nothing repeats, and the file is in assetUri order.
        assertThat(uris).doesNotHaveDuplicates().isSorted();
        // Every asset that was there when the export began is exported exactly once.
        for (int n = 2; n <= 20; n += 2) {
            assertThat(uris).containsOnlyOnce(String.format("walk://asset-%03d", n));
        }
        // Written ahead of the cursor: exported. Written behind it: not.
        assertThat(uris).contains("walk://asset-007", "walk://asset-015");
        assertThat(uris).doesNotContain("walk://asset-003", "walk://asset-005");
        assertThat(manifest.rows()).isEqualTo(12);
        assertThat(manifest.source()).containsEntry("totalElements", 10L).containsEntry("paging", "keyset");
        assertThat(service.manifest(EXPORT_ID)).contains(manifest);
        assertThat(count("completed")).isEqualTo(1);
    }

    @Test
    void anExportThatSeesTheSameAssetTwiceBecauseThePagesShiftedIsAborted() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query(null));
        when(catalog.getAssetActivity(any())).thenAnswer(inv -> {
            int page = ExpiryFixtures.fixturePage(((ActivityRequest) inv.getArgument(0)).after());
            // page 1 repeats the last row of page 0, as an offset window would after an insert before it
            if (page == 1) {
                List<ActivityRow> rows = new ArrayList<>(ExpiryFixtures.split(1).content());
                rows.set(0, ExpiryFixtures.split(0).content().get(1));
                ActivityPage original = ExpiryFixtures.split(1);
                return new ActivityPage(rows, original.totalElements(), original.totalPages(), 1, original.size(),
                        original.nextAfter(), original.last());
            }
            return ExpiryFixtures.split(page);
        });

        assertThatThrownBy(() -> session.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(ExportAbortedException.class)
                .hasMessageContaining("twice");
        assertThat(service.manifest(EXPORT_ID)).isEmpty();
    }

    @Test
    void anExportWhoseCatalogDoesNotAdvanceItsCursorIsAborted() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query(null));
        when(catalog.getAssetActivity(any())).thenAnswer(inv -> {
            ActivityRequest request = (ActivityRequest) inv.getArgument(0);
            if (request.after() == null) {
                return ExpiryFixtures.split(0);
            }
            // The catalog answers page 1 with the cursor it was sent, so the walk would never end.
            ActivityPage original = ExpiryFixtures.split(1);
            return new ActivityPage(original.content(), original.totalElements(), original.totalPages(), 0,
                    original.size(), request.after(), false);
        });

        assertThatThrownBy(() -> session.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(ExportAbortedException.class)
                .hasMessageContaining("did not advance its keyset cursor");
        assertThat(service.manifest(EXPORT_ID)).isEmpty();
    }

    @Test
    void anExportWhoseCatalogSaysMoreRowsFollowButSendsNoCursorIsAbortedMidStream() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query(null));
        when(catalog.getAssetActivity(any())).thenAnswer(inv -> {
            int page = ExpiryFixtures.fixturePage(((ActivityRequest) inv.getArgument(0)).after());
            if (page == 1) {
                ActivityPage original = ExpiryFixtures.split(1);
                return new ActivityPage(original.content(), original.totalElements(), original.totalPages(), 0,
                        original.size(), null, false);
            }
            return ExpiryFixtures.split(page);
        });

        assertThatThrownBy(() -> session.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(ExportAbortedException.class)
                .hasMessageContaining("sent no keyset cursor");
    }

    @Test
    void aCatalogThatPredatesKeysetPagingIsRefusedBeforeAnyByteIsSent() {
        // An older catalog ignores after= and sends the offset page: more rows, no cursor.
        ActivityPage firstPage = ExpiryFixtures.split(0);
        when(catalog.getAssetActivity(any())).thenReturn(new ActivityPage(firstPage.content(),
                firstPage.totalElements(), firstPage.totalPages(), 0, firstPage.size(), null, false));

        assertThatThrownBy(() -> service.startExport(query(null)))
                .isInstanceOf(CatalogUnavailableException.class)
                .hasMessageContaining("keyset");
    }

    @Test
    void anExportThatGrowsPastTheRowLimitWhileItRunsIsAborted() throws Exception {
        ExpiryReportService tight = new ExpiryReportService(catalog, registry, ExpiryFixtures.CLOCK, meters, "live", 2, 5,
                () -> EXPORT_ID);
        ExpiryReportService.ExportSession session = tight.startExport(query(null));
        when(catalog.getAssetActivity(any())).thenAnswer(inv -> {
            int page = ExpiryFixtures.fixturePage(((ActivityRequest) inv.getArgument(0)).after());
            if (page == 2) {
                ActivityPage original = ExpiryFixtures.split(2);
                List<ActivityRow> rows = new ArrayList<>(original.content());
                rows.add(ExpiryFixtures.rowNumbered(original.content().get(0), 1));
                return new ActivityPage(rows, original.totalElements(), original.totalPages(), 0, original.size(),
                        null, true);
            }
            return ExpiryFixtures.split(page);
        });

        assertThatThrownBy(() -> session.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(ExportAbortedException.class)
                .hasMessageContaining("grew past the limit of 5");
        assertThat(tight.manifest(EXPORT_ID)).isEmpty();
    }

    @Test
    void anExportWhoseRowsFallShortOfTheAnnouncementBecauseAnAssetWasRemovedStillCompletes() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query(null));
        when(catalog.getAssetActivity(any())).thenAnswer(inv -> {
            int page = ExpiryFixtures.fixturePage(((ActivityRequest) inv.getArgument(0)).after());
            if (page == 2) {
                ActivityPage original = ExpiryFixtures.split(2);
                return new ActivityPage(List.of(), 4, 2, 0, original.size(), null, true);
            }
            return ExpiryFixtures.split(page);
        });

        ExportManifest manifest = session.writeTo(new ByteArrayOutputStream());

        assertThat(manifest.rows()).isEqualTo(4);
        assertThat(manifest.source()).containsEntry("totalElements", 5L);
    }

    @Test
    void aClientThatDisconnectsGetsNoManifest() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query(null));
        java.io.OutputStream broken = new java.io.OutputStream() {
            private int written;

            @Override
            public void write(int b) throws IOException {
                if (++written > 100) {
                    throw new IOException("Broken pipe");
                }
            }
        };

        assertThatThrownBy(() -> session.writeTo(broken)).isInstanceOf(IOException.class);
        assertThat(service.manifest(EXPORT_ID)).isEmpty();
    }

    @Test
    void aSessionStreamsOnlyOnce() throws Exception {
        ExpiryReportService.ExportSession session = service.startExport(query(null));
        session.writeTo(new ByteArrayOutputStream());

        assertThatThrownBy(() -> session.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class);
    }
}
