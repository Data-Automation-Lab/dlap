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

package com.dlap.reporting.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestParam;

import com.dlap.reporting.client.CatalogServiceClient.ActivityRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Pins the report's catalog client to the catalog's committed API contract
 * ({@code docs/api/openapi/catalog.json}, written by the catalog's own OpenAPI export test): every
 * field the client reads and every parameter it sends is in the contract, and the fixture rows of
 * the report tests have exactly the contract's fields. If the catalog renames or drops a field, this
 * test fails here, in the reporting build, not in production.
 */
class CatalogActivityContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode catalog;

    @BeforeAll
    static void readContract() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("docs/api/openapi/catalog.json"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("docs/api/openapi/catalog.json was not found above " + System.getProperty("user.dir"));
        }
        catalog = JSON.readTree(Files.readString(dir.resolve("docs/api/openapi/catalog.json")));
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static JsonNode rowSchema() {
        return catalog.at("/components/schemas/AssetActivityRowDTO/properties");
    }

    @Test
    void everyFieldTheClientReadsIsInTheCatalogContract() {
        Set<String> contract = names(rowSchema());
        List<String> read = Arrays.stream(ActivityRow.class.getRecordComponents()).map(RecordComponent::getName).toList();

        assertThat(contract).isNotEmpty();
        assertThat(contract).containsAll(read);
    }

    @Test
    void thePageEnvelopeFieldsAreInTheCatalogContract() {
        Set<String> page = names(catalog.at("/components/schemas/AssetActivityPageDTO/properties"));

        assertThat(page).contains("content", "totalElements", "totalPages", "number", "size", "last", "nextAfter");
    }

    @Test
    void everyParameterTheClientSendsIsInTheCatalogContract() {
        JsonNode operation = catalog.at("/paths/~1api~1v1~1catalog~1assets~1activity/get");
        Set<String> documented = new HashSet<>();
        operation.get("parameters").forEach(p -> documented.add(p.get("name").asText()));
        Method call = Arrays.stream(CatalogServiceClient.class.getMethods())
                .filter(m -> m.getName().equals("getAssetActivity")).findFirst().orElseThrow();
        List<String> sent = new ArrayList<>();
        Arrays.stream(call.getParameters()).forEach(p -> sent.add(AnnotatedElementUtils.findMergedAnnotation(p, RequestParam.class).name()));

        // page, size and sort are the members of the catalog's documented Spring "pageable" parameter
        assertThat(documented).contains("pageable");
        sent.removeAll(List.of("page", "size", "sort"));
        assertThat(documented).containsAll(sent);
        assertThat(sent).containsExactlyInAnyOrder("provider", "kind", "status", "lastReadBefore", "lastReadAfter",
                "lastReadKnown", "retentionStatus", "legalHold", "readCoverage", "asOf", "after");
    }

    @Test
    void theReportFixturesHaveExactlyTheFieldsOfTheCatalogContract() throws IOException {
        Set<String> contract = names(rowSchema());
        for (String file : List.of("catalog-activity-all.json", "catalog-activity-page-0.json")) {
            try (InputStream in = getClass().getResourceAsStream("/expiry/" + file)) {
                JsonNode page = JSON.readTree(in);
                for (JsonNode row : page.get("content")) {
                    assertThat(names(row)).as(file).isEqualTo(contract);
                }
            }
        }
    }
}
