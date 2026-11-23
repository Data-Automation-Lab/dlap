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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Default {@link PdpClient}: calls the DLAP AuthZEN PDP over HTTP using the
 * Spring {@link RestClient} (from the inherited spring-web - no new dependency).
 *
 * <p>Flow per docs/specs/enforcement-contract.md §1/§2/§3:
 * <ol>
 *   <li>{@link AuthZenRequestMapper} builds the §2 request Map.</li>
 *   <li>POST it to {@code <base-url>/access/v1/evaluation} with a bearer token
 *       from {@link PdpTokenProvider}.</li>
 *   <li>Parse the §3 response ({@code decision}, {@code context.id},
 *       {@code context.reason_admin.en}, {@code context.obligations[]}) into an
 *       {@link McpDecision}.</li>
 * </ol>
 *
 * <p>Any I/O or parse failure is wrapped in {@link PdpUnavailableException};
 * the enforcer applies the fail-closed / fail-open policy.
 */
@Component
public class HttpPdpClient implements PdpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPdpClient.class);

    /** AuthZEN single-evaluation path (§1). */
    static final String EVALUATION_PATH = "/access/v1/evaluation";

    private final RestClient restClient;
    private final AuthZenRequestMapper mapper;
    private final PdpTokenProvider tokenProvider;

    public HttpPdpClient(RestClient.Builder restClientBuilder,
                         AuthZenRequestMapper mapper,
                         PdpTokenProvider tokenProvider,
                         McpPepProperties properties) {
        this.restClient = restClientBuilder
                .baseUrl(properties.getPdp().getBaseUrl())
                .build();
        this.mapper = mapper;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public McpDecision evaluate(McpAccessRequest req) {
        Map<String, Object> body = mapper.toAuthZenRequest(req);
        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri(EVALUATION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> tokenProvider.getToken()
                            .ifPresent(t -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + t)))
                    .body(body)
                    .retrieve()
                    .body(RESPONSE_TYPE);
        } catch (RuntimeException ex) {
            // Connection refused, timeout, non-2xx, deserialization failure, …
            throw new PdpUnavailableException("PDP evaluation call failed", ex);
        }
        if (response == null) {
            throw new PdpUnavailableException("PDP returned an empty evaluation response");
        }
        return parse(response);
    }

    @SuppressWarnings("unchecked")
    private McpDecision parse(Map<String, Object> response) {
        try {
            boolean allow = Boolean.TRUE.equals(response.get("decision"));
            String reason = null;
            List<Obligation> obligations = List.of();

            Object ctxObj = response.get("context");
            if (ctxObj instanceof Map<?, ?> ctx) {
                Object reasonAdmin = ((Map<String, Object>) ctx).get("reason_admin");
                if (reasonAdmin instanceof Map<?, ?> ra) {
                    Object en = ((Map<String, Object>) ra).get("en");
                    if (en != null) {
                        reason = en.toString();
                    }
                }
                Object obs = ((Map<String, Object>) ctx).get("obligations");
                if (obs instanceof List<?> obsList) {
                    obligations = parseObligations(obsList);
                }
            }
            if (reason == null) {
                reason = allow ? "allowed" : "denied";
            }
            return new McpDecision(allow, reason, obligations, false, false);
        } catch (RuntimeException ex) {
            throw new PdpUnavailableException("Failed to parse PDP evaluation response", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Obligation> parseObligations(List<?> obsList) {
        List<Obligation> result = new ArrayList<>();
        for (Object o : obsList) {
            if (o instanceof Map<?, ?> om) {
                Map<String, Object> m = (Map<String, Object>) om;
                Object type = m.get("type");
                List<String> labels = new ArrayList<>();
                Object labelsObj = m.get("labels");
                if (labelsObj instanceof List<?> l) {
                    for (Object label : l) {
                        if (label != null) {
                            labels.add(label.toString());
                        }
                    }
                }
                if (type != null) {
                    result.add(new Obligation(type.toString(), labels));
                }
            }
        }
        return result;
    }

    // RestClient needs a ParameterizedTypeReference to deserialize into a
    // Map<String,Object>; a nested type token avoids an unchecked-cast warning
    // on the retrieve() call.
    private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new org.springframework.core.ParameterizedTypeReference<>() {
            };
}
