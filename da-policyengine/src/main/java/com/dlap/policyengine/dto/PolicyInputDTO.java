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

package com.dlap.policyengine.dto;

import java.util.List;
import java.util.Map;

import com.dlap.policyengine.filter.RetiredRules;
import com.dlap.policyengine.model.PolicyStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PolicyInputDTO {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 1000)
    private String description;

    private PolicyStatus status = PolicyStatus.DISABLED;

    // Free-text note kept for display; never compiled or evaluated (SEC-04, ADR-0057).
    private String conditionLogic;

    // Canonical dlap/v1 DSL document (ADR-0031). The only evaluated form of a policy:
    // the service validates it via PolicyDslParser and stores it on Policy.policyDocument.
    // Required since the MVEL rules were retired (SEC-04, ADR-0057).
    private String policyDocument;

    // Retired MVEL rules (SEC-04, ADR-0057). Kept on the wire contract only so that a client
    // that still sends them gets a clear HTTP 400 (bean validation runs before the handler's
    // @PreAuthorize, so every role gets the same answer). Null or empty is accepted.
    @Valid
    @Size(max = 0, message = RetiredRules.POLICY_RULES_MESSAGE)
    private List<PolicyRuleDTO> rules;

    private Map<String, Object> actions; // Policy-level actions

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public void setStatus(PolicyStatus status) {
        this.status = status;
    }

    public String getConditionLogic() {
        return conditionLogic;
    }

    public void setConditionLogic(String conditionLogic) {
        this.conditionLogic = conditionLogic;
    }

    public String getPolicyDocument() {
        return policyDocument;
    }

    public void setPolicyDocument(String policyDocument) {
        this.policyDocument = policyDocument;
    }

    public List<PolicyRuleDTO> getRules() {
        return rules;
    }

    public void setRules(List<PolicyRuleDTO> rules) {
        this.rules = rules;
    }

    public Map<String, Object> getActions() {
        return actions;
    }

    public void setActions(Map<String, Object> actions) {
        this.actions = actions;
    }
} 