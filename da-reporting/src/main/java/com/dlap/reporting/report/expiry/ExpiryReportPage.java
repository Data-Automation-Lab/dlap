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
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One page of the Asset Activity and Expiry report as JSON. The envelope says which report and
 * contract version it is, the deployment mode, the instant the day counts were computed against,
 * the filters that applied and where the page sits in the whole result. A report is reproducible:
 * the same filters and the same {@code asOf} over the same catalog state give the same page.
 *
 * @param report          always {@code asset-activity-expiry}
 * @param contractVersion version of the column contract, see {@code package-info}
 * @param mode            deployment mode label, {@code live} or {@code shadow}
 * @param asOf            the instant the day counts are computed against
 * @param filters         the filters applied, normalized; absent filters are not listed
 * @param page            position of this page in the result
 * @param items           the rows of this page
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ExpiryReportPage(
        String report,
        String contractVersion,
        String mode,
        Instant asOf,
        Map<String, Object> filters,
        PageInfo page,
        List<ExpiryReportRow> items) {

    /**
     * Position of a page in the result.
     *
     * @param number        zero-based page number
     * @param size          rows per page, as the catalog applied it
     * @param totalElements rows over all pages
     * @param totalPages    number of pages
     * @param sort          the sort in effect, {@code property,direction}
     */
    public record PageInfo(int number, int size, long totalElements, int totalPages, String sort) {
    }
}
