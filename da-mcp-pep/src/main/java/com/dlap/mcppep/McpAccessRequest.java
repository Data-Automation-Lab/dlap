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

import java.util.List;
import java.util.Map;

/**
 * A single MCP access intent to be authorized by the DLAP PDP.
 *
 * <p>This is the PEP's own neutral request shape - an MCP tool-call or
 * resource-read reduced to the fields the enforcement contract needs.
 * {@link AuthZenRequestMapper} translates it into the AuthZEN evaluation
 * request (docs/specs/enforcement-contract.md §2).
 *
 * <ul>
 *   <li>{@code principalId} - the calling agent/service/user id
 *       (AuthZEN {@code subject.id}).</li>
 *   <li>{@code roles} - realm roles carried in {@code subject.properties.roles}.</li>
 *   <li>{@code tier} - ADR-0032 persona tier ({@code subject.properties.tier}).</li>
 *   <li>{@code tool} - the MCP tool name being invoked (audit/context only;
 *       DLAP evaluates {@code action}).</li>
 *   <li>{@code resourceUri} - the asset URI (e.g. {@code bq://project/dataset/table}),
 *       carried in {@code resource.properties.assetUri} and used as a cache key
 *       when {@code assetId} is absent.</li>
 *   <li>{@code assetId} - the DLAP catalog asset UUID ({@code resource.id}),
 *       preferred cache key when present.</li>
 *   <li>{@code action} - {@code read | query | export | write | delete}
 *       ({@code action.name}).</li>
 *   <li>{@code context} - free-form request context (purpose, consent, region);
 *       DLAP evaluates the keys named by AccessPolicy {@code condition} clauses.</li>
 * </ul>
 */
public record McpAccessRequest(
        String principalId,
        List<String> roles,
        String tier,
        String tool,
        String resourceUri,
        String assetId,
        String action,
        Map<String, Object> context) {
}
