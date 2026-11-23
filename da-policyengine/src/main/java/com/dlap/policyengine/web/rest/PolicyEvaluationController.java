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
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.dlap.common.security.SecurityUtils;
import com.dlap.policyengine.dto.PolicyEvaluationOutputDTO;
import com.dlap.policyengine.dto.PolicyEvaluationRequestDTO;
import com.dlap.policyengine.dto.PolicyEvaluationSummaryDTO;
import com.dlap.policyengine.service.IPolicyEvaluationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/policyengine")
public class PolicyEvaluationController {

    private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationController.class);

    private final IPolicyEvaluationService policyEvaluationService;

    @Autowired
    public PolicyEvaluationController(IPolicyEvaluationService policyEvaluationService) {
        this.policyEvaluationService = policyEvaluationService;
    }

    @PostMapping("/{policyId}/evaluations")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<PolicyEvaluationOutputDTO> triggerPolicyEvaluation(
            @PathVariable UUID policyId,
            @Valid @RequestBody PolicyEvaluationRequestDTO evaluationRequest,
            Authentication authentication) {
        log.info("REST request to trigger evaluation for Policy: {}", policyId);

        UUID triggeredByUserId = SecurityUtils.getAuthenticatedUserId();
        // Q-569: the caller's JWT sub is an excluded approver of every request this evaluation creates.
        String triggeredBySubject = subjectOf(authentication);
        PolicyEvaluationOutputDTO evaluation = policyEvaluationService.triggerPolicyEvaluation(policyId, evaluationRequest, triggeredByUserId, triggeredBySubject);
        
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{evaluationId}")
                .buildAndExpand(evaluation.getId())
                .toUri();
        
        return ResponseEntity.created(location).body(evaluation);
    }

    @GetMapping("/evaluations")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR')")
    public ResponseEntity<Page<PolicyEvaluationSummaryDTO>> getPolicyEvaluations(
            @PageableDefault(size = 50, sort = "evaluatedAt") Pageable pageable,
            @RequestParam(required = false) UUID policyId,
            @RequestParam(required = false) String targetAssetId,
            @RequestParam(required = false) String status) {
        log.info("REST request to get Policy Evaluations with filters: policyId={}, targetAssetId={}, status={}", policyId, targetAssetId, status);
        Page<PolicyEvaluationSummaryDTO> resultPage = policyEvaluationService.getPolicyEvaluations(pageable, policyId, targetAssetId, status);
        return ResponseEntity.ok(resultPage);
    }

    @GetMapping("/evaluations/{evaluationId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR')")
    public ResponseEntity<PolicyEvaluationOutputDTO> getPolicyEvaluationById(@PathVariable UUID evaluationId) {
        log.info("REST request to get Policy Evaluation by id: {}", evaluationId);
        PolicyEvaluationOutputDTO evaluationDTO = policyEvaluationService.getPolicyEvaluationById(evaluationId);
        return ResponseEntity.ok(evaluationDTO);
    }

    /**
     * The JWT {@code sub} of the caller, which approval records use as the person's identity.
     * Only a bearer token with a subject can trigger an evaluation, because a fire that waits for
     * approval must name who started it.
     *
     * @param authentication the caller's authentication
     * @return the subject
     * @throws AccessDeniedException when the caller has no JWT subject (answered 403)
     */
    private static String subjectOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            String subject = token.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject.strip();
            }
        }
        throw new AccessDeniedException("an on-demand evaluation needs a bearer token with a sub claim");
    }
}
