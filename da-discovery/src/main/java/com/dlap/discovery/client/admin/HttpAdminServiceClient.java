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

package com.dlap.discovery.client.admin;

import com.dlap.common.auth.ServiceTokenProvider;
import com.dlap.discovery.client.admin.AdminServiceException.Reason;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.config.Jackson2ClientConverters;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves cloud connections from da-admin-service over a service-account (M2M) token.
 *
 * <p>Active on every profile except {@code dev} and {@code test}, and on those too when
 * {@value AdminClientMode#PROPERTY}{@code =http} (see {@link AdminClientMode}). It calls
 * {@code GET /api/v1/admin/cloud-connections/{id}} and
 * {@code GET /api/v1/admin/cloud-connections} with the Keycloak client-credentials token
 * that {@link ServiceTokenProvider} caches (ADR-0021, enabled by
 * {@code dlap.service-auth.enabled=true}). It fails closed: with service-auth off it
 * refuses to call instead of sending an unauthenticated request, and it never invents a
 * connection when da-admin-service is unreachable.
 *
 * <p>Both endpoints must allow {@code ROLE_SERVICE_ACCOUNT} on the admin side (ADR-0034:
 * a new M2M call needs an explicit allow-list entry). Without it da-admin-service answers
 * 403 and this client reports {@link Reason#REJECTED}.
 *
 * <p>Configuration (all with defaults, so no {@code application.yml} change is needed):
 * {@code dlap.admin-service.url} (default {@code http://da-admin-service:8080}, the compose
 * network name and in-container port), {@code dlap.admin-service.connect-timeout-ms}
 * (3000) and {@code dlap.admin-service.read-timeout-ms} (10000).
 */
@Component
@Conditional(AdminClientMode.HttpSelected.class)
public class HttpAdminServiceClient implements IAdminServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAdminServiceClient.class);

    /** Collection endpoint of da-admin-service's cloud-connection API. */
    static final String CONNECTIONS_PATH = "/api/v1/admin/cloud-connections";

    private static final String KEY_GCP = "projectId";
    private static final String KEY_AWS = "accountId";
    private static final String KEY_AZURE = "subscriptionId";

    private final RestClient restClient;
    private final Supplier<String> tokenSupplier;

    /**
     * Production constructor.
     *
     * @param tokenProvider  the shared service-account token provider; absent when
     *                       {@code dlap.service-auth.enabled} is not true
     * @param baseUrl        base URL of da-admin-service
     * @param connectTimeout connect timeout in milliseconds
     * @param readTimeout    read timeout in milliseconds
     */
    @Autowired
    public HttpAdminServiceClient(
            ObjectProvider<ServiceTokenProvider> tokenProvider,
            @Value("${dlap.admin-service.url:http://da-admin-service:8080}") String baseUrl,
            @Value("${dlap.admin-service.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${dlap.admin-service.read-timeout-ms:10000}") int readTimeout) {
        // Jackson 2 for JSON, as before Spring Framework 7 (BOOT-01, Jackson2ClientConverters).
        this(RestClient.builder()
                        .configureMessageConverters(Jackson2ClientConverters::apply)
                        .requestFactory(timeouts(connectTimeout, readTimeout)),
                serviceToken(tokenProvider), baseUrl);
        if (tokenProvider.getIfAvailable() == null) {
            log.warn("dlap.service-auth.enabled is not true: cloud connections cannot be read from "
                    + "da-admin-service and every scan request will fail until it is enabled");
        }
    }

    /**
     * Test constructor: lets a test bind a mock server to the builder and supply a fixed token.
     *
     * @param builder       REST client builder, already configured by the caller
     * @param tokenSupplier supplies the bearer token for each call
     * @param baseUrl       base URL of da-admin-service
     */
    HttpAdminServiceClient(RestClient.Builder builder, Supplier<String> tokenSupplier, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public AdminConnectionDetails getConnectionDetails(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new AdminServiceException(Reason.NOT_FOUND, "A cloud connection id is required");
        }
        CloudConnectionPayload payload = call(true, () -> restClient.get()
                .uri(CONNECTIONS_PATH + "/{id}", connectionId)
                .headers(h -> h.setBearerAuth(tokenSupplier.get()))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(CloudConnectionPayload.class));
        if (payload == null) {
            throw new AdminServiceException(Reason.UNAVAILABLE,
                    "da-admin-service returned an empty answer for the cloud connection");
        }
        if (!isEnabled(payload)) {
            throw new AdminServiceException(Reason.DISABLED,
                    "Cloud connection '" + payload.name() + "' is disabled");
        }
        CloudProvider provider = scannableProvider(payload.providerType()).orElseThrow(() ->
                new AdminServiceException(Reason.UNSUPPORTED_PROVIDER, "Cloud connection '" + payload.name()
                        + "' has provider type " + payload.providerType() + ", which cannot be scanned"));
        return new AdminConnectionDetails(accountId(connectionId, provider, payload), provider, payload.name());
    }

    @Override
    public List<AdminConnectionSummary> listConnections() {
        List<CloudConnectionPayload> all = call(false, () -> restClient.get()
                .uri(CONNECTIONS_PATH)
                .headers(h -> h.setBearerAuth(tokenSupplier.get()))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<List<CloudConnectionPayload>>() { }));
        if (all == null) {
            return List.of();
        }
        return all.stream()
                .filter(p -> p != null && p.id() != null)
                .map(p -> new AdminConnectionSummary(p.id(), p.name(), p.providerType(), isEnabled(p),
                        isEnabled(p) && scannableProvider(p.providerType()).isPresent()))
                .toList();
    }

    /**
     * Maps a da-admin-service provider type to the discovery provider that can crawl it.
     *
     * @param providerType admin-side provider type such as GCP, AWS, AZURE or OTHER
     * @return the provider, or empty for OTHER and unknown values
     */
    static Optional<CloudProvider> scannableProvider(String providerType) {
        if (providerType == null) {
            return Optional.empty();
        }
        return switch (providerType.toUpperCase(Locale.ROOT)) {
            case "GCP" -> Optional.of(CloudProvider.GCP);
            case "AWS" -> Optional.of(CloudProvider.AWS);
            case "AZURE" -> Optional.of(CloudProvider.AZURE);
            default -> Optional.empty();
        };
    }

    /**
     * Picks the account scope the crawlers key a job on. The connection wizard stores the
     * non-secret scope under a provider-specific key; a connection without one is keyed by
     * its own id so the job row still has a stable, non-null account.
     */
    private static String accountId(String connectionId, CloudProvider provider, CloudConnectionPayload payload) {
        String key = switch (provider) {
            case GCP -> KEY_GCP;
            case AWS -> KEY_AWS;
            case AZURE -> KEY_AZURE;
            default -> null;
        };
        Map<String, String> params = payload.connectionParameters();
        String scoped = key == null || params == null ? null : params.get(key);
        if (scoped != null && !scoped.isBlank()) {
            return scoped.trim();
        }
        log.warn("Cloud connection {} declares no {} scope parameter; keying the scan on the connection id",
                connectionId, key);
        return connectionId;
    }

    private static boolean isEnabled(CloudConnectionPayload payload) {
        return !Boolean.FALSE.equals(payload.enabled());
    }

    /**
     * Runs one request and translates transport and HTTP failures into
     * {@link AdminServiceException}s that carry no response body.
     */
    private <T> T call(boolean singleConnection, Supplier<T> request) {
        try {
            return request.get();
        } catch (AdminServiceException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 404) {
                throw singleConnection
                        ? new AdminServiceException(Reason.NOT_FOUND, "Cloud connection not found", e)
                        : new AdminServiceException(Reason.REJECTED,
                                "da-admin-service does not serve the cloud-connections API (HTTP 404)", e);
            }
            throw new AdminServiceException(Reason.REJECTED, "da-admin-service rejected the discovery service "
                    + "account (HTTP " + status.value() + "); the service account needs access to cloud connections", e);
        } catch (HttpServerErrorException e) {
            throw new AdminServiceException(Reason.UNAVAILABLE,
                    "da-admin-service failed to answer (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (ResourceAccessException e) {
            throw new AdminServiceException(Reason.UNAVAILABLE, "da-admin-service is unreachable", e);
        } catch (RestClientException e) {
            throw new AdminServiceException(Reason.UNAVAILABLE,
                    "da-admin-service returned an answer that could not be read", e);
        }
    }

    private static SimpleClientHttpRequestFactory timeouts(int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    private static Supplier<String> serviceToken(ObjectProvider<ServiceTokenProvider> tokenProvider) {
        return () -> {
            ServiceTokenProvider provider = tokenProvider.getIfAvailable();
            if (provider == null) {
                throw new AdminServiceException(Reason.MISCONFIGURED, "Service-to-service authentication is "
                        + "disabled (dlap.service-auth.enabled=true is required to read cloud connections "
                        + "from da-admin-service)");
            }
            try {
                return provider.getAccessToken();
            } catch (RuntimeException e) {
                throw new AdminServiceException(Reason.UNAVAILABLE,
                        "Could not obtain a service-account token from the identity provider", e);
            }
        };
    }

    /** Subset of da-admin-service's {@code CloudConnectionDTO} that discovery reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CloudConnectionPayload(String id, String name, String providerType,
            Map<String, String> connectionParameters, Boolean enabled) {
    }
}
