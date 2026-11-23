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

package com.dlap.autodelete.controller;

import com.dlap.autodelete.dto.WouldDeleteRowDTO;
import com.dlap.autodelete.retire.WouldDeleteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The would-delete list (RET-02): what the delete executor holds for deletion, soonest first, and
 * the native rule or engine delete it would apply.
 */
@RestController
@RequestMapping("/api/v1/autodelete/would-delete")
@Tag(name = "Would delete", description = "Assets held for deletion and the rule that would delete them")
public class WouldDeleteController {

    private final WouldDeleteService service;

    /**
     * Creates the controller.
     *
     * @param service the service
     */
    public WouldDeleteController(WouldDeleteService service) {
        this.service = service;
    }

    /**
     * One page of the list.
     *
     * @param page the page, from 0
     * @param size the page size, 1 to 200
     * @return the rows and the page counts
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR')")
    @Operation(summary = "List the assets held for deletion and the rule that would delete each")
    public ResponseEntity<WouldDeletePage> list(@RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Page<WouldDeleteRowDTO> rows = service.list(page, size);
        return ResponseEntity.ok(new WouldDeletePage(rows.getContent(), rows.getNumber(), rows.getSize(),
                rows.getTotalElements(), rows.getTotalPages(), Map.of("states", WouldDeleteService.STATES)));
    }

    /**
     * The page.
     *
     * @param content       the rows
     * @param page          the page
     * @param size          the size
     * @param totalElements all rows
     * @param totalPages    pages at this size
     * @param meta          the states the list shows
     */
    public record WouldDeletePage(List<WouldDeleteRowDTO> content, int page, int size, long totalElements,
            int totalPages, Map<String, List<String>> meta) {
    }
}
