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

package com.dlap.adminservice.config;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Provides the current auditor (principal) for Spring Data JPA auditing
 * ({@code @CreatedBy} / {@code @LastModifiedBy}) on entities that extend
 * {@link com.dlap.adminservice.model.AbstractAuditableEntity}.
 *
 * <p>Resolves the auditor in the following order:
 * <ol>
 *   <li>JWT claim {@code preferred_username} (Keycloak default)</li>
 *   <li>JWT claim {@code sub}</li>
 *   <li>{@link Authentication#getName()} fallback</li>
 *   <li>Literal {@code "system"} when no authentication is present (e.g. background jobs)</li>
 * </ol>
 */
@Component("springSecurityAuditorAware")
public class SpringSecurityAuditorAware implements AuditorAware<String> {

    private static final String SYSTEM_AUDITOR = "system";
    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    private static final String CLAIM_SUB = "sub";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.of(SYSTEM_AUDITOR);
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            String preferredUsername = jwt.getClaimAsString(CLAIM_PREFERRED_USERNAME);
            if (preferredUsername != null && !preferredUsername.isBlank()) {
                return Optional.of(preferredUsername);
            }
            String subject = jwt.getClaimAsString(CLAIM_SUB);
            if (subject != null && !subject.isBlank()) {
                return Optional.of(subject);
            }
        }

        String name = authentication.getName();
        if (name != null && !name.isBlank()) {
            return Optional.of(name);
        }
        return Optional.of(SYSTEM_AUDITOR);
    }
}
