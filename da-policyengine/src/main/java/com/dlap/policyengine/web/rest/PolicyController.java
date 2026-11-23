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

package com.dlap.policyengine.web.rest;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.dlap.common.security.SecurityUtils;
import com.dlap.policyengine.approval.AuditUnavailableException;
import com.dlap.policyengine.dto.PolicyImpactRequestDTO;
import com.dlap.policyengine.dto.PolicyImpactResponseDTO;
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.model.PolicyVersionView;
import com.dlap.policyengine.dto.PolicyOutputDTO;
import com.dlap.policyengine.dto.PolicySummaryDTO;
import com.dlap.policyengine.dto.PolicyTextDraftRequestDTO;
import com.dlap.policyengine.dto.PolicyTextDraftResponseDTO;
import com.dlap.policyengine.service.IPolicyService;
import com.dlap.policyengine.service.PolicyDraftFromTextService;
import com.dlap.policyengine.service.PolicyEngineMetricsService;
import com.dlap.policyengine.service.PolicyHistoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/policyengine/policies")
@Tag(name = "Policy Management", description = "Endpoints for managing data governance policies")
public class PolicyController {

    private static final Logger log = LoggerFactory.getLogger(PolicyController.class);

    /**
     * SEC-01: a legacy policy rule condition is MVEL, which is executable code. Policy
     * authors may save a policy that carries no legacy rules (a DSL document, ADR-0031);
     * any legacy rule requires ROLE_ADMIN. Evaluated against the request-body parameter
     * named {@code policyInputDTO}.
     */
    private static final String POLICY_RULES_ADMIN_ONLY =
        "hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')"
        + " and (hasAuthority('ROLE_ADMIN') or #policyInputDTO.rules == null or #policyInputDTO.rules.isEmpty())";

    /** Who may read a policy's history: the policy readers and the auditor (AUD-02a). */
    private static final String HISTORY_READERS = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', "
        + "'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_AUDITOR')";

    /** Machine reason of a 503 when the audit log could not record a policy change. */
    public static final String AUDIT_UNAVAILABLE = "audit-unavailable";

    private final IPolicyService policyService;
    private final PolicyEngineMetricsService policyEngineMetricsService;
    private final PolicyDraftFromTextService policyDraftFromTextService;
    private final PolicyHistoryService policyHistory;

    /**
     * Creates the controller.
     *
     * @param policyService              the policy service
     * @param policyEngineMetricsService the metrics behind the dashboard endpoints
     * @param policyDraftFromTextService the plain-English drafting service
     * @param policyHistory              the policy history (versions)
     */
    @Autowired
    public PolicyController(IPolicyService policyService,
                            PolicyEngineMetricsService policyEngineMetricsService,
                            PolicyDraftFromTextService policyDraftFromTextService,
                            PolicyHistoryService policyHistory) {
        this.policyService = policyService;
        this.policyEngineMetricsService = policyEngineMetricsService;
        this.policyDraftFromTextService = policyDraftFromTextService;
        this.policyHistory = policyHistory;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<Page<PolicySummaryDTO>> getAllPolicies(
            @PageableDefault(size = 20, sort = "name") Pageable pageable,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String nameContains) {
        log.info("REST request to get all Policies with filters: status={}, nameContains={}", status, nameContains);
        Page<PolicySummaryDTO> policyPage = policyService.getAllPolicies(pageable, status, nameContains);
        return ResponseEntity.ok(policyPage);
    }

    @GetMapping("/{policyId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<PolicyOutputDTO> getPolicyById(@PathVariable UUID policyId) {
        log.info("REST request to get Policy by id: {}", policyId);
        PolicyOutputDTO policyDTO = policyService.getPolicyById(policyId);
        return ResponseEntity.ok(policyDTO);
    }

    @PostMapping
    @PreAuthorize(POLICY_RULES_ADMIN_ONLY)
    public ResponseEntity<PolicyOutputDTO> createPolicy(
            @Valid @RequestBody @P("policyInputDTO") PolicyInputDTO policyInputDTO) {
        log.info("REST request to create Policy: {}", policyInputDTO.getName());
        
        UUID creatorUserId = SecurityUtils.getAuthenticatedUserId();
        PolicyOutputDTO createdPolicy = policyService.createPolicy(policyInputDTO, creatorUserId);
        
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(createdPolicy.getId())
                .toUri();
        
        return ResponseEntity.created(location).body(createdPolicy);
    }

    @PutMapping("/{policyId}")
    @PreAuthorize(POLICY_RULES_ADMIN_ONLY)
    public ResponseEntity<PolicyOutputDTO> updatePolicy(
            @PathVariable UUID policyId,
            @Valid @RequestBody @P("policyInputDTO") PolicyInputDTO policyInputDTO) {
        log.info("REST request to update Policy: {}", policyId);
        
        UUID updaterUserId = SecurityUtils.getAuthenticatedUserId();
        PolicyOutputDTO updatedPolicy = policyService.updatePolicy(policyId, policyInputDTO, updaterUserId);
        
        return ResponseEntity.ok(updatedPolicy);
    }

    @PatchMapping("/{policyId}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    public ResponseEntity<PolicyOutputDTO> setPolicyStatus(
            @PathVariable UUID policyId,
            @RequestParam PolicyStatus status) {
        log.info("REST request to set Policy {} status to {}", policyId, status);
        UUID updaterUserId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(policyService.setPolicyStatus(policyId, status, updaterUserId));
    }

    /**
     * Deletes a policy softly (AUD-02a): it is disabled and frozen, it disappears from the policy list
     * and the evaluators, and its history stays readable under {@code /{policyId}/versions}.
     *
     * @param policyId the policy
     * @return 204; 404 for an unknown or already deleted policy; 503 when the audit log cannot record it
     */
    @DeleteMapping("/{policyId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    @Operation(summary = "Delete a policy (soft delete)",
        description = "Disables the policy, stamps it deleted and freezes it. It disappears from the policy list "
                + "and the evaluators; its versions stay readable. The change is a new DELETED version, chained "
                + "in the audit stream govern.policy.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Deleted"),
        @ApiResponse(responseCode = "404", description = "No live policy with this id"),
        @ApiResponse(responseCode = "503", description = "The audit log could not record the change; nothing changed")
    })
    public ResponseEntity<Void> deletePolicy(@PathVariable UUID policyId) {
        log.info("REST request to delete Policy by id: {}", policyId);
        policyService.deletePolicy(policyId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists the versions of a policy, oldest first, without their content (AUD-02a). A deleted policy
     * keeps its versions; the last one is {@code DELETED}.
     *
     * @param policyId the policy
     * @return the versions; empty for a policy that has not changed since policy history began
     */
    @GetMapping("/{policyId}/versions")
    @PreAuthorize(HISTORY_READERS)
    @Operation(summary = "List the versions of a policy",
        description = "Every change of a policy (create, update, status change, delete) is an immutable version, "
                + "chained in the audit stream govern.policy at auditSeq. Oldest first, without content; a deleted "
                + "policy keeps its versions.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "The versions"),
        @ApiResponse(responseCode = "404", description = "No policy and no version with this id")
    })
    public ResponseEntity<List<PolicyVersionView>> getPolicyVersions(@PathVariable UUID policyId) {
        return ResponseEntity.ok(policyHistory.versions(policyId));
    }

    /**
     * Reads one version of a policy with its content (AUD-02a).
     *
     * @param policyId the policy
     * @param version  the version number, from 1
     * @return the version
     */
    @GetMapping("/{policyId}/versions/{version}")
    @PreAuthorize(HISTORY_READERS)
    @Operation(summary = "Read one version of a policy",
        description = "The version with its content: description, conditionLogic, actions and policyDocument as "
                + "they were. contentSha256 is the SHA-256 of the stored snapshot and is also in the audit entry.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "The version"),
        @ApiResponse(responseCode = "404", description = "The policy has no such version")
    })
    public ResponseEntity<PolicyVersionView> getPolicyVersion(@PathVariable UUID policyId,
            @PathVariable int version) {
        return ResponseEntity.ok(policyHistory.version(policyId, version));
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, String reason,
            HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        java.time.Instant now = policyHistory.now();
        body.put("timestamp", now == null ? null : now.toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * A policy change the audit log could not record was rolled back: 503 {@code audit-unavailable},
     * never 400 or 500 (AUD-02a, Q-692). The driver's message stays in the log.
     *
     * @param e       the failure
     * @param request the request
     * @return the error
     */
    @ExceptionHandler(AuditUnavailableException.class)
    public ResponseEntity<Map<String, Object>> auditUnavailable(AuditUnavailableException e,
            HttpServletRequest request) {
        log.error("Policy change at {} was not made: the audit log could not record it ({})",
                request.getRequestURI(), e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "the audit log could not record the policy change; nothing was changed", AUDIT_UNAVAILABLE, request);
    }

    @GetMapping("/count/active")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Long> countActivePolicies() {
        return ResponseEntity.ok(policyEngineMetricsService.getActivePoliciesCount());
    }

    @GetMapping("/violations/count")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Long> countViolations() {
        return ResponseEntity.ok(policyEngineMetricsService.getPolicyViolationsCount());
    }

    @GetMapping("/compliance-rate")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Double> complianceRate() {
        return ResponseEntity.ok(policyEngineMetricsService.getPolicyComplianceRate());
    }

    @GetMapping("/compliance-breakdown")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Map<String, Object>> complianceBreakdown() {
        return ResponseEntity.ok(policyEngineMetricsService.getPolicyComplianceBreakdown());
    }

    @GetMapping("/action-items")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<List<Map<String, Object>>> policyActionItems(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") Integer limit) {
        return ResponseEntity.ok(policyEngineMetricsService.getPolicyActionItems(severity, status, limit));
    }

    @GetMapping("/compliance-history")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<List<Map<String, Object>>> complianceHistory(
            @RequestParam(defaultValue = "30") Integer days) {
        return ResponseEntity.ok(policyEngineMetricsService.getPolicyComplianceHistory(days));
    }

    @PostMapping("/analyze-impact")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @Operation(
        summary = "Analyze policy impact before implementation",
        description = "Provides comprehensive analysis of potential policy impacts including affected assets, performance estimates, cost implications, and compliance analysis. Supports FULL, QUICK, and TARGETED analysis types."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200", 
            description = "Policy impact analysis completed successfully"
        ),
        @ApiResponse(
            responseCode = "400", 
            description = "Invalid analysis request parameters"
        ),
        @ApiResponse(
            responseCode = "403", 
            description = "Insufficient permissions to perform policy analysis"
        )
    })
    public ResponseEntity<PolicyImpactResponseDTO> analyzePolicyImpact(
            @Parameter(description = "Policy impact analysis request with rules content and analysis parameters")
            @Valid @RequestBody PolicyImpactRequestDTO request) {
        log.info("REST request to analyze policy impact with analysis type: {}", request.getAnalysisType());
        
        PolicyImpactResponseDTO response = policyService.analyzePolicy(request);
        
        log.info("Policy impact analysis completed for analysis ID: {}", response.getAnalysisId());
        return ResponseEntity.ok(response);
    }

    /**
     * Drafts a policy from a plain-English description (ADR-0044).
     *
     * <p><b>Draft only.</b> Nothing is persisted and no event is published. The
     * UI renders the returned document in the visual builder so a human can
     * inspect and correct it; creating the policy still goes through
     * {@code POST /api/v1/policyengine/policies}.
     *
     * <p>Status codes: {@code 200} a draft was produced; {@code 422} the request
     * could not be expressed in the DSL, with {@code warnings} explaining why and
     * no DSL returned; {@code 503} no LLM provider is configured - never a
     * fabricated policy.
     *
     * @param request The plain-English text and optional owner.
     * @return The draft, or a warnings-only body when drafting was refused.
     */
    @PostMapping("/draft-from-text")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_POLICY_MANAGER')")
    @Operation(
        summary = "Draft a policy from plain English",
        description = "Translates a natural-language description into a canonical dlap/v1 policy DSL draft. "
                + "Persists nothing: the draft must be confirmed by a human in the visual builder before "
                + "the create-policy endpoint is called. Destructive actions are always drafted with "
                + "dryRun and requireApproval enabled."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Draft produced (not saved)"),
        @ApiResponse(responseCode = "400", description = "Invalid request body"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to draft policies"),
        @ApiResponse(responseCode = "422", description = "The request could not be expressed in the policy DSL"),
        @ApiResponse(responseCode = "503", description = "No LLM provider is configured")
    })
    public ResponseEntity<PolicyTextDraftResponseDTO> draftPolicyFromText(
            @Parameter(description = "Plain-English policy description and optional owner")
            @Valid @RequestBody PolicyTextDraftRequestDTO request) {
        log.info("REST request to draft a policy from text ({} chars)", request.text().length());

        PolicyDraftFromTextService.Draft draft =
                policyDraftFromTextService.draftFromText(request.text(), request.owner());

        PolicyTextDraftResponseDTO body = new PolicyTextDraftResponseDTO(
                draft.dslYaml(), draft.structured(), draft.confidence(),
                draft.assumptions(), draft.warnings());

        HttpStatus status = switch (draft.outcome()) {
            case DRAFTED -> HttpStatus.OK;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        log.info("Policy draft-from-text outcome: {}", draft.outcome());
        return ResponseEntity.status(status).body(body);
    }
}