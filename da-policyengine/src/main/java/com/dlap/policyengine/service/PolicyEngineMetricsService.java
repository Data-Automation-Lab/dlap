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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.policyengine.model.PolicyEvaluation;
import com.dlap.policyengine.model.PolicyEvaluationStatus;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyEvaluationRepository;
import com.dlap.policyengine.repository.PolicyRepository;

/**
 * Read-only aggregates for reporting dashboards and cross-service governance views.
 */
@Service
public class PolicyEngineMetricsService {

    private final PolicyRepository policyRepository;
    private final PolicyEvaluationRepository policyEvaluationRepository;

    public PolicyEngineMetricsService(PolicyRepository policyRepository,
                                      PolicyEvaluationRepository policyEvaluationRepository) {
        this.policyRepository = policyRepository;
        this.policyEvaluationRepository = policyEvaluationRepository;
    }

    @Transactional(readOnly = true)
    public long getActivePoliciesCount() {
        return policyRepository.countByStatus(PolicyStatus.ENABLED);
    }

    @Transactional(readOnly = true)
    public long getPolicyViolationsCount() {
        return policyEvaluationRepository.countByStatus(PolicyEvaluationStatus.FAIL)
                + policyEvaluationRepository.countByStatus(PolicyEvaluationStatus.ERROR);
    }

    @Transactional(readOnly = true)
    public double getPolicyComplianceRate() {
        long pass = policyEvaluationRepository.countByStatus(PolicyEvaluationStatus.PASS);
        long fail = policyEvaluationRepository.countByStatus(PolicyEvaluationStatus.FAIL);
        long denom = pass + fail;
        if (denom == 0) {
            return 100.0;
        }
        return Math.round(1000.0 * pass / denom) / 10.0;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPolicyComplianceBreakdown() {
        Map<String, Object> m = new HashMap<>();
        m.put("pass", policyEvaluationRepository.countByStatus(PolicyEvaluationStatus.PASS));
        m.put("fail", policyEvaluationRepository.countByStatus(PolicyEvaluationStatus.FAIL));
        m.put("error", policyEvaluationRepository.countByStatus(PolicyEvaluationStatus.ERROR));
        m.put("pending", policyEvaluationRepository.countByStatus(PolicyEvaluationStatus.PENDING));
        m.put("notApplicable", policyEvaluationRepository.countByStatus(PolicyEvaluationStatus.NOT_APPLICABLE));
        m.put("complianceRatePercent", getPolicyComplianceRate());
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPolicyActionItems(String severity, String status, int limit) {
        int cap = Math.min(Math.max(limit, 1), 200);
        List<PolicyEvaluation> evaluations = policyEvaluationRepository
                .findByStatus(PolicyEvaluationStatus.FAIL, PageRequest.of(0, cap))
                .getContent();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PolicyEvaluation ev : evaluations) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", ev.getId().toString());
            row.put("policyId", ev.getPolicyId().toString());
            row.put("targetAssetId", ev.getTargetAssetId());
            row.put("status", ev.getStatus().name());
            row.put("evaluatedAt", ev.getEvaluatedAt() != null ? ev.getEvaluatedAt().toString() : null);
            row.put("severity", "HIGH");
            rows.add(row);
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPolicyComplianceHistory(int days) {
        int d = Math.min(Math.max(days, 1), 365);
        Instant since = Instant.now().minus(d, ChronoUnit.DAYS);
        List<Object[]> raw = policyEvaluationRepository.summarizeEvaluationsByDay(since);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : raw) {
            Map<String, Object> m = new HashMap<>();
            // Hibernate ORM 7 returns a native-query date as java.time.LocalDate (Hibernate 6 returned
            // java.sql.Date). Both print yyyy-MM-dd, so the response keeps its "date" text (BOOT-01).
            Object day = row[0];
            Number passed = (Number) row[1];
            Number failed = (Number) row[2];
            m.put("date", day != null ? day.toString() : null);
            m.put("passed", passed != null ? passed.longValue() : 0L);
            m.put("failed", failed != null ? failed.longValue() : 0L);
            long p = passed != null ? passed.longValue() : 0L;
            long f = failed != null ? failed.longValue() : 0L;
            long total = p + f;
            m.put("complianceRatePercent", total == 0 ? 100.0 : Math.round(1000.0 * p / total) / 10.0);
            out.add(m);
        }
        return out;
    }
}
