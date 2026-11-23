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
import java.util.Objects;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

/**
 * The result of one evaluation (asset-descriptor spec "Retention > Evaluation"): the status, the
 * rule that decided it, the expiry it found, and the reason in the open vocabulary of the
 * retention-due event. The fields {@link #retentionDays()}, {@link #retentionBasis()},
 * {@link #retentionSource()}, {@link #retentionPolicyId()}, {@link #expiresAt()} and
 * {@link #status()} are what the sweep writes to {@code dlap_assets.retention_*}.
 *
 * @param status       the retention status
 * @param reason       why, for the retention-due event; never {@code null}
 * @param decidingRule the rule that decided the result, or {@code null} for {@code NO_POLICY}
 *                     and for an asset on hold that no rule applies to
 * @param expiresAt    the expiry the evaluation found (spec step 5), or {@code null}
 */
public record RetentionEvaluation(
        RetentionStatus status,
        String reason,
        RetentionRule decidingRule,
        Instant expiresAt) {

    /** The reason of a {@code DUE} result. */
    public static final String EXPIRY_PASSED = "EXPIRY_PASSED";
    /** The reason of a {@code RETAINED} result. */
    public static final String NOT_EXPIRED = "NOT_EXPIRED";
    /** The reason of a {@code NO_POLICY} result. */
    public static final String NO_RULE = "NO_RULE";
    /** The basis time of an undecided rule is not known (or no upper bound exists for it). */
    public static final String BASIS_UNKNOWN = "BASIS_UNKNOWN";
    /** A last-access rule is undecided because no binding captures reads for the asset. */
    public static final String READ_COVERAGE_NOT_CAPTURED = "READ_COVERAGE_NOT_CAPTURED";
    /** A last-access rule is undecided because read capture is complete only up to an earlier instant. */
    public static final String READ_COVERAGE_STALE = "READ_COVERAGE_STALE";
    /** A last-modified rule is undecided because unlogged later writes cannot be ruled out. */
    public static final String MODIFIED_TIME_UNBOUNDED = "MODIFIED_TIME_UNBOUNDED";
    /** The evidence bounds the expiry on both sides of now, so it proves neither. */
    public static final String EXPIRY_UNDETERMINED = "EXPIRY_UNDETERMINED";
    /** A provider retention setting is present but unreadable, so a due result cannot be trusted. */
    public static final String PROVIDER_RULE_UNREADABLE = "PROVIDER_RULE_UNREADABLE";
    /** The asset is under the DLAP legal hold. */
    public static final String HOLD_PLACED = "HOLD_PLACED";
    /** The asset carries a label that the configuration maps to a hold. */
    public static final String HOLD_LABEL = "HOLD_LABEL";

    /**
     * Creates the evaluation.
     *
     * @param status       the status
     * @param reason       the reason
     * @param decidingRule the deciding rule, if any
     * @param expiresAt    the expiry, if any
     */
    public RetentionEvaluation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * The period of the deciding rule.
     *
     * @return days, or {@code null} for a {@code FIXED_DATE} rule or no rule
     */
    public Integer retentionDays() {
        return decidingRule == null ? null : decidingRule.days();
    }

    /**
     * The basis of the deciding rule.
     *
     * @return the basis, or {@code null} when no rule decided
     */
    public RetentionBasis retentionBasis() {
        return decidingRule == null ? null : decidingRule.basis();
    }

    /**
     * The source of the deciding rule.
     *
     * @return the source name, or {@code null} when no rule decided
     */
    public String retentionSource() {
        return decidingRule == null ? null : decidingRule.source().name();
    }

    /**
     * The policy of the deciding rule.
     *
     * @return the policy id, or {@code null}
     */
    public java.util.UUID retentionPolicyId() {
        return decidingRule == null ? null : decidingRule.policyId();
    }
}
