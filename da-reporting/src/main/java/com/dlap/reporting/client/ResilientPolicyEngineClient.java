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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ResilientPolicyEngineClient {

    private final PolicyEngineServiceClient delegate;

    @CircuitBreaker(name = "da-policyengine", fallbackMethod = "fallbackLong")
    public Long getActivePoliciesCount() {
        return delegate.getActivePoliciesCount();
    }

    @CircuitBreaker(name = "da-policyengine", fallbackMethod = "fallbackLong")
    public Long getPolicyViolationsCount(String status) {
        return delegate.getPolicyViolationsCount(status);
    }

    @CircuitBreaker(name = "da-policyengine", fallbackMethod = "fallbackComplianceRate")
    public Double getPolicyComplianceRate() {
        return delegate.getPolicyComplianceRate();
    }

    @CircuitBreaker(name = "da-policyengine", fallbackMethod = "fallbackActionItems")
    public List<Map<String, Object>> getPolicyActionItems(String severity, String status, Integer limit) {
        return delegate.getPolicyActionItems(severity, status, limit);
    }

    @SuppressWarnings("unused")
    private Long fallbackLong(String ignored, Throwable ex) {
        return 0L;
    }

    @SuppressWarnings("unused")
    private Long fallbackLong(Throwable ex) {
        return 0L;
    }

    @SuppressWarnings("unused")
    private Double fallbackComplianceRate(Throwable ex) {
        return null;
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> fallbackActionItems(String severity, String status, Integer limit, Throwable ex) {
        return Collections.emptyList();
    }
}

