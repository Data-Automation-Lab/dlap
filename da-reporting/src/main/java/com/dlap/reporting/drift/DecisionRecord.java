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

package com.dlap.reporting.drift;

import java.time.Instant;

/**
 * One PDP access-decision, projected to the fields the drift join needs.
 * Sourced from the {@code dlap.access.decision} event (enforcement-contract.md
 * &#167;6). Immutable; the correlation join in {@link DriftDetectionService} is a
 * pure function of these plus {@link ReadRecord}s.
 *
 * @param decisionId the PDP decision id ({@code decisionId})
 * @param subjectId  the evaluated DLAP subject ({@code subjectId}); the subject join key
 * @param assetId    the asset join key: the rolled-up canonical URI of {@code assetUri}
 *                   (else of {@code assetId}), or the id as written when it does not
 *                   normalize (enforcement-contract.md section 6.1)
 * @param allow      {@code true} for ALLOW, {@code false} for DENY
 * @param decidedAt  the decision timestamp ({@code timestamp})
 */
public record DecisionRecord(
        String decisionId,
        String subjectId,
        String assetId,
        boolean allow,
        Instant decidedAt) {
}
