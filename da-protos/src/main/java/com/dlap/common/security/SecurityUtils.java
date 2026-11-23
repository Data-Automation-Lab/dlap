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

package com.dlap.common.security;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Utility class for Spring Security operations related to authentication and authorization.
 * Handles extraction of user information from JWT tokens issued by Keycloak.
 * 
 * This is a shared utility class used across all DLAP microservices.
 */
public final class SecurityUtils {

    private static final Logger log = LoggerFactory.getLogger(SecurityUtils.class);
    
    private static final String SYSTEM_USER_ID = "00000000-0000-0000-0000-000000000000";
    private static final String USER_ID_CLAIM = "sub"; // Standard JWT subject claim
    private static final String PREFERRED_USERNAME_CLAIM = "preferred_username";
    
    private SecurityUtils() {
        // Utility class
    }

    /**
     * Get the authenticated user ID from the Spring Security context.
     * Extracts the user ID from the JWT token's 'sub' claim.
     * 
     * @return UUID of the authenticated user, or system user ID if not authenticated
     */
    public static UUID getAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null) {
            log.warn("No authentication found in security context, returning system user ID");
            return UUID.fromString(SYSTEM_USER_ID);
        }
        
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            Jwt jwt = jwtToken.getToken();
            String userIdClaim = jwt.getClaimAsString(USER_ID_CLAIM);
            
            if (userIdClaim != null) {
                try {
                    return UUID.fromString(userIdClaim);
                } catch (IllegalArgumentException e) {
                    log.error("Invalid UUID format in JWT sub claim: {}", userIdClaim, e);
                    return UUID.fromString(SYSTEM_USER_ID);
                }
            } else {
                log.warn("No 'sub' claim found in JWT token, returning system user ID");
                return UUID.fromString(SYSTEM_USER_ID);
            }
        }
        
        // For non-JWT authentication (e.g., system calls, service-to-service)
        log.debug("Non-JWT authentication found: {}, returning system user ID", authentication.getClass().getSimpleName());
        return UUID.fromString(SYSTEM_USER_ID);
    }

    /**
     * Get the authenticated username from the Spring Security context.
     * 
     * @return Username of the authenticated user, or "system" if not authenticated
     */
    public static String getAuthenticatedUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null) {
            return "system";
        }
        
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            Jwt jwt = jwtToken.getToken();
            String username = jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM);
            if (username != null) {
                return username;
            }
            // Fallback to subject if preferred_username not available
            return jwt.getClaimAsString(USER_ID_CLAIM);
        }
        
        return authentication.getName() != null ? authentication.getName() : "system";
    }

    /**
     * Check if the current user has a specific authority/role.
     * 
     * @param authority The authority to check for
     * @return true if the user has the authority, false otherwise
     */
    public static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> grantedAuthority.getAuthority().equals(authority));
    }

    /**
     * Check if the current authentication represents a system user (non-human).
     * This is useful for service-to-service calls or system-initiated operations.
     * 
     * @return true if this is a system user, false otherwise
     */
    public static boolean isSystemUser() {
        UUID userId = getAuthenticatedUserId();
        return SYSTEM_USER_ID.equals(userId.toString());
    }

    /**
     * Get the system user ID constant.
     * 
     * @return UUID representing the system user
     */
    public static UUID getSystemUserId() {
        return UUID.fromString(SYSTEM_USER_ID);
    }
} 