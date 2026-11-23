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

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KeycloakAdminClientConfig {

    @Value("${keycloak.auth-server-url}")
    private String serverUrl;

    // Realm the admin client AUTHENTICATES against. The bootstrap admin
    // (admin/admin) lives in the 'master' realm, so the admin client must log in
    // there. Operations then target keycloak.realm (e.g. dlap) via
    // keycloakAdminClient.realm(realmName) in the service layer
    // (see UserServiceImpl / RoleServiceImpl). Splitting these is required:
    // authenticating admin/admin against the dlap realm returns 401.
    @Value("${keycloak.admin.auth-realm:master}")
    private String authRealm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret:#{null}}") // Default to null if not provided
    private String clientSecret;
    
    // Optional: If using password credentials grant type for admin client
    @Value("${keycloak.admin.username:#{null}}")
    private String adminUsername;

    @Value("${keycloak.admin.password:#{null}}")
    private String adminPassword;

    @Bean
    public Keycloak keycloakAdminClient() {
        KeycloakBuilder builder = KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm(authRealm)
                .clientId(clientId);
                // ResteasyClient can be configured here if needed, e.g., for connection pooling
                // .resteasyClient(ResteasyClientBuilder.newBuilder().build()); // Basic Resteasy client

        if (clientSecret != null && !clientSecret.isEmpty()) {
            builder.clientSecret(clientSecret);
            // Ensure the client (e.g., admin-cli or your dedicated client) has "Service Accounts Enabled"
            // and appropriate "Service Account Roles" (e.g., realm-management -> manage-users, view-users)
            builder.grantType("client_credentials");
        } else if (adminUsername != null && !adminUsername.isEmpty() && adminPassword != null && !adminPassword.isEmpty()){
            // This is typically for direct admin user login, ensure this user has necessary permissions.
            // The 'admin-cli' client itself might not allow password grant for other users.
            // Prefer service account with client_credentials for backend services.
            builder.username(adminUsername);
            builder.password(adminPassword);
            builder.grantType("password");
        } else {
            throw new IllegalStateException("Keycloak admin client not properly configured: Missing clientSecret or admin username/password.");
        }
        
        return builder.build();
    }
} 