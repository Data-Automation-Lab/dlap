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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps a PEP-neutral {@link McpAccessRequest} to the AuthZEN 1.0 evaluation
 * request shape DLAP profiles in docs/specs/enforcement-contract.md §2.
 *
 * <p>Pure and stateless - the produced {@code Map} serializes directly to the
 * §2 JSON:
 *
 * <pre>{@code
 * {
 *   "subject":  { "type": "agent", "id": ..., "properties": { "roles": [...], "tier": ... } },
 *   "action":   { "name": ... },
 *   "resource": { "type": "asset", "id"?: ..., "properties": { "assetUri": ..., "labels"?: [...] } },
 *   "context":  { ... }
 * }
 * }</pre>
 *
 * <p>Empty/absent members are omitted so the request stays close to the spec
 * example (no {@code "id": null}). {@code subject.type} is fixed to
 * {@code "agent"} - an MCP tool caller is an agent principal (§2 profile).
 */
@Component
public class AuthZenRequestMapper {

    /** AuthZEN subject type for an MCP agent caller (§2 profile). */
    public static final String SUBJECT_TYPE_AGENT = "agent";

    /** AuthZEN resource type for a DLAP catalog asset (§2 profile). */
    public static final String RESOURCE_TYPE_ASSET = "asset";

    /**
     * @param req the neutral MCP access request
     * @return a nested {@code Map} matching the §2 AuthZEN request JSON
     */
    public Map<String, Object> toAuthZenRequest(McpAccessRequest req) {
        Map<String, Object> root = new LinkedHashMap<>();

        // subject
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("type", SUBJECT_TYPE_AGENT);
        subject.put("id", req.principalId());
        Map<String, Object> subjectProps = new LinkedHashMap<>();
        if (req.roles() != null && !req.roles().isEmpty()) {
            subjectProps.put("roles", List.copyOf(req.roles()));
        }
        if (hasText(req.tier())) {
            subjectProps.put("tier", req.tier());
        }
        if (!subjectProps.isEmpty()) {
            subject.put("properties", subjectProps);
        }
        root.put("subject", subject);

        // action
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("name", req.action());
        root.put("action", action);

        // resource
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", RESOURCE_TYPE_ASSET);
        if (hasText(req.assetId())) {
            resource.put("id", req.assetId());
        }
        Map<String, Object> resourceProps = new LinkedHashMap<>();
        if (hasText(req.resourceUri())) {
            resourceProps.put("assetUri", req.resourceUri());
        }
        if (!resourceProps.isEmpty()) {
            resource.put("properties", resourceProps);
        }
        root.put("resource", resource);

        // context (free-form; DLAP reads the keys named by AccessPolicy conditions)
        if (req.context() != null && !req.context().isEmpty()) {
            root.put("context", new LinkedHashMap<>(req.context()));
        }

        return root;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
