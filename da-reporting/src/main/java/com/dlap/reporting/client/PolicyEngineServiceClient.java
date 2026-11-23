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

package com.dlap.reporting.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Feign client for da-policyengine service
 * Enables cross-service communication for policy compliance data
 * 
 * @author DLAP Development Team
 * @since 2025-01-02
 */
@FeignClient(name = "da-policyengine", url = "${dlap.services.da-policyengine.url:http://localhost:8083}")
public interface PolicyEngineServiceClient {

    /**
     * Get active policies count for governance metrics
     */
    @GetMapping("/api/v1/policyengine/policies/count/active")
    Long getActivePoliciesCount();

    /**
     * Get policy violations count for compliance scoring
     */
    @GetMapping("/api/v1/policyengine/policies/violations/count")
    Long getPolicyViolationsCount(@RequestParam(required = false) String severity);

    /**
     * Get policy compliance rate
     */
    @GetMapping("/api/v1/policyengine/policies/compliance-rate")
    Double getPolicyComplianceRate();

    /**
     * Get policy compliance breakdown by category
     */
    @GetMapping("/api/v1/policyengine/policies/compliance-breakdown")
    Map<String, Object> getPolicyComplianceBreakdown();

    /**
     * Get policy action items for aggregation
     */
    @GetMapping("/api/v1/policyengine/policies/action-items")
    List<Map<String, Object>> getPolicyActionItems(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") Integer limit);

    /**
     * Get historical policy compliance data
     */
    @GetMapping("/api/v1/policyengine/policies/compliance-history")
    List<Map<String, Object>> getPolicyComplianceHistory(
            @RequestParam(defaultValue = "30") Integer days);
} 