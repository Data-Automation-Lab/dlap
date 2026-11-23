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

package com.dlap.catalog.retention;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.dlap.catalog.common.ConflictException;
import com.dlap.catalog.common.ResourceNotFoundException;
import com.dlap.catalog.retention.RetentionAdminService.Actor;
import com.dlap.catalog.security.SecurityUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

/**
 * The retention API of the catalog (CAT-03): the retention state of an asset, the operator's
 * rules, and the legal hold.
 *
 * <p>Reading is open to every human role of the Access Model and to the machine principal (the
 * retire executors and the reporting service re-read before they act, ADR-0034). Changing a rule
 * or the hold needs a role that governs data: {@code ROLE_ADMIN}, {@code ROLE_CDO},
 * {@code ROLE_DIRECTOR} or {@code ROLE_POLICY_MANAGER}. Every change is audited and evaluates the
 * asset at once.
 */
@RestController
@RequestMapping("/api/v1/catalog/assets/{assetId}")
public class RetentionController {

    /** Who may read an asset's retention state. */
    static final String READ_ACCESS = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', "
            + "'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', "
            + "'ROLE_AUDITOR', 'ROLE_VIEWER', 'ROLE_SERVICE_ACCOUNT')";

    /** Who may add or remove a rule and place or release the legal hold. */
    static final String WRITE_ACCESS = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', "
            + "'ROLE_POLICY_MANAGER')";

    private final RetentionAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the retention service
     */
    public RetentionController(RetentionAdminService service) {
        this.service = service;
    }

    /**
     * The retention state of one asset and every rule that applies to it.
     *
     * @param assetId the asset
     * @return 200 with the state, 404 when the asset does not exist
     */
    @GetMapping("/retention")
    @PreAuthorize(READ_ACCESS)
    @Operation(summary = "Retention state and rules of an asset")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The state"),
            @ApiResponse(responseCode = "404", description = "No such asset")})
    public ResponseEntity<AssetRetentionView> getRetention(@PathVariable UUID assetId) {
        return ResponseEntity.ok(service.view(assetId));
    }

    /**
     * Adds an operator's retention rule (source MANUAL). The asset is evaluated again at once.
     *
     * @param assetId the asset
     * @param request the rule
     * @return 201 with the rule, 400 when it is malformed, 404 when the asset does not exist,
     *         409 when the asset carries the maximum number of rules
     */
    @PostMapping("/retention/rules")
    @PreAuthorize(WRITE_ACCESS)
    @Operation(summary = "Add a manual retention rule")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "The rule was stored"),
            @ApiResponse(responseCode = "400", description = "The rule is malformed"),
            @ApiResponse(responseCode = "404", description = "No such asset"),
            @ApiResponse(responseCode = "409", description = "Too many manual rules on the asset")})
    public ResponseEntity<RetentionRuleView> addRule(@PathVariable UUID assetId,
                                                     @Valid @RequestBody RetentionRuleRequest request) {
        RetentionRuleView created = service.addManualRule(assetId, request, actor());
        return ResponseEntity
                .created(URI.create("/api/v1/catalog/assets/" + assetId + "/retention/rules/" + created.ruleId()))
                .body(created);
    }

    /**
     * Removes an operator's rule. A policy's rule is not removable here.
     *
     * @param assetId the asset
     * @param ruleId  the rule
     * @return 204, or 404 when the asset has no manual rule with that id
     */
    @DeleteMapping("/retention/rules/{ruleId}")
    @PreAuthorize(WRITE_ACCESS)
    @Operation(summary = "Remove a manual retention rule")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "The rule was removed"),
            @ApiResponse(responseCode = "404", description = "No such manual rule")})
    public ResponseEntity<Void> removeRule(@PathVariable UUID assetId, @PathVariable UUID ruleId) {
        service.removeManualRule(assetId, ruleId, actor());
        return ResponseEntity.noContent().build();
    }

    /**
     * Places the legal hold. The status becomes ON_HOLD at once and nothing retires the asset
     * until the hold is released. Repeating the call changes nothing.
     *
     * @param assetId the asset
     * @param request the reason, recorded in the audit trail
     * @return 200 with the retention state, 404 when the asset does not exist
     */
    @PutMapping("/legal-hold")
    @PreAuthorize(WRITE_ACCESS)
    @Operation(summary = "Place a legal hold on an asset")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The hold is in place"),
            @ApiResponse(responseCode = "404", description = "No such asset")})
    public ResponseEntity<AssetRetentionView> placeHold(@PathVariable UUID assetId,
                                                        @Valid @RequestBody LegalHoldRequest request) {
        return ResponseEntity.ok(service.placeLegalHold(assetId, request.reason(), actor()));
    }

    /**
     * Releases the legal hold. The asset is evaluated again at once. Repeating the call changes
     * nothing.
     *
     * @param assetId the asset
     * @param request the reason, recorded in the audit trail
     * @return 200 with the retention state, 404 when the asset does not exist
     */
    @PostMapping("/legal-hold/release")
    @PreAuthorize(WRITE_ACCESS)
    @Operation(summary = "Release the legal hold of an asset")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The hold is released"),
            @ApiResponse(responseCode = "404", description = "No such asset")})
    public ResponseEntity<AssetRetentionView> releaseHold(@PathVariable UUID assetId,
                                                          @Valid @RequestBody LegalHoldRequest request) {
        return ResponseEntity.ok(service.releaseLegalHold(assetId, request.reason(), actor()));
    }

    private static Actor actor() {
        return new Actor(SecurityUtils.getAuthenticatedUsername(), SecurityUtils.getAuthenticatedUserId());
    }

    /**
     * A missing asset or rule is a 404. Handled here because the module's generic handler maps
     * every other exception to 500.
     *
     * @param e the exception
     * @return the error envelope
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Too many manual rules is a 409.
     *
     * @param e the exception
     * @return the error envelope
     */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * A body that is missing, not JSON, or names an unknown basis is a 400 (the module's generic
     * handler would answer 500).
     *
     * @param e the exception
     * @return the error envelope
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "The request body is missing or malformed");
    }

    /**
     * An identifier that is not a UUID is a 400.
     *
     * @param e the exception
     * @return the error envelope
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> badIdentifier(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "Parameter " + e.getName() + " has the wrong type");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }
}
