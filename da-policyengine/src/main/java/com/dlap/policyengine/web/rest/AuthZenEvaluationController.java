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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.policyengine.access.AccessDecision;
import com.dlap.policyengine.access.AccessDecisionEventProducer;
import com.dlap.policyengine.access.AccessDecisionService;
import com.dlap.policyengine.access.AuthZenEvaluationRequest;
import com.dlap.policyengine.access.AuthZenEvaluationResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * AuthZEN 1.0 Policy Decision Point endpoints (ADR-0040,
 * enforcement-contract.md §1-3). PEPs - MCP gateways, OPA sidecars,
 * AuthZEN-capable IAM - POST an access request and receive a deterministic
 * verdict; every verdict also emits a decision-log event (§6).
 *
 * <p>Authorized for M2M service accounts (ADR-0034 {@code ROLE_SERVICE_ACCOUNT})
 * and {@code ROLE_ADMIN}, matching how other DLAP endpoints admit service-to-
 * service callers (e.g. {@code PolicyController}'s metrics endpoints).
 */
@RestController
@RequestMapping("/access/v1")
@Tag(name = "Access Decision (AuthZEN PDP)",
        description = "Consumed-stage per-request access verdicts (ADR-0040)")
public class AuthZenEvaluationController {

    private static final Logger log = LoggerFactory.getLogger(AuthZenEvaluationController.class);

    private static final String AUTHZ =
            "hasAnyAuthority('ROLE_SERVICE_ACCOUNT', 'ROLE_ADMIN')";

    private final AccessDecisionService accessDecisionService;
    private final AccessDecisionEventProducer eventProducer;

    public AuthZenEvaluationController(AccessDecisionService accessDecisionService,
                                       AccessDecisionEventProducer eventProducer) {
        this.accessDecisionService = accessDecisionService;
        this.eventProducer = eventProducer;
    }

    /** Single access evaluation (AuthZEN {@code POST /access/v1/evaluation}). */
    @PostMapping("/evaluation")
    @PreAuthorize(AUTHZ)
    @Operation(summary = "Evaluate a single access request (AuthZEN 1.0)",
            description = "Returns a deterministic allow/deny verdict for one subject-action-resource "
                    + "request and emits a decision-log event. Verdict context carries the decision id, "
                    + "reason, and any obligations.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Verdict returned (decision field is true/false)"),
        @ApiResponse(responseCode = "400", description = "Malformed or invalid evaluation request"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_SERVICE_ACCOUNT / ROLE_ADMIN")
    })
    public ResponseEntity<AuthZenEvaluationResponse> evaluate(
            @Valid @RequestBody AuthZenEvaluationRequest request) {
        return ResponseEntity.ok(evaluateAndAudit(request));
    }

    /**
     * Batch access evaluation (AuthZEN boxcarring,
     * {@code POST /access/v1/evaluations}). Each entry is evaluated and audited
     * independently; results are returned in request order under
     * {@code evaluations}.
     */
    @PostMapping("/evaluations")
    @PreAuthorize(AUTHZ)
    @Operation(summary = "Evaluate a batch of access requests (AuthZEN 1.0 boxcarring)",
            description = "Evaluates and audits each request independently and returns the verdicts in "
                    + "request order under the 'evaluations' key.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Per-request verdicts returned in request order"),
        @ApiResponse(responseCode = "400", description = "Malformed or invalid batch request"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_SERVICE_ACCOUNT / ROLE_ADMIN")
    })
    public ResponseEntity<Map<String, Object>> evaluateBatch(
            @Valid @RequestBody AuthZenBatchRequest batch) {
        List<AuthZenEvaluationResponse> results = new ArrayList<>();
        if (batch.getEvaluations() != null) {
            for (AuthZenEvaluationRequest req : batch.getEvaluations()) {
                results.add(evaluateAndAudit(req));
            }
        }
        return ResponseEntity.ok(Map.of("evaluations", results));
    }

    /** Deterministic verdict + failure-isolated decision-log publish + AuthZEN mapping. */
    private AuthZenEvaluationResponse evaluateAndAudit(AuthZenEvaluationRequest request) {
        AccessDecisionService.EvaluationResult result = accessDecisionService.evaluate(request);
        AccessDecision decision = result.decision();
        // Publishing is non-blocking and failure-isolated inside the producer.
        eventProducer.publish(request, decision, result.resolved(), Instant.now().toString());
        if (log.isDebugEnabled()) {
            log.debug("Access decision {} -> {} ({} ms)",
                    decision.decisionId(), decision.decisionLabel(), decision.latencyMs());
        }
        return toResponse(decision);
    }

    private static AuthZenEvaluationResponse toResponse(AccessDecision decision) {
        List<AuthZenEvaluationResponse.Obligation> obligations = null;
        if (decision.obligations() != null && !decision.obligations().isEmpty()) {
            obligations = new ArrayList<>();
            for (AccessDecision.PolicyDslObligation o : decision.obligations()) {
                obligations.add(new AuthZenEvaluationResponse.Obligation(o.type(), o.labels()));
            }
        }
        AuthZenEvaluationResponse.Context context = new AuthZenEvaluationResponse.Context(
                decision.decisionId(),
                Map.of("en", decision.reason()),
                obligations);
        return new AuthZenEvaluationResponse(decision.allow(), context);
    }

    /** Batch request wrapper: {@code { "evaluations": [ <request>, ... ] }}. */
    public static class AuthZenBatchRequest {
        @Valid
        private List<AuthZenEvaluationRequest> evaluations;

        public List<AuthZenEvaluationRequest> getEvaluations() {
            return evaluations;
        }

        public void setEvaluations(List<AuthZenEvaluationRequest> evaluations) {
            this.evaluations = evaluations;
        }
    }
}
