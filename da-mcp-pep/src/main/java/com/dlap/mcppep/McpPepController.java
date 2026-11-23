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

package com.dlap.mcppep;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reference HTTP surface for the MCP PEP.
 *
 * <p>{@code POST /mcp/v1/authorize} accepts an {@link McpAccessRequest} and
 * returns the enforced verdict:
 * <ul>
 *   <li>allow → 200 {@code {"allow":true,"obligations":[...]}}</li>
 *   <li>deny  → 403 {@code {"allow":false,"reason":"..."}}</li>
 * </ul>
 *
 * <p>Authenticated (SEC-07): the caller presents a Keycloak bearer token with the configured
 * issuer and audience ({@link SecurityConfiguration}); no token or an invalid one, a token for
 * another audience included, is answered 401. Any authenticated caller whose token carries the PEP
 * audience gets a decision, whatever its roles ({@link PepAudienceGate}, Q-459, D29); the subject
 * binding below is what keeps that safe. The PDP call the PEP makes is
 * authenticated as well (a ROLE_SERVICE_ACCOUNT bearer token via {@link PdpTokenProvider}).
 * The subject of the evaluation comes from the token (PEP-02, Q-382, D26): for a user or agent token
 * {@code principalId}, {@code roles} and {@code tier} are the token's, and a body that names another
 * principal is answered 403. Only a {@code ROLE_SERVICE_ACCOUNT} caller (an MCP gateway) may name the
 * subject in the body ({@link SubjectBinder}).
 *
 * <p>GET {@code /actuator/health} (actuator inherited from the root build) returns
 * 200 when the PEP process is up; it does not probe the PDP.
 */
@RestController
@RequestMapping("/mcp/v1")
@Tag(name = "MCP PEP", description = "Reference MCP Policy Enforcement Point - maps a tool-call/resource-read to an AuthZEN verdict (ADR-0040)")
public class McpPepController {

    private static final Logger log = LoggerFactory.getLogger(McpPepController.class);

    /**
     * Any authenticated caller whose token carries the PEP audience: a user, an agent or an MCP
     * gateway. The audience is also checked by the token decoder, so this is the second line.
     */
    private static final String AUTHZ = "@pepAudienceGate.carriesPepAudience(authentication)";

    private final McpPolicyEnforcer enforcer;
    private final SubjectBinder subjectBinder;

    public McpPepController(McpPolicyEnforcer enforcer, SubjectBinder subjectBinder) {
        this.enforcer = enforcer;
        this.subjectBinder = subjectBinder;
    }

    @PostMapping("/authorize")
    @PreAuthorize(AUTHZ)
    @Operation(summary = "Enforce an MCP access request",
            description = "Maps the request to an AuthZEN evaluation, calls the DLAP PDP, "
                    + "and returns the enforced verdict: allow (200, with obligations) or deny (403, with reason). "
                    + "Any authenticated caller whose token carries the PEP audience gets a decision. "
                    + "The subject is taken from the bearer token; only a ROLE_SERVICE_ACCOUNT caller may name "
                    + "principalId, roles and tier in the body, and any other caller naming another principal is "
                    + "refused with 403.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Access allowed; obligations (if any) returned"),
        @ApiResponse(responseCode = "400", description = "Malformed access request"),
        @ApiResponse(responseCode = "401", description = "No bearer token, or the token is invalid (signature, expiry, issuer, or it does not carry the PEP audience)"),
        @ApiResponse(responseCode = "403", description = "Access denied by the PDP verdict, or a non-service-account token names another principal")
    })
    public ResponseEntity<Map<String, Object>> authorize(@RequestBody McpAccessRequest request,
            Authentication authentication) {
        McpAccessRequest bound;
        try {
            bound = subjectBinder.bind(request, authentication);
        } catch (SubjectMismatchException ex) {
            log.warn("Refused an access request that does not match the token subject (token subject {}): {}",
                    authentication.getName(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "allow", false,
                    "reason", ex.getMessage(),
                    "obligations", List.of(),
                    "fromCache", false,
                    "failClosed", false));
        }
        McpDecision decision = enforcer.enforce(bound);
        if (decision.allow()) {
            return ResponseEntity.ok(Map.of(
                    "allow", true,
                    "obligations", decision.obligations(),
                    "fromCache", decision.fromCache(),
                    "failClosed", decision.failClosed()));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "allow", false,
                "reason", nz(decision.reason()),
                "obligations", List.of(),
                "fromCache", decision.fromCache(),
                "failClosed", decision.failClosed()));
    }

    private static String nz(String s) {
        return s == null ? "denied" : s;
    }
}
