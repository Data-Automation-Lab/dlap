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

package com.dlap.reporting.export;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The manifest of one completed export: what was exported, under which filters and instant, and
 * the SHA-256 of the exact bytes the client received. A reader checks a downloaded file with
 * {@code sha256sum} against {@link #sha256()}, and the row count against {@link #rows()}.
 *
 * <p>Only a completed export has a manifest. A download that ends early (a catalog failure
 * mid-stream, a client that disconnects) has none, so a file without a matching manifest is not
 * to be trusted.
 *
 * @param exportId       identifier returned in the {@code X-Export-Id} response header
 * @param report         report name, for example {@code asset-activity-expiry}
 * @param contractVersion version of the report's column contract
 * @param format         {@code csv}
 * @param fileName       the file name offered in {@code Content-Disposition}
 * @param sha256         lower-case hexadecimal SHA-256 of the exported bytes
 * @param bytes          size of the export in bytes
 * @param rows           data rows, not counting the header
 * @param columns        column names in file order
 * @param asOf           the instant the day counts were computed against
 * @param mode           deployment mode label of the report ({@code live} or {@code shadow})
 * @param filters        the filters applied, as the caller gave them, plus the paging the export used
 * @param source         where the rows came from: the service, the endpoint and how many pages it took
 * @param completedAt    when the export finished, from the service clock (a processing time, not a business time)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ExportManifest(
        UUID exportId,
        String report,
        String contractVersion,
        String format,
        String fileName,
        String sha256,
        long bytes,
        long rows,
        List<String> columns,
        Instant asOf,
        String mode,
        Map<String, Object> filters,
        Map<String, Object> source,
        Instant completedAt) {

    /**
     * Makes the collections immutable and never null.
     *
     * @param exportId        identifier
     * @param report          report name
     * @param contractVersion contract version
     * @param format          format
     * @param fileName        file name
     * @param sha256          digest
     * @param bytes           size
     * @param rows            row count
     * @param columns         column names
     * @param asOf            as-of instant
     * @param mode            mode label
     * @param filters         filters
     * @param source          source description
     * @param completedAt     completion time
     */
    public ExportManifest {
        columns = columns == null ? List.of() : List.copyOf(columns);
        filters = filters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(filters));
        source = source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
