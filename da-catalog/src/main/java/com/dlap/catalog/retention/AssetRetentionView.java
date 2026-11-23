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
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The retention state of one asset: the result of the last evaluation and every rule that applies.
 * The result fields are those of {@code dlap_assets.retention_*}; a null field is unknown.
 *
 * @param assetId          the asset
 * @param assetUri         its canonical URI
 * @param retentionStatus  {@code UNKNOWN}, {@code NO_POLICY}, {@code RETAINED}, {@code DUE} or {@code ON_HOLD}
 * @param retentionExpiresAt the expiry the last evaluation found
 * @param retentionEvaluatedAt when the last evaluation ran
 * @param retentionDays    period of the deciding rule
 * @param retentionBasis   basis of the deciding rule
 * @param retentionSource  source of the deciding rule
 * @param retentionPolicyId policy of the deciding rule
 * @param legalHold        whether the asset is on the DLAP legal hold
 * @param rules            every rule that applies: stored, label default and provider rules
 */
@Schema(description = "Retention state and rules of one asset.")
public record AssetRetentionView(
        UUID assetId,
        String assetUri,
        String retentionStatus,
        Instant retentionExpiresAt,
        Instant retentionEvaluatedAt,
        Integer retentionDays,
        String retentionBasis,
        String retentionSource,
        UUID retentionPolicyId,
        boolean legalHold,
        List<RetentionRuleView> rules) {
}
