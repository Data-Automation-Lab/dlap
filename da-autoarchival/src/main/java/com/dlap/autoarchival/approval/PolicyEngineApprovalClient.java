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

package com.dlap.autoarchival.approval;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dlap.common.auth.ServiceTokenProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Calls the approval API of da-policyengine as {@code ROLE_SERVICE_ACCOUNT}
 * (docs/specs/approvals.md sections 5, 8 and 9).
 *
 * <ul>
 *   <li>{@link #fetchExecutable}: the re-read of section 9 rule 3. Kafka write access to
 *       {@code dlap.policies.actions} then cannot forge an approval: the executor acts only when
 *       the policy engine itself says the request is executable.</li>
 *   <li>{@link #ask}: an archive requested through this service's own REST API (rule 4). The
 *       caller is named in {@code onBehalfOf}, so the caller can never approve what they asked
 *       for.</li>
 * </ul>
 *
 * <p>Thread-safe. Never logs the bearer token.
 */
@Component
public class PolicyEngineApprovalClient {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyEngineApprovalClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/api/v1/policyengine/approvals";

    private final String baseUrl;
    private final Supplier<String> token;
    private final HttpClient http;
    private final Duration timeout;

    /**
     * Spring constructor.
     *
     * @param baseUrl        {@code dlap.services.da-policyengine.url}
     * @param tokenProvider  the M2M token provider; absent when {@code dlap.service-auth.enabled=false}
     * @param timeoutSeconds the request timeout
     */
    @Autowired
    public PolicyEngineApprovalClient(
            @Value("${dlap.services.da-policyengine.url:http://localhost:8083}") String baseUrl,
            ObjectProvider<ServiceTokenProvider> tokenProvider,
            @Value("${dlap.autoarchival.approvals.timeout-seconds:10}") int timeoutSeconds) {
        this(baseUrl, () -> {
            ServiceTokenProvider p = tokenProvider.getIfAvailable();
            return p == null ? null : p.getAccessToken();
        }, Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * Test constructor.
     *
     * @param baseUrl the policy engine base URL
     * @param token   supplies the bearer token, or {@code null} for none
     * @param timeout the request timeout
     */
    public PolicyEngineApprovalClient(String baseUrl, Supplier<String> token, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Re-reads the approval and checks that the policy engine itself calls it executable for this asset.
     *
     * @param approvalId the approval id
     * @param assetId    the catalog asset id the action runs on
     * @return the approval as the policy engine reports it
     * @throws ApprovalRefusedException {@code NOT_DECIDED} (still pending), {@code NOT_EXECUTABLE},
     *                                  {@code WRONG_CLASS}, {@code ASSET_MISMATCH} or {@code VERIFY_UNAVAILABLE}
     */
    public ArchiveApproval fetchExecutable(String approvalId, String assetId) {
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(baseUrl + PATH + "/"
                + URLEncoder.encode(approvalId, StandardCharsets.UTF_8))).GET());
        if (res.statusCode() == 404) {
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.NOT_EXECUTABLE,
                    "the policy engine does not know approval " + approvalId);
        }
        if (res.statusCode() != 200) {
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.VERIFY_UNAVAILABLE,
                    "the policy engine answered HTTP " + res.statusCode() + " for approval " + approvalId);
        }
        JsonNode n = parse(res.body());
        if (!approvalId.equals(n.path("id").asText())) {
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.NOT_EXECUTABLE,
                    "the policy engine answered for another approval");
        }
        String status = n.path("status").asText("");
        if ("PENDING".equals(status)) {
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.NOT_DECIDED,
                    "approval " + approvalId + " is still waiting for decisions");
        }
        if (!n.path("executable").asBoolean(false)
                || !(ArchiveApproval.APPROVED.equals(status) || ArchiveApproval.APPROVED_BREAK_GLASS.equals(status))) {
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.NOT_EXECUTABLE,
                    "approval " + approvalId + " is " + status + " and not executable");
        }
        String actionClass = n.path("actionClass").asText("");
        if (!ArchiveApproval.ACTION_CLASS_ARCHIVE.equals(actionClass)) {
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.WRONG_CLASS,
                    "approval " + approvalId + " was given for " + actionClass + ", not ARCHIVE");
        }
        String approvedAsset = n.path("assetId").asText("");
        if (assetId == null || !approvedAsset.equalsIgnoreCase(assetId)) {
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.ASSET_MISMATCH,
                    "approval " + approvalId + " was given for another asset");
        }
        List<String> approvers = new ArrayList<>();
        for (JsonNode d : n.path("decisions")) {
            if (!"REJECT".equals(d.path("decision").asText())) {
                approvers.add(d.path("approverSub").asText());
            }
        }
        Map<String, Object> approvedParameters = new LinkedHashMap<>();
        JsonNode params = n.path("actionParameters");
        if (params.isObject()) {
            params.fields().forEachRemaining(e -> approvedParameters.put(e.getKey(), plain(e.getValue())));
        }
        return new ArchiveApproval(approvalId, status, actionClass, n.path("requiredApprovals").asInt(1), approvers,
                ArchiveApproval.APPROVED_BREAK_GLASS.equals(status), textOrNull(n.path("assetUri")),
                textOrNull(n.path("provider")), approvedParameters);
    }

    /**
     * Asks for an approval on behalf of a person.
     *
     * @param assetId        the catalog asset UUID
     * @param assetUri       the asset's storage URI
     * @param provider       the provider
     * @param actionType     the action type, for example {@code archive}
     * @param parameters     what the executor needs (tier, prefix, ...)
     * @param reason         why
     * @param onBehalfOf     the {@code sub} of the person who asked; excluded from deciding
     * @param idempotencyKey a retry with the same key returns the first request
     * @return the approval id
     * @throws IllegalStateException when the request could not be recorded
     */
    public String ask(String assetId, String assetUri, String provider, String actionType,
            Map<String, Object> parameters, String reason, String onBehalfOf, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionClass", ArchiveApproval.ACTION_CLASS_ARCHIVE);
        body.put("actionType", actionType);
        body.put("assetId", assetId);
        body.put("assetUri", assetUri);
        body.put("provider", provider);
        body.put("actionParameters", parameters == null ? Map.of() : parameters);
        body.put("reason", reason);
        body.put("idempotencyKey", idempotencyKey);
        if (onBehalfOf != null) {
            body.put("onBehalfOf", onBehalfOf);
        }
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode the approval request", e);
        }
        HttpResponse<String> res;
        try {
            res = send(HttpRequest.newBuilder(URI.create(baseUrl + PATH)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)));
        } catch (ApprovalRefusedException e) {
            throw new IllegalStateException("the approval request could not be sent: " + e.getMessage(), e);
        }
        if (res.statusCode() != 200 && res.statusCode() != 201) {
            throw new IllegalStateException("the policy engine refused the approval request: HTTP " + res.statusCode());
        }
        String id = parse(res.body()).path("id").asText("");
        if (id.isEmpty()) {
            throw new IllegalStateException("the policy engine answered without an approval id");
        }
        return id;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        builder.timeout(timeout).header("Accept", "application/json");
        String bearer = token.get();
        if (bearer != null && !bearer.isBlank()) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOG.warn("policy engine call failed: {}", e.getClass().getSimpleName());
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.VERIFY_UNAVAILABLE,
                    "the policy engine could not be reached: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.VERIFY_UNAVAILABLE,
                    "interrupted while asking the policy engine", e);
        }
    }

    private static String textOrNull(JsonNode n) {
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }

    /** A parameter value as the executor reads it: text, number or boolean; anything nested stays as JSON text. */
    private static Object plain(JsonNode v) {
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        return v.isNull() ? null : v.toString();
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ApprovalRefusedException(ApprovalRefusedException.Reason.VERIFY_UNAVAILABLE,
                    "the policy engine answered with invalid JSON", e);
        }
    }
}
