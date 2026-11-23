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

package com.dlap.policyengine.approval;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API of the approval service (APPR-01, docs/specs/approvals.md, "REST API"). Authorization
 * follows docs/specs/access-model.md: practitioners and above (and service accounts) ask; the
 * Executive and Leadership tiers decide; only ADMIN breaks glass; AUDITOR reads.
 */
@RestController
@RequestMapping("/api/v1/policyengine/approvals")
@Tag(name = "Approvals", description = "Dual control for destructive lifecycle actions")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalService service;

    /**
     * Creates the controller.
     *
     * @param service the approval service
     */
    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    /**
     * Asks for an approval. A retry with the same {@code idempotencyKey} returns the first request
     * with 200; a new request answers 201.
     *
     * @param body           the request
     * @param authentication the caller
     * @return the request
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', 'ROLE_POLICY_MANAGER', "
            + "'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_SERVICE_ACCOUNT')")
    @Operation(summary = "Ask for approval of a destructive action")
    @ApiResponse(responseCode = "201", description = "A new request")
    @ApiResponse(responseCode = "200", description = "The earlier request with the same idempotencyKey")
    @ApiResponse(responseCode = "400", description = "Invalid body", content = @Content)
    @ApiResponse(responseCode = "403", description = "onBehalfOf from a caller that is not a service account", content = @Content)
    public ResponseEntity<ApprovalApi.ApprovalView> create(@Valid @RequestBody ApprovalApi.ApprovalCreateBody body,
            Authentication authentication) {
        ApprovalService.Created created = service.create(new ApprovalService.CreateCommand(body.actionClass(),
                body.actionType(), body.assetId(), body.assetUri(), body.provider(), body.serviceType(),
                body.actionParameters(), body.reason(), body.requiredApprovals(), body.idempotencyKey(),
                body.onBehalfOf()), Caller.of(authentication));
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApprovalApi.ApprovalView.of(created.detail()));
    }

    /**
     * Lists requests, newest first.
     *
     * @param status      only this state
     * @param actionClass only this class
     * @param assetId     only this asset
     * @param page        the page, from 0
     * @param size        the page size, 1 to 200
     * @return the page
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', 'ROLE_POLICY_MANAGER', "
            + "'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    @Operation(summary = "List approval requests")
    public ApprovalApi.ApprovalPageView list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String actionClass, @RequestParam(required = false) UUID assetId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ApprovalApi.ApprovalPageView.of(service.list(status(status), actionClass(actionClass), assetId, page, size));
    }

    /**
     * The pending requests the caller may still decide, soonest expiry first.
     *
     * @param page           the page, from 0
     * @param size           the page size, 1 to 200
     * @param authentication the caller
     * @return the page
     */
    @GetMapping("/inbox")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', 'ROLE_POLICY_MANAGER')")
    @Operation(summary = "Approval requests waiting for the caller")
    public ApprovalApi.ApprovalPageView inbox(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, Authentication authentication) {
        return ApprovalApi.ApprovalPageView.of(service.inbox(Caller.of(authentication), page, size));
    }

    /**
     * Reads one request with its decisions. Executors re-read a request here before they act.
     *
     * @param id the request id
     * @return the request
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', 'ROLE_POLICY_MANAGER', "
            + "'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    @Operation(summary = "Read one approval request")
    public ApprovalApi.ApprovalView get(@PathVariable UUID id) {
        return ApprovalApi.ApprovalView.of(service.get(id));
    }

    /**
     * Approves or rejects a request, with a comment.
     *
     * @param id             the request id
     * @param body           the decision
     * @param authentication the caller
     * @return the request after the decision
     */
    @PostMapping("/{id}/decisions")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', 'ROLE_POLICY_MANAGER')")
    @Operation(summary = "Approve or reject an approval request")
    @ApiResponse(responseCode = "200", description = "The request after the decision")
    @ApiResponse(responseCode = "400", description = "Invalid body, or a comment or justification too short", content = @Content)
    @ApiResponse(responseCode = "403", description = "Not an approver, no MFA marker, or the requester (reason field)", content = @Content)
    @ApiResponse(responseCode = "404", description = "No such request", content = @Content)
    @ApiResponse(responseCode = "409", description = "Not pending, expired, or already decided by the caller", content = @Content)
    public ApprovalApi.ApprovalView decide(@PathVariable UUID id, @Valid @RequestBody ApprovalApi.ApprovalDecisionBody body,
            Authentication authentication) {
        return ApprovalApi.ApprovalView.of(service.decide(id, decision(body.decision()), body.comment(),
                Caller.of(authentication)));
    }

    /**
     * Releases a request without the quorum. Alerts.
     *
     * @param id             the request id
     * @param body           the justification
     * @param authentication the caller
     * @return the request after the release
     */
    @PostMapping("/{id}/break-glass")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Break glass: release an approval request without the quorum")
    @ApiResponse(responseCode = "200", description = "The request after the decision")
    @ApiResponse(responseCode = "400", description = "Invalid body, or a comment or justification too short", content = @Content)
    @ApiResponse(responseCode = "403", description = "Not an approver, no MFA marker, or the requester (reason field)", content = @Content)
    @ApiResponse(responseCode = "404", description = "No such request", content = @Content)
    @ApiResponse(responseCode = "409", description = "Not pending, expired, or already decided by the caller", content = @Content)
    public ApprovalApi.ApprovalView breakGlass(@PathVariable UUID id,
            @Valid @RequestBody ApprovalApi.ApprovalBreakGlassBody body, Authentication authentication) {
        return ApprovalApi.ApprovalView.of(service.breakGlass(id, body.justification(), Caller.of(authentication)));
    }

    /**
     * Withdraws a pending request on behalf of a person. Service accounts only (APPR-03, Q-676): an
     * executor whose own work makes a request pointless, for example a restore of the record a hard
     * delete waits on, ends it without being able to decide it. Not a decision: the separation of
     * duties rules do not apply, and the result is as final as any other.
     *
     * @param id             the request id
     * @param body           the reason and the person
     * @param authentication the caller
     * @return the request after the withdrawal
     */
    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('ROLE_SERVICE_ACCOUNT')")
    @Operation(summary = "Withdraw a pending approval request on behalf of a person (service accounts only)")
    @ApiResponse(responseCode = "200", description = "The request, now WITHDRAWN")
    @ApiResponse(responseCode = "400", description = "Invalid body: a reason shorter than 10 characters, or no onBehalfOf", content = @Content)
    @ApiResponse(responseCode = "403", description = "The caller is not a service account", content = @Content)
    @ApiResponse(responseCode = "404", description = "No such request", content = @Content)
    @ApiResponse(responseCode = "409", description = "Not pending: the body carries the current status as status", content = @Content)
    @ApiResponse(responseCode = "503", description = "The audit log could not record the withdrawal; nothing changed", content = @Content)
    public ApprovalApi.ApprovalView withdraw(@PathVariable UUID id,
            @Valid @RequestBody ApprovalApi.ApprovalWithdrawBody body, Authentication authentication) {
        return ApprovalApi.ApprovalView.of(service.withdraw(id, body.reason(), body.onBehalfOf(),
                Caller.of(authentication)));
    }

    /**
     * Answers a refused operation with its status and reason, in the service's error shape. A
     * refused state change (a withdrawal of a request that is not pending) answers the request's
     * current state as {@code status}, as the withdraw contract says; {@code httpStatus} then
     * carries the HTTP code.
     *
     * @param e       the refusal
     * @param request the HTTP request
     * @return the error body
     */
    @ExceptionHandler(ApprovalException.class)
    public ResponseEntity<Map<String, Object>> refused(ApprovalException e, HttpServletRequest request) {
        log.warn("Approval operation refused at {}: {} ({})", request.getRequestURI(), e.reason(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        Instant now = service.now();
        body.put("timestamp", now == null ? null : now.toString());
        if (e.currentStatus() != null) {
            body.put("status", e.currentStatus());
            body.put("httpStatus", e.status().value());
        } else {
            body.put("status", e.status().value());
        }
        body.put("error", e.status().getReasonPhrase());
        body.put("message", e.getMessage());
        body.put("reason", e.reason());
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(e.status()).body(body);
    }

    private static ApprovalStatus status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ApprovalStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApprovalException.invalid("status must be one of PENDING, APPROVED, APPROVED_BREAK_GLASS, "
                    + "REJECTED, EXPIRED, WITHDRAWN");
        }
    }

    private static ActionClass actionClass(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ActionClass.parse(value);
        } catch (IllegalArgumentException e) {
            throw ApprovalException.invalid(e.getMessage());
        }
    }

    private static DecisionKind decision(String value) {
        String v = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!DecisionKind.APPROVE.name().equals(v) && !DecisionKind.REJECT.name().equals(v)) {
            throw ApprovalException.invalid("decision must be APPROVE or REJECT");
        }
        return DecisionKind.valueOf(v);
    }
}
