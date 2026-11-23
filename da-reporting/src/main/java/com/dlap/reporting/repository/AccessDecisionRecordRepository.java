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

package com.dlap.reporting.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import com.dlap.reporting.model.AccessDecisionRecordEntity;

public interface AccessDecisionRecordRepository extends JpaRepository<AccessDecisionRecordEntity, UUID> {

    boolean existsByDecisionId(String decisionId);

    /**
     * Decisions for the given asset+subject decided at or after {@code since}:
     * the candidate set the join needs to correlate one read. Bounded by the
     * (asset, subject) index so the lookup stays cheap under load.
     */
    List<AccessDecisionRecordEntity> findByAssetIdAndSubjectIdAndDecidedAtGreaterThanEqual(
            String assetId, String subjectId, Instant since);

    /** Retention sweep: delete buffered decisions older than {@code cutoff}. */
    long deleteByDecidedAtBefore(@Param("cutoff") Instant cutoff);
}
