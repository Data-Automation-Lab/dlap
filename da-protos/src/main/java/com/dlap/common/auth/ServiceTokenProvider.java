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

package com.dlap.common.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Obtains and caches a Keycloak client-credentials access token for
 * service-to-service (M2M) authentication (P0-K / ADR-0021).
 *
 * <p>One instance per JVM; the cached token is rotated when it has
 * fewer than {@link #REFRESH_BUFFER_SECONDS} seconds left to live.
 * Thread-safe via {@link AtomicReference}.
 *
 * <p>Configuration via Spring properties:
 *
 * <ul>
 *   <li>{@code dlap.service-auth.token-uri}:
 *       {@code <keycloak>/realms/dlap/protocol/openid-connect/token}.</li>
 *   <li>{@code dlap.service-auth.client-id} - Keycloak client id
 *       (e.g. {@code dlap-services} or per-service id).</li>
 *   <li>{@code dlap.service-auth.client-secret} - client secret
 *       (from {@code ${DLAP_SERVICE_CLIENT_SECRET}} env).</li>
 * </ul>
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);
    private static final int REFRESH_BUFFER_SECONDS = 30;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    private final AtomicReference<CachedToken> cache = new AtomicReference<>();

    public ServiceTokenProvider(String tokenUri, String clientId, String clientSecret) {
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        // Keycloak's token response carries extra fields (refresh_expires_in,
        // not-before-policy, scope, …) beyond the three we read. Be lenient so a
        // token refresh never fails on an unmodeled field - that would silently
        // strip the bearer token from every service-to-service Feign call.
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Return a valid access token. Refreshes from Keycloak if cache is
     * empty or near-expiry. Never returns null on the happy path;
     * throws if Keycloak refuses or is unreachable.
     */
    public String getAccessToken() {
        CachedToken current = cache.get();
        if (current != null && current.isValid()) {
            return current.accessToken;
        }
        return refresh();
    }

    private synchronized String refresh() {
        CachedToken current = cache.get();
        if (current != null && current.isValid()) {
            // Another thread won the race.
            return current.accessToken;
        }
        try {
            String body = "grant_type=client_credentials"
                    + "&client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUri))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Keycloak token endpoint returned "
                        + response.statusCode() + ": " + response.body());
            }
            TokenResponse parsed = objectMapper.readValue(response.body(), TokenResponse.class);
            if (parsed.accessToken == null || parsed.expiresIn <= 0) {
                throw new IllegalStateException("Malformed Keycloak token response");
            }
            CachedToken next = new CachedToken(
                    parsed.accessToken,
                    Instant.now().plusSeconds(parsed.expiresIn - REFRESH_BUFFER_SECONDS));
            cache.set(next);
            log.debug("Refreshed service-account token for clientId={} ttl={}s", clientId, parsed.expiresIn);
            return parsed.accessToken;
        } catch (Exception e) {
            log.error("Failed to obtain service-account token from Keycloak: {}", e.getMessage());
            throw new RuntimeException("service-auth token refresh failed", e);
        }
    }

    private static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static class CachedToken {
        final String accessToken;
        final Instant expiresAt;

        CachedToken(String accessToken, Instant expiresAt) {
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
        }

        boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /** Jackson DTO for the Keycloak token response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenResponse {
        @JsonProperty("access_token")
        public String accessToken;
        @JsonProperty("expires_in")
        public int expiresIn;
        @JsonProperty("token_type")
        public String tokenType;
    }
}
