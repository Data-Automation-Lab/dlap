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
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A retention rule as the API shows it.
 *
 * @param ruleId    the row id of a stored rule; null for a label default or a provider rule
 * @param source    {@code MANUAL}, {@code POLICY}, {@code LABEL_DEFAULT} or {@code PROVIDER}
 * @param basis     what the period counts from
 * @param days      the period in days; null for {@code FIXED_DATE}
 * @param expiresAt the expiry instant of a {@code FIXED_DATE} rule
 * @param policyId  the policy of a {@code POLICY} rule
 * @param mode      {@code soft} or {@code hard} for a policy rule
 * @param reason    the operator's reason of a {@code MANUAL} rule
 * @param issuedAt  when the policy fired or the operator created the rule
 * @param createdBy the operator of a {@code MANUAL} rule
 */
@Schema(description = "One retention rule that applies to an asset.")
public record RetentionRuleView(
        UUID ruleId,
        String source,
        String basis,
        Integer days,
        Instant expiresAt,
        UUID policyId,
        String mode,
        String reason,
        Instant issuedAt,
        String createdBy) {
}
