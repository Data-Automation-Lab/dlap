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

package com.dlap.catalog.retention;

import java.time.Instant;

/**
 * The evidence columns of one asset that retention rules are measured against. Every time is a
 * business time from provider evidence and {@code null} means unknown (ADR-0046 rule 2).
 *
 * @param cloudCreatedAt   provider creation time of the current incarnation
 * @param discoveredAt     when DLAP first saw the asset, an upper bound for the creation time
 * @param lastAccessedAt   latest event time of a successful read
 * @param lastModifiedAt   latest event time of a successful write
 * @param legalHold        the DLAP legal hold
 * @param heldByLabel      whether a label that the configuration maps to a hold is assigned
 * @param providerRuleUnreadable a provider retention setting is present but could not be read, so
 *                         the asset may be under a rule the evaluator cannot see
 */
public record RetentionEvidence(
        Instant cloudCreatedAt,
        Instant discoveredAt,
        Instant lastAccessedAt,
        Instant lastModifiedAt,
        boolean legalHold,
        boolean heldByLabel,
        boolean providerRuleUnreadable) {
}
