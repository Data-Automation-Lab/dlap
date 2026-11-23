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

package com.dlap.policyengine.mapper;

import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.dlap.policyengine.dto.PolicyEvaluationOutputDTO;
import com.dlap.policyengine.dto.PolicyEvaluationSummaryDTO;
// Add missing DTO imports
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.dto.PolicyOutputDTO;
import com.dlap.policyengine.dto.PolicyRuleDTO;
import com.dlap.policyengine.dto.PolicySummaryDTO;
// Add missing entity imports
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyEvaluation;
import com.dlap.policyengine.model.PolicyEvaluationStatus;
import com.dlap.policyengine.model.PolicyRule;

@Component
public class PolicyMapper {

    public PolicySummaryDTO toPolicySummaryDTO(Policy policy) {
        if (policy == null) return null;
        PolicySummaryDTO dto = new PolicySummaryDTO();
        dto.setId(policy.getId());
        dto.setName(policy.getName());
        dto.setDescription(policy.getDescription());
        dto.setStatus(policy.getStatus());
        dto.setRuleCount(policy.getRules() != null ? policy.getRules().size() : 0);
        dto.setCreatedAt(policy.getCreatedAt());
        dto.setUpdatedAt(policy.getUpdatedAt());
        return dto;
    }

    public PolicyOutputDTO toPolicyOutputDTO(Policy policy) {
        if (policy == null) return null;
        PolicyOutputDTO dto = new PolicyOutputDTO();
        dto.setId(policy.getId());
        dto.setName(policy.getName());
        dto.setDescription(policy.getDescription());
        dto.setStatus(policy.getStatus());
        dto.setConditionLogic(policy.getConditionLogic());
        dto.setActions(policy.getActions());
        dto.setPolicyDocument(policy.getPolicyDocument());
        dto.setRules(policy.getRules().stream().map(this::toPolicyRuleDTO).collect(Collectors.toList()));
        dto.setCreatedAt(policy.getCreatedAt());
        dto.setUpdatedAt(policy.getUpdatedAt());
        dto.setCreatedByUserId(policy.getCreatedByUserId());
        dto.setUpdatedByUserId(policy.getUpdatedByUserId());
        return dto;
    }

    public PolicyRuleDTO toPolicyRuleDTO(PolicyRule policyRule) {
        if (policyRule == null) return null;
        PolicyRuleDTO dto = new PolicyRuleDTO();
        dto.setId(policyRule.getId() != null ? policyRule.getId().toString() : null);
        dto.setName(policyRule.getName());
        dto.setDescription(policyRule.getDescription());
        dto.setCondition(policyRule.getCondition());
        dto.setPriority(policyRule.getPriority());
        dto.setActions(policyRule.getActions());
        return dto;
    }

    public Policy toPolicyEntity(PolicyInputDTO dto) {
        if (dto == null) return null;
        Policy policy = new Policy();
        updatePolicyEntityFromInputDTO(policy, dto); // Reuse logic for update
        return policy;
    }

    public void updatePolicyEntityFromInputDTO(Policy policy, PolicyInputDTO dto) {
        if (policy == null || dto == null) return;

        policy.setName(dto.getName());
        policy.setDescription(dto.getDescription());
        policy.setStatus(dto.getStatus());
        policy.setConditionLogic(dto.getConditionLogic());
        policy.setActions(dto.getActions());
        policy.setPolicyDocument(dto.getPolicyDocument());

        // Rules are legacy and optional under ADR-0031. Always mutate the
        // existing collection in place - never replace the reference: Policy.rules
        // is @OneToMany(orphanRemoval=true), and swapping the instance makes
        // Hibernate throw "collection no longer referenced" on flush (HTTP 500),
        // which is the normal path now that a DSL-only policy carries no rules.
        policy.getRules().clear();
        if (dto.getRules() != null) {
            dto.getRules().forEach(ruleDTO -> policy.addRule(toPolicyRuleEntity(ruleDTO)));
        }
    }

    public PolicyRule toPolicyRuleEntity(PolicyRuleDTO dto) {
        if (dto == null) return null;
        PolicyRule rule = new PolicyRule();
        // ID is not set here for new rules, or could be used to fetch existing if updating
        // For simplicity, if ID is present in DTO, we assume it might be used by service layer
        // to find and update existing rule, but mapper focuses on new entity creation or field mapping.
        if (dto.getId() != null) {
           try {
             rule.setId(UUID.fromString(dto.getId()));
           } catch (IllegalArgumentException e) {
             // Handle invalid UUID string if necessary, or let it be null for new entity
           }
        }
        rule.setName(dto.getName());
        rule.setDescription(dto.getDescription());
        rule.setCondition(dto.getCondition());
        rule.setPriority(dto.getPriority());
        rule.setActions(dto.getActions());
        return rule;
    }

    public PolicyEvaluationOutputDTO toPolicyEvaluationOutputDTO(PolicyEvaluation evaluation, String policyName) {
        if (evaluation == null) return null;
        PolicyEvaluationOutputDTO dto = new PolicyEvaluationOutputDTO();
        dto.setId(evaluation.getId());
        dto.setPolicyId(evaluation.getPolicyId());
        dto.setPolicyName(policyName); // Policy name fetched separately for convenience
        dto.setTargetAssetId(evaluation.getTargetAssetId());
        dto.setStatus(evaluation.getStatus());
        dto.setEvaluationDetails(evaluation.getEvaluationDetails());
        dto.setTriggeredActions(evaluation.getTriggeredActions());
        dto.setEvaluatedAt(evaluation.getEvaluatedAt());
        dto.setEvaluationTriggeredByUserId(evaluation.getEvaluationTriggeredByUserId());
        return dto;
    }

    public PolicyEvaluationSummaryDTO toPolicyEvaluationSummaryDTO(PolicyEvaluation evaluation, String policyName) {
        if (evaluation == null) return null;
        PolicyEvaluationSummaryDTO dto = new PolicyEvaluationSummaryDTO();
        dto.setId(evaluation.getId());
        dto.setPolicyId(evaluation.getPolicyId());
        dto.setPolicyName(policyName);
        dto.setTargetAssetId(evaluation.getTargetAssetId());
        dto.setStatus(evaluation.getStatus());
        dto.setEvaluatedAt(evaluation.getEvaluatedAt());
        return dto;
    }
} 