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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dlap.reporting.client.CatalogServiceClient.ActivityPage;
import com.dlap.reporting.client.CatalogServiceClient.ActivityRow;
import com.dlap.reporting.client.ResilientCatalogClient;
import com.dlap.reporting.export.CsvWriter;
import com.dlap.reporting.export.ExportManifest;
import com.dlap.reporting.export.ExportManifestRegistry;
import com.dlap.reporting.export.HashingOutputStream;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The Asset Activity and Expiry report (RPT-01, decision D9): the catalog serves the activity
 * query, and this service adds the report contract (columns, read signal state, mode), the CSV
 * export and its manifest. Access rules are on the controller.
 *
 * <p>Nothing here computes a business value. Ages, idle days, retention status and expiry come
 * from the catalog as they are stored, against the one {@code asOf} instant of the request. The
 * service adds three derived columns, the account (read from the canonical URI), the read signal
 * state and its sentence (see {@link ReadSignalState}), and a mode label.
 *
 * <p>If the catalog cannot answer, the report fails with {@link CatalogUnavailableException}. It
 * never returns an empty or partial report: an empty report reads as "no assets".
 */
@Service
public class ExpiryReportService {

    /** Name of the report in the JSON envelope and the manifest. */
    public static final String REPORT = "asset-activity-expiry";

    /** Version of the column contract documented in {@code package-info}. */
    public static final String CONTRACT_VERSION = "1.1";

    private static final Logger log = LoggerFactory.getLogger(ExpiryReportService.class);
    private static final Pattern MODE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String EXPORT_FAILED_MARKER = "\"EXPORT INCOMPLETE, DO NOT USE: ";

    private final ResilientCatalogClient catalog;
    private final ExportManifestRegistry manifests;
    private final Clock clock;
    private final String mode;
    private final int maxPageSize;
    private final int maxExportRows;
    private final Supplier<UUID> exportIds;
    private final MeterRegistry meters;

    /**
     * Creates the service from the application context.
     *
     * @param catalog       the catalog client with its circuit breaker
     * @param manifests     the registry of recent export manifests
     * @param clocks        the application clock when one is defined, else the system UTC clock
     * @param meterRegistry the metrics registry when one is defined
     * @param mode          deployment mode label ({@code dlap.mode}); {@code live} when unset
     * @param maxPageSize   the largest page size, at most the catalog page limit (200)
     * @param maxExportRows the largest export in rows
     */
    @Autowired
    public ExpiryReportService(ResilientCatalogClient catalog, ExportManifestRegistry manifests,
            ObjectProvider<Clock> clocks, ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${dlap.mode:live}") String mode,
            @Value("${dlap.reporting.expiry.max-page-size:200}") int maxPageSize,
            @Value("${dlap.reporting.expiry.export.max-rows:250000}") int maxExportRows) {
        this(catalog, manifests, clocks.getIfAvailable(Clock::systemUTC), meterRegistry.getIfAvailable(),
                mode, maxPageSize, maxExportRows, UUID::randomUUID);
    }

    /**
     * Creates the service with every collaborator given, for tests.
     *
     * @param catalog       the catalog client
     * @param manifests     the manifest registry
     * @param clock         the time source
     * @param meters        the metrics registry, may be null
     * @param mode          deployment mode label
     * @param maxPageSize   the largest page size
     * @param maxExportRows the largest export in rows
     * @param exportIds     the source of export identifiers
     */
    ExpiryReportService(ResilientCatalogClient catalog, ExportManifestRegistry manifests, Clock clock,
            MeterRegistry meters, String mode, int maxPageSize, int maxExportRows, Supplier<UUID> exportIds) {
        this.catalog = catalog;
        this.manifests = manifests;
        this.clock = clock;
        this.meters = meters;
        this.mode = checkedMode(mode);
        if (maxPageSize < 1 || maxPageSize > 200) {
            throw new IllegalStateException("dlap.reporting.expiry.max-page-size must be 1 to 200 (the catalog limit)");
        }
        if (maxExportRows < 1) {
            throw new IllegalStateException("dlap.reporting.expiry.export.max-rows must be positive");
        }
        this.maxPageSize = maxPageSize;
        this.maxExportRows = maxExportRows;
        this.exportIds = exportIds;
    }

    private static String checkedMode(String raw) {
        String value = raw == null || raw.isBlank() ? "live" : raw.strip().toLowerCase(Locale.ROOT);
        if (!MODE.matcher(value).matches()) {
            throw new IllegalStateException("dlap.mode must be a lower-case word such as live or shadow");
        }
        return value;
    }

    /**
     * Validates the query string of a request.
     *
     * @param params the query string as sent
     * @return the validated query, with {@code asOf} resolved
     * @throws ExpiryReportRequestException when a value is malformed
     */
    public ExpiryReportQuery parse(ExpiryReportParams params) {
        return ExpiryReportQuery.parse(params, clock, maxPageSize);
    }

    /**
     * The deployment mode label.
     *
     * @return {@code live}, {@code shadow} or another lower-case word set in {@code dlap.mode}
     */
    public String mode() {
        return mode;
    }

    /**
     * One page of the report.
     *
     * @param query the validated query
     * @return the page
     * @throws CatalogUnavailableException when the catalog cannot answer
     */
    public ExpiryReportPage page(ExpiryReportQuery query) {
        ActivityPage page = fetch(query);
        var items = page.content().stream().map(row -> ExpiryReportRow.from(row, mode)).toList();
        return new ExpiryReportPage(REPORT, CONTRACT_VERSION, mode, query.asOf(), query.filterEcho(),
                new ExpiryReportPage.PageInfo(page.number(), page.size() > 0 ? page.size() : query.size(),
                        page.totalElements(), page.totalPages(), query.sort()),
                items);
    }

    /**
     * Starts a CSV export. The first page is fetched here, before anything is sent, so a catalog
     * outage or an oversized result is still a proper HTTP error. The rest is fetched while the
     * export streams, by keyset: each request carries the {@code nextAfter} of the page before, so a
     * row written during the export cannot shift a page (Q-445).
     *
     * @param query the validated query; its paging fields are ignored, an export always starts at
     *              the first row and runs in {@code assetUri} order
     * @return the session, ready to stream
     * @throws CatalogUnavailableException when the catalog cannot answer
     * @throws ExportRejectedException     when the result is larger than the row limit
     */
    public ExportSession startExport(ExpiryReportQuery query) {
        ExpiryReportQuery first = query.forExportPage(0, maxPageSize);
        ActivityPage page = fetch(first);
        if (cursorMissing(page)) {
            // A catalog that predates keyset paging would send the first page and then nothing: say so
            // before a byte of the file is sent.
            throw new CatalogUnavailableException("The catalog returned no keyset cursor for a result with more "
                    + "rows; it does not support keyset paging (after=)", null);
        }
        if (page.totalElements() > maxExportRows) {
            count("rejected");
            throw new ExportRejectedException("The export would have " + page.totalElements()
                    + " rows, more than the limit of " + maxExportRows + "; narrow the filters");
        }
        return new ExportSession(first, page, exportIds.get());
    }

    /**
     * The manifest of a completed export.
     *
     * @param exportId the id from the {@code X-Export-Id} header
     * @return the manifest, or empty when unknown or expired
     */
    public Optional<ExportManifest> manifest(UUID exportId) {
        return manifests.find(exportId);
    }

    /** True when the catalog says more rows follow (not the last page) but gave no keyset cursor. */
    private static boolean cursorMissing(ActivityPage page) {
        return !page.content().isEmpty() && Boolean.FALSE.equals(page.last())
                && (page.nextAfter() == null || page.nextAfter().isBlank());
    }

    private ActivityPage fetch(ExpiryReportQuery query) {
        return fetch(query.toCatalogRequest());
    }

    private ActivityPage fetch(ResilientCatalogClient.ActivityRequest request) {
        ActivityPage page;
        try {
            page = catalog.getAssetActivity(request);
        } catch (CallNotPermittedException e) {
            throw new CatalogUnavailableException("The catalog is not being called: its circuit breaker is open", e);
        } catch (FeignException e) {
            throw new CatalogUnavailableException("The catalog answered HTTP " + e.status(), e);
        } catch (RuntimeException e) {
            throw new CatalogUnavailableException(
                    "The catalog answer could not be read (" + e.getClass().getSimpleName() + ")", e);
        }
        if (page == null) {
            throw new CatalogUnavailableException("The catalog answered with no page", null);
        }
        return page;
    }

    private void count(String outcome) {
        if (meters != null) {
            meters.counter("dlap_reporting_expiry_exports_total", "outcome", outcome).increment();
        }
    }

    /**
     * One CSV export in progress. It holds the first page and the identifier and file name that
     * the response headers carry, and streams the whole export once.
     */
    public final class ExportSession {

        private final ExpiryReportQuery firstQuery;
        private final ActivityPage firstPage;
        private final UUID exportId;
        private boolean used;

        private ExportSession(ExpiryReportQuery firstQuery, ActivityPage firstPage, UUID exportId) {
            this.firstQuery = firstQuery;
            this.firstPage = firstPage;
            this.exportId = exportId;
        }

        /**
         * The identifier of this export, for the {@code X-Export-Id} header and the manifest URL.
         *
         * @return the id
         */
        public UUID exportId() {
            return exportId;
        }

        /**
         * The file name for {@code Content-Disposition}.
         *
         * @return a name built from the report instant
         */
        public String fileName() {
            return "expiry-report-" + FILE_STAMP.format(firstQuery.asOf()) + ".csv";
        }

        /**
         * Streams the header and every row to {@code out}, page by page, hashing the bytes as they
         * go, and registers the manifest when the export is complete.
         *
         * <p>The export is checked as it runs. If the catalog fails, if a row repeats or has no id
         * (the pages shifted), if the catalog does not advance its cursor, if it says more rows
         * follow but sends no cursor, or if the export grows past the row limit, the export is cut
         * off with a marker line that starts an unterminated quoted field (a CSV reader reports an
         * error) and {@link ExportAbortedException} is thrown. No manifest is registered for it.
         *
         * <p>A change in the number of matching rows while the export runs is not an error: the
         * walk is by cursor, so no asset that stays in the catalog is skipped or repeated. An asset
         * written ahead of the cursor is exported, one written behind it is not; the manifest
         * records the count announced at the start next to the rows written.
         *
         * @param out the response stream; not closed here
         * @return the manifest of the completed export
         * @throws IOException            when the stream cannot be written
         * @throws ExportAbortedException when the export cannot be completed correctly
         * @throws IllegalStateException  when called twice
         */
        public ExportManifest writeTo(OutputStream out) throws IOException {
            if (used) {
                throw new IllegalStateException("An export session streams once");
            }
            used = true;
            HashingOutputStream hashed = new HashingOutputStream(out);
            Writer writer = new BufferedWriter(new OutputStreamWriter(hashed, StandardCharsets.UTF_8));
            try {
                long rows = streamRows(writer);
                writer.flush();
                ExportManifest manifest = manifest(hashed, rows);
                manifests.register(manifest);
                count("completed");
                log.info("Expiry report export {} completed: rows={} bytes={} sha256={}", exportId, rows,
                        manifest.bytes(), manifest.sha256());
                return manifest;
            } catch (ExportAbortedException e) {
                count("aborted");
                log.error("Expiry report export {} aborted: {}", exportId, e.getMessage());
                writer.write(EXPORT_FAILED_MARKER + e.getMessage().replace('"', '\''));
                writer.flush();
                throw e;
            }
        }

        private long streamRows(Writer writer) throws IOException {
            writer.write(CsvWriter.row(ExpiryReportRow.COLUMNS));
            writer.flush();
            Set<UUID> seen = new HashSet<>();
            ActivityPage page = firstPage;
            String cursor = null;
            int pageNumber = 0;
            long rows = 0;
            while (true) {
                for (ActivityRow row : page.content()) {
                    if (row.assetId() == null || !seen.add(row.assetId())) {
                        throw new ExportAbortedException("the catalog returned a row twice or without an id, "
                                + "so the pages shifted while the export ran; run it again", null);
                    }
                    if (++rows > maxExportRows) {
                        throw new ExportAbortedException("the export grew past the limit of " + maxExportRows
                                + " rows while it ran; narrow the filters", null);
                    }
                    writer.write(CsvWriter.row(ExpiryReportRow.from(row, mode).csvCells()));
                }
                writer.flush();
                String next = page.nextAfter();
                if (next == null || next.isBlank()) {
                    if (cursorMissing(page)) {
                        throw new ExportAbortedException("the catalog says more rows follow page " + pageNumber
                                + " but sent no keyset cursor", null);
                    }
                    break;
                }
                if (page.content().isEmpty() || next.equals(cursor)) {
                    throw new ExportAbortedException("the catalog did not advance its keyset cursor at page "
                            + pageNumber + "; run it again", null);
                }
                cursor = next;
                pageNumber++;
                try {
                    page = fetch(firstQuery.toCatalogRequest(cursor));
                } catch (CatalogUnavailableException e) {
                    throw new ExportAbortedException("the catalog failed at page " + pageNumber + ": "
                            + e.getMessage(), e);
                }
            }
            if (rows != firstPage.totalElements()) {
                log.info("Expiry report export {}: {} rows written, {} announced at the start; assets were written "
                        + "while the export ran", exportId, rows, firstPage.totalElements());
            }
            return rows;
        }

        private ExportManifest manifest(HashingOutputStream hashed, long rows) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("service", "da-catalog");
            source.put("endpoint", "/api/v1/catalog/assets/activity");
            source.put("pageSize", firstQuery.size());
            source.put("totalElements", firstPage.totalElements());
            source.put("sort", ExpiryReportQuery.EXPORT_SORT);
            source.put("paging", "keyset");
            return new ExportManifest(exportId, REPORT, CONTRACT_VERSION, "csv", fileName(), hashed.sha256Hex(),
                    hashed.bytesWritten(), rows, ExpiryReportRow.COLUMNS, firstQuery.asOf(), mode,
                    firstQuery.filterEcho(), source, clock.instant());
        }
    }
}
