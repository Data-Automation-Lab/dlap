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

import com.dlap.common.constants.DlapConstants;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Binds the subject of an {@link McpAccessRequest} to the verified bearer token (PEP-02, Q-382
 * option b, D26).
 *
 * <p>Before this class the PEP trusted the body: any authenticated caller could ask for the verdict
 * of any principal with any roles and tier. The binding depends on who the caller is:
 * <ul>
 *   <li><b>Service account</b> (the token carries {@code ROLE_SERVICE_ACCOUNT}): an MCP gateway
 *       speaks for the agents and users behind it, so it may name the subject in the body. A body
 *       without a {@code principalId} falls back to the token's own subject.</li>
 *   <li><b>User or agent token</b> (any other token that reaches the PEP): the subject is the
 *       token's. {@code principalId} is the token subject ({@code sub}), {@code roles} are the
 *       token's realm roles without the {@code ROLE_} prefix, and {@code tier} is the token's
 *       {@code tier} claim (absent when the token has none). A body that names another principal
 *       is refused ({@link SubjectMismatchException}, answered 403); a body that repeats the token's
 *       own subject, or names none, is accepted. Roles and tier in the body are ignored, so a
 *       caller cannot raise its own tier by asserting one.</li>
 * </ul>
 *
 * <p>The binding runs before the decision cache and the PDP call, so both only ever see the bound
 * subject. Roles come from the token only, never from the body, for a non-service-account caller.
 */
@Component
public class SubjectBinder {

    /** Authority of a machine caller (an MCP gateway); the only one allowed to name the subject. */
    static final String SERVICE_ACCOUNT_AUTHORITY = "ROLE_" + DlapConstants.UserRole.SERVICE_ACCOUNT;

    /** Token claim that carries the ADR-0032 persona tier, when the realm emits one. */
    static final String TIER_CLAIM = "tier";

    private static final String ROLE_PREFIX = "ROLE_";

    /** Counter of bound requests, tagged by where the subject came from. */
    static final String BINDINGS_COUNTER = "dlap.mcppep.subject.bindings.total";

    private final MeterRegistry meterRegistry;

    public SubjectBinder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Returns the request the PEP enforces: the body for a service account (with the token subject
     * filled in when the body names none), the token's subject, roles and tier for anyone else.
     *
     * @param request the request as the caller sent it (not null)
     * @param authentication the verified caller (not null)
     * @return the request to enforce
     * @throws SubjectMismatchException when a user or agent token is used to ask about another
     *         principal, or when the token has no subject to bind to
     */
    public McpAccessRequest bind(McpAccessRequest request, Authentication authentication) {
        String tokenSubject = authentication.getName();
        if (isServiceAccount(authentication)) {
            return bindServiceAccount(request, tokenSubject);
        }
        if (tokenSubject == null || tokenSubject.isBlank()) {
            count("mismatch");
            throw new SubjectMismatchException("the token has no subject to bind the request to");
        }
        String named = request.principalId();
        if (named != null && !named.isBlank() && !named.equals(tokenSubject)) {
            count("mismatch");
            throw new SubjectMismatchException(
                    "principalId must be the subject of the token; only a service account may name another principal");
        }
        count("token");
        return new McpAccessRequest(tokenSubject, rolesOf(authentication), tierOf(authentication),
                request.tool(), request.resourceUri(), request.assetId(), request.action(), request.context());
    }

    private McpAccessRequest bindServiceAccount(McpAccessRequest request, String tokenSubject) {
        String named = request.principalId();
        if (named != null && !named.isBlank()) {
            count("body");
            return request;
        }
        if (tokenSubject == null || tokenSubject.isBlank()) {
            count("mismatch");
            throw new SubjectMismatchException("the request names no principalId and the token has no subject");
        }
        count("service-account");
        return new McpAccessRequest(tokenSubject, request.roles(), request.tier(), request.tool(),
                request.resourceUri(), request.assetId(), request.action(), request.context());
    }

    private static boolean isServiceAccount(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (SERVICE_ACCOUNT_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Realm roles of the token without the {@code ROLE_} prefix, the form the PDP matches on. Only
     * {@code ROLE_} authorities count: Spring Security 7 adds {@code FACTOR_*} authorities for the
     * authentication mechanism, and those are not roles.
     */
    private static List<String> rolesOf(Authentication authentication) {
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith(ROLE_PREFIX) && name.length() > ROLE_PREFIX.length()) {
                roles.add(name.substring(ROLE_PREFIX.length()));
            }
        }
        return List.copyOf(roles);
    }

    private static String tierOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Jwt jwt = jwtAuthentication.getToken();
            Object tier = jwt.getClaim(TIER_CLAIM);
            if (tier instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private void count(String source) {
        meterRegistry.counter(BINDINGS_COUNTER, "source", source).increment();
    }
}
