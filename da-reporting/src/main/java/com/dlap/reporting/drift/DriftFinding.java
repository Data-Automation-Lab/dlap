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
 * A single detected divergence between what the PDP decided and what actually
 * happened (ADR-0040). Produced by {@link DriftDetectionService#detect}; the
 * value object the persistence + API layers carry.
 *
 * @param type       {@link DriftType#UNENFORCED_READ} or
 *                   {@link DriftType#ENFORCEMENT_BYPASS}
 * @param subjectId  the principal that performed the read
 * @param assetId    the asset that was read
 * @param decisionId the correlated DENY decision for a bypass; {@code null}
 *                   for an unenforced read (there was no decision)
 * @param readAt     when the offending read occurred
 * @param detail     human-readable one-line explanation
 */
public record DriftFinding(
        DriftType type,
        String subjectId,
        String assetId,
        String decisionId,
        Instant readAt,
        String detail) {
}
