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

import java.util.Set;
import java.util.TreeSet;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.dlap.common.constants.DlapConstants;

/**
 * Who calls the approval API: the JWT {@code sub}, which is the identity every decision records,
 * the {@code preferred_username} for display, and the role authorities.
 *
 * <p>Only a bearer token identifies a caller. Any other authentication has no subject the
 * approval record can trust and is refused ({@link ApprovalException#NO_SUBJECT}).
 *
 * @param subject     the JWT {@code sub}
 * @param username    the JWT {@code preferred_username}; may be null
 * @param authorities the granted authorities, for example {@code ROLE_ADMIN}
 */
public record Caller(String subject, String username, Set<String> authorities) {

    /** Authority of service-account (client-credentials) tokens. */
    public static final String SERVICE_ACCOUNT = "ROLE_" + DlapConstants.UserRole.SERVICE_ACCOUNT;

    /** Authority of administrators, the only role that may break glass. */
    public static final String ADMIN = "ROLE_" + DlapConstants.UserRole.ADMIN;

    /**
     * Copies the authorities into a sorted set.
     */
    public Caller {
        authorities = authorities == null ? Set.of() : Set.copyOf(new TreeSet<>(authorities));
    }

    /**
     * The caller of the current request.
     *
     * @param authentication the Spring Security authentication
     * @return the caller
     * @throws ApprovalException when the authentication is not a JWT with a subject
     */
    public static Caller of(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw ApprovalException.forbidden(ApprovalException.NO_SUBJECT, "approvals need a bearer token");
        }
        String subject = token.getToken().getSubject();
        if (subject == null || subject.isBlank()) {
            throw ApprovalException.forbidden(ApprovalException.NO_SUBJECT, "the token has no sub claim");
        }
        Set<String> authorities = new TreeSet<>();
        for (GrantedAuthority a : token.getAuthorities()) {
            if (a.getAuthority() != null) {
                authorities.add(a.getAuthority());
            }
        }
        return new Caller(subject.strip(), token.getToken().getClaimAsString("preferred_username"), authorities);
    }

    /**
     * Whether the caller holds an authority.
     *
     * @param authority the authority, for example {@code ROLE_ADMIN}
     * @return true when held
     */
    public boolean has(String authority) {
        return authorities.contains(authority);
    }

    /**
     * Whether the caller is a service account.
     *
     * @return true for a client-credentials token
     */
    public boolean serviceAccount() {
        return has(SERVICE_ACCOUNT);
    }
}
