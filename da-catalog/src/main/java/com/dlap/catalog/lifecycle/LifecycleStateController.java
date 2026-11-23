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

package com.dlap.catalog.lifecycle;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.dlap.catalog.lifecycle.okf.OkfExporter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Lifecycle State 0.1 over HTTP (LSE-01, docs/specs/lifecycle-state.md, ADR-0097).
 *
 * <ul>
 *   <li>{@code GET /api/v1/catalog/assets/{assetId}/lifecycle-state}: one object.</li>
 *   <li>{@code GET /api/v1/catalog/lifecycle-states?page=&size=}: a page of objects, every asset of
 *       the catalog in {@code asset_id} order, all at one {@code asOf}.</li>
 *   <li>{@code GET /api/v1/catalog/lifecycle-states/okf}: the OKF v0.1 export of the whole catalog
 *       as a zip of concept files (section 8.2). ADMIN only: it is a bulk copy of the catalog.</li>
 * </ul>
 *
 * <p>Bodies are written by {@link LifecycleStateJson} with its own mapper, not the application's,
 * so the bytes follow rule R6 whatever the global JSON settings: two calls at the same {@code asOf}
 * over the same data return identical bytes.
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class LifecycleStateController {

    /** Who may read Lifecycle State: the readers of retention state, and the machine principal. */
    static final String READ_ACCESS = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', "
            + "'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', "
            + "'ROLE_AUDITOR', 'ROLE_VIEWER', 'ROLE_SERVICE_ACCOUNT')";

    /** Who may export the whole catalog. */
    static final String EXPORT_ACCESS = "hasAuthority('ROLE_ADMIN')";

    /** Assets per database round trip of the export. */
    static final int EXPORT_BATCH = 200;

    private static final Logger log = LoggerFactory.getLogger(LifecycleStateController.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final UUID FIRST = new UUID(0L, 0L);

    private final LifecycleStateEmitter emitter;
    private final LifecycleStateProperties properties;

    /**
     * Creates the controller.
     *
     * @param emitter    builds the objects
     * @param properties the default page size
     */
    public LifecycleStateController(LifecycleStateEmitter emitter, LifecycleStateProperties properties) {
        this.emitter = emitter;
        this.properties = properties;
    }

    /**
     * The Lifecycle State of one asset at the current instant.
     *
     * @param assetId the asset
     * @return 200 with the object, 404 when the asset does not exist, 422 when the asset cannot be
     *         described in 0.1 (its URI does not normalize, or its provider or kind is not a 0.1 value)
     */
    @GetMapping(value = "/assets/{assetId}/lifecycle-state", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(READ_ACCESS)
    @Operation(summary = "Lifecycle State 0.1 of an asset",
            description = "docs/specs/lifecycle-state.md; valid against docs/specs/schemas/lifecycle-state-0.1.json")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The object",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LifecycleState.class))),
            @ApiResponse(responseCode = "404", description = "No such asset"),
            @ApiResponse(responseCode = "422", description = "The asset cannot be described in Lifecycle State 0.1")})
    public ResponseEntity<String> getLifecycleState(@PathVariable UUID assetId) {
        return emitter.emit(assetId)
                .map(o -> o.state() != null
                        ? json(HttpStatus.OK, LifecycleStateJson.compact(o.state()))
                        : error(HttpStatus.UNPROCESSABLE_ENTITY, "The asset cannot be described in Lifecycle State "
                                + LifecycleState.SPEC_VERSION + ": " + o.unrepresentable()))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "No asset with id " + assetId));
    }

    /**
     * A page of Lifecycle State objects, every asset of the catalog in {@code asset_id} order, all
     * evaluated at one instant. Assets that cannot be described in 0.1 are listed under
     * {@code unrepresentable} with the reason, never emitted with a guessed value.
     *
     * @param page the page number, from 0
     * @param size the page size, 1 to 500; default {@code dlap.catalog.lifecycle-state.default-page-size}
     * @return 200 with {@code specVersion}, {@code asOf}, {@code page}, {@code size},
     *         {@code totalElements}, {@code items} and {@code unrepresentable}; 400 when a parameter is
     *         out of range
     */
    @GetMapping(value = "/lifecycle-states", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(READ_ACCESS)
    @Operation(summary = "Lifecycle State 0.1 of every asset, paged",
            description = "items are valid against docs/specs/schemas/lifecycle-state-0.1.json")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The page"),
            @ApiResponse(responseCode = "400", description = "page or size out of range")})
    public ResponseEntity<String> listLifecycleStates(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(required = false) Integer size) {
        int pageSize = size == null ? properties.getDefaultPageSize() : size;
        if (page < 0 || pageSize < 1 || pageSize > LifecycleStateProperties.MAX_PAGE_SIZE) {
            return error(HttpStatus.BAD_REQUEST, "page must be 0 or more and size 1 to "
                    + LifecycleStateProperties.MAX_PAGE_SIZE);
        }
        LifecycleStateEmitter.Page result = emitter.page(page, pageSize, emitter.now());
        return json(HttpStatus.OK, LifecycleStateJson.compact(pageNode(result)));
    }

    /**
     * The OKF v0.1 export of every asset that can be described in 0.1, as a zip of
     * {@code lifecycle-state/<sha256>.md} concept files, all at one {@code asOf}.
     *
     * @return 200 with the zip, streamed
     */
    @GetMapping(value = "/lifecycle-states/okf", produces = "application/zip")
    @PreAuthorize(EXPORT_ACCESS)
    @Operation(summary = "OKF v0.1 export of Lifecycle State (zip of Markdown concept files)",
            description = "docs/specs/lifecycle-state.md section 8.2. ADMIN only.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The zip",
            content = @Content(mediaType = "application/zip"))})
    public ResponseEntity<StreamingResponseBody> exportOkf() {
        Instant asOf = emitter.now();
        StreamingResponseBody body = out -> export(asOf, out);
        String name = "lifecycle-state-okf-" + asOf.toString().replace(":", "") + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(body);
    }

    /**
     * Writes the export by walking the catalog in {@code asset_id} order.
     *
     * @param asOf the instant of every object
     * @param out  the response body
     * @throws IOException when writing fails
     */
    void export(Instant asOf, java.io.OutputStream out) throws IOException {
        int unrepresentable = 0;
        int files;
        try (OkfExporter.ZipWriter zip = OkfExporter.open(asOf, out)) {
            UUID after = FIRST;
            while (true) {
                List<LifecycleStateEmitter.Outcome> batch = emitter.after(after, EXPORT_BATCH, asOf);
                if (batch.isEmpty()) {
                    break;
                }
                for (LifecycleStateEmitter.Outcome o : batch) {
                    if (o.state() == null) {
                        unrepresentable++;
                    } else {
                        zip.add(o.state());
                    }
                }
                after = batch.get(batch.size() - 1).assetId();
            }
            files = zip.count();
        }
        log.info("OKF export as of {}: {} concept files, {} assets not representable in Lifecycle State {}",
                asOf, files, unrepresentable, LifecycleState.SPEC_VERSION);
    }

    private static ObjectNode pageNode(LifecycleStateEmitter.Page page) {
        ObjectNode n = NODES.objectNode();
        n.put("specVersion", LifecycleState.SPEC_VERSION);
        n.put("asOf", page.asOf().toString());
        n.put("page", page.page());
        n.put("size", page.size());
        n.put("totalElements", page.totalElements());
        ArrayNode items = n.putArray("items");
        ArrayNode skipped = n.putArray("unrepresentable");
        for (LifecycleStateEmitter.Outcome o : page.outcomes()) {
            if (o.state() != null) {
                items.add(LifecycleStateJson.toNode(o.state()));
            } else {
                ObjectNode s = skipped.addObject();
                s.put("assetId", o.assetId().toString());
                s.put("reason", o.unrepresentable());
            }
        }
        return n;
    }

    private static ResponseEntity<String> json(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<String> error(HttpStatus status, String message) {
        ObjectNode n = NODES.objectNode();
        n.put("status", status.value());
        n.put("error", status.getReasonPhrase());
        n.put("message", message);
        return json(status, LifecycleStateJson.compact(n));
    }
}
