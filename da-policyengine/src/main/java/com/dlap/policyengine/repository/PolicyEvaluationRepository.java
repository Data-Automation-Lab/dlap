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

package com.dlap.policyengine.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.policyengine.model.PolicyEvaluation;
import com.dlap.policyengine.model.PolicyEvaluationStatus;

@Repository
public interface PolicyEvaluationRepository extends JpaRepository<PolicyEvaluation, UUID>, JpaSpecificationExecutor<PolicyEvaluation> {
    Page<PolicyEvaluation> findByPolicyId(UUID policyId, Pageable pageable);
    Page<PolicyEvaluation> findByTargetAssetId(String targetAssetId, Pageable pageable);
    Page<PolicyEvaluation> findByStatus(PolicyEvaluationStatus status, Pageable pageable);
    Page<PolicyEvaluation> findByPolicyIdAndTargetAssetId(UUID policyId, String targetAssetId, Pageable pageable);

    long countByStatus(PolicyEvaluationStatus status);

    @Query(value = """
            SELECT CAST(date_trunc('day', evaluated_at AT TIME ZONE 'UTC') AS date) AS bucket_date,
                   SUM(CASE WHEN status = 'PASS' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN status IN ('FAIL','ERROR') THEN 1 ELSE 0 END)
            FROM dlap_policyengine.policy_evaluations
            WHERE evaluated_at >= :since
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> summarizeEvaluationsByDay(@Param("since") Instant since);
} 