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

package com.dlap.autodelete.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/policyengine/approvals} ({@code ApprovalCreateBody},
 * docs/specs/approvals.md section 8).
 *
 * @param actionClass       {@code SOFT_DELETE} or {@code HARD_DELETE}
 * @param actionType        what the executor runs: {@code delete}
 * @param assetId           catalog asset id
 * @param assetUri          canonical asset URI
 * @param provider          cloud provider
 * @param serviceType       asset kind
 * @param actionParameters  what the executor needs to act (the soft-delete record, the planned rule)
 * @param reason            why
 * @param requiredApprovals approvals wanted, never fewer than the class floor
 * @param idempotencyKey    a retry returns the first request
 * @param onBehalfOf        the JWT subject of the person the service acts for, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalCreateRequest(
        String actionClass,
        String actionType,
        UUID assetId,
        String assetUri,
        String provider,
        String serviceType,
        Map<String, Object> actionParameters,
        String reason,
        Integer requiredApprovals,
        String idempotencyKey,
        String onBehalfOf) {
}
