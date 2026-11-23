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

package com.dlap.policyengine.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.dlap.common.event.AssetChangeEvent;
import com.dlap.policyengine.dto.PolicyEvaluationOutputDTO;
import com.dlap.policyengine.dto.PolicyEvaluationRequestDTO;
import com.dlap.policyengine.dto.PolicyEvaluationSummaryDTO;

public interface IPolicyEvaluationService {

    /**
     * Evaluates a policy for one asset on behalf of a user whose JWT {@code sub} is not known
     * beyond the user id. The user id, in its text form, is used as the triggering subject.
     *
     * @param policyId          the policy row id
     * @param evaluationRequest the asset to evaluate
     * @param triggeredByUserId the user who triggered it; may be null
     * @return the evaluation record
     */
    default PolicyEvaluationOutputDTO evaluatePolicyForAsset(UUID policyId, PolicyEvaluationRequestDTO evaluationRequest, UUID triggeredByUserId) {
        return evaluatePolicyForAsset(policyId, evaluationRequest, triggeredByUserId,
                triggeredByUserId == null ? null : triggeredByUserId.toString());
    }

    /**
     * Evaluates a policy for one asset. When the policy matches, its actions go through the
     * approval gate, and {@code triggeredBySubject} becomes an excluded approver of every request
     * that results (Q-569).
     *
     * @param policyId           the policy row id
     * @param evaluationRequest  the asset to evaluate
     * @param triggeredByUserId  the user id recorded on the evaluation; may be null
     * @param triggeredBySubject the JWT {@code sub} of the caller, as the approval records see it;
     *                           null when nobody triggered the evaluation
     * @return the evaluation record
     */
    PolicyEvaluationOutputDTO evaluatePolicyForAsset(UUID policyId, PolicyEvaluationRequestDTO evaluationRequest, UUID triggeredByUserId, String triggeredBySubject);

    /**
     * Triggers an evaluation; see {@link #evaluatePolicyForAsset(UUID, PolicyEvaluationRequestDTO, UUID)}.
     *
     * @param policyId          the policy row id
     * @param evaluationRequest the asset to evaluate
     * @param triggeredByUserId the user who triggered it; may be null
     * @return the evaluation record
     */
    default PolicyEvaluationOutputDTO triggerPolicyEvaluation(UUID policyId, PolicyEvaluationRequestDTO evaluationRequest, UUID triggeredByUserId) {
        return evaluatePolicyForAsset(policyId, evaluationRequest, triggeredByUserId);
    }

    /**
     * Triggers an evaluation on behalf of a caller; see
     * {@link #evaluatePolicyForAsset(UUID, PolicyEvaluationRequestDTO, UUID, String)}.
     *
     * @param policyId           the policy row id
     * @param evaluationRequest  the asset to evaluate
     * @param triggeredByUserId  the user id recorded on the evaluation; may be null
     * @param triggeredBySubject the JWT {@code sub} of the caller; null when nobody triggered it
     * @return the evaluation record
     */
    PolicyEvaluationOutputDTO triggerPolicyEvaluation(UUID policyId, PolicyEvaluationRequestDTO evaluationRequest, UUID triggeredByUserId, String triggeredBySubject);

    Page<PolicyEvaluationSummaryDTO> getPolicyEvaluations(Pageable pageable, UUID policyId, String targetAssetId, String status);

    PolicyEvaluationOutputDTO getPolicyEvaluationById(UUID evaluationId);

    // P0-I (ADR-0021): the Kafka-driven path is now LifecyclePolicyConsumer
    // + PolicyDslEvaluator. The legacy evaluatePolicyForAssetInternal (MVEL
    // rules engine) is no longer part of this interface.
}
