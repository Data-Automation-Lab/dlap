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

import com.dlap.common.model.RetentionBasis;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /assets/{assetId}/retention/rules}: an operator's retention rule.
 * A day-based rule carries {@code days}; a {@code FIXED_DATE} rule carries {@code expiresAt}.
 *
 * @param basis     what the period counts from: {@code CREATED}, {@code LAST_MODIFIED},
 *                  {@code LAST_ACCESSED} or {@code FIXED_DATE}
 * @param days      the period in days, 0 to 365000; required unless the basis is {@code FIXED_DATE}
 * @param expiresAt the expiry instant (ISO-8601); required for {@code FIXED_DATE}
 * @param reason    why the rule is set, recorded in the audit trail
 */
@Schema(description = "An operator's retention rule for one asset (source MANUAL).")
public record RetentionRuleRequest(
        @NotNull @Schema(description = "What the period counts from") RetentionBasis basis,
        @Schema(description = "Period in days, 0 to 365000; omitted for FIXED_DATE", example = "365") Integer days,
        @Schema(description = "Expiry instant for FIXED_DATE", example = "2027-12-31T00:00:00Z") Instant expiresAt,
        @Size(max = 1000) @Schema(description = "Why, for the audit trail") String reason) {
}
