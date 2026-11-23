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

package com.dlap.policyengine.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One version of a policy as the versions API answers it (AUD-02a). The list leaves the content out;
 * the single version carries it ({@code description}, {@code conditionLogic}, {@code actions},
 * {@code policyDocument}).
 *
 * @param policyId       the policy
 * @param version        the version number, from 1
 * @param changeType     what made the version
 * @param name           the policy name at this version
 * @param status         the policy status at this version
 * @param deleted        true for the version that deleted the policy
 * @param contentSha256  SHA-256 of the stored snapshot, also in the audit entry
 * @param changedBy      who made the change: a JWT {@code sub}, a client id or a component
 * @param changedByKind  {@code USER}, {@code SERVICE} or {@code SYSTEM}
 * @param changedAt      when the change happened
 * @param auditStream    the audit stream of the version's entry, {@code govern.policy}
 * @param auditSeq       the position of that entry
 * @param auditEntryHash the hash of that entry
 * @param description    the description at this version; only on a single version
 * @param conditionLogic the display note at this version; only on a single version
 * @param actions        the actions at this version; only on a single version
 * @param policyDocument the dlap/v1 DSL document at this version; only on a single version
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One immutable version of a policy (AUD-02a)")
public record PolicyVersionView(
        UUID policyId,
        int version,
        PolicyChangeType changeType,
        String name,
        PolicyStatus status,
        boolean deleted,
        String contentSha256,
        String changedBy,
        String changedByKind,
        Instant changedAt,
        String auditStream,
        long auditSeq,
        String auditEntryHash,
        String description,
        String conditionLogic,
        Map<String, Object> actions,
        String policyDocument) {
}
