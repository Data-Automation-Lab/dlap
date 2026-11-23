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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.dlap.reporting.client.CatalogServiceClient.ActivityPage;
import com.dlap.reporting.client.CatalogServiceClient.ActivityRow;
import com.dlap.reporting.client.ResilientCatalogClient.ActivityRequest;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Test data of the Asset Activity and Expiry report: five synthetic assets in the wire shape of
 * the catalog's activity endpoint, and the golden outputs written for them by hand (the JSON and
 * the manifest by an independent script, the CSV by hand). Nothing here comes from a real account.
 *
 * <p>The assets, in {@code assetUri} order: an RDS instance on legal hold with read logging off
 * ({@code NO_COVERAGE}), an Azure container with a formula in its name and partial coverage, a
 * BigQuery table that was not read under full coverage ({@code NOT_READ}), a GCS bucket with a
 * recorded read, and an S3 bucket the catalog knows nothing about ({@code UNKNOWN}).
 */
final class ExpiryFixtures {

    /** The report instant of every golden output. */
    static final Instant AS_OF = Instant.parse("2026-10-19T12:00:00Z");

    /** A clock fixed at {@link #AS_OF}. */
    static final Clock CLOCK = Clock.fixed(AS_OF, ZoneOffset.UTC);

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private ExpiryFixtures() {
    }

    /** All five assets on one page of 50, as the catalog serves them. */
    static ActivityPage all() {
        return catalogPage("catalog-activity-all.json");
    }

    /** One page of the same five assets split into pages of two (0, 1 or 2). */
    static ActivityPage split(int page) {
        return catalogPage("catalog-activity-page-" + page + ".json");
    }

    /**
     * The fixture page a keyset request asks for: the first page for no cursor, and for the cursor
     * a page ended with, the page after it. This is how the catalog's keyset walk answers.
     *
     * @param request the request the report sent
     * @return the fixture page
     */
    static ActivityPage keyset(ActivityRequest request) {
        return split(fixturePage(request.after()));
    }

    /**
     * The index (0, 1 or 2) of the fixture page a keyset cursor selects.
     *
     * @param after the cursor, or null for the first page
     * @return the page index
     */
    static int fixturePage(String after) {
        if (after == null) {
            return 0;
        }
        for (int i = 0; i < 2; i++) {
            if (after.equals(split(i).nextAfter())) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("No fixture page follows the cursor " + after);
    }

    /**
     * A row shaped like {@code template} with its own id and URI, for a catalog that grows.
     *
     * @param template an existing row
     * @param n        a number that makes the id and the URI unique and ordered
     * @return the new row
     */
    static ActivityRow rowNumbered(ActivityRow template, int n) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> asMap = JSON.convertValue(template, java.util.Map.class);
        asMap.put("assetId", new java.util.UUID(0, 1000L + n).toString());
        asMap.put("assetUri", String.format("walk://asset-%03d", n));
        asMap.put("assetName", String.format("asset-%03d", n));
        return JSON.convertValue(asMap, ActivityRow.class);
    }

    private static ActivityPage catalogPage(String name) {
        try (InputStream in = open(name)) {
            return JSON.readValue(in, ActivityPage.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The golden CSV, with line ends normalized to LF (a Windows checkout may have changed them). */
    static byte[] goldenCsv() {
        return golden("expiry-report.csv").replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A golden JSON document as text. */
    static String goldenJson(String name) {
        return golden(name);
    }

    private static String golden(String name) {
        try (InputStream in = open("golden/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(String name) {
        InputStream in = ExpiryFixtures.class.getResourceAsStream("/expiry/" + name);
        if (in == null) {
            throw new IllegalStateException("Missing test resource /expiry/" + name);
        }
        return in;
    }
}
