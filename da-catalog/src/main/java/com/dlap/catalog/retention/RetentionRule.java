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
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

import com.dlap.common.model.RetentionBasis;

/**
 * One retention rule of one asset: a period in days counted from a basis time, or, with basis
 * {@link RetentionBasis#FIXED_DATE}, an expiry instant. The expiry of a day-based rule is the
 * basis time plus {@code days}.
 *
 * @param ruleId      the row id for stored rules; {@code null} for configuration and provider rules
 * @param source      where the rule is kept
 * @param basis       what the period counts from
 * @param days        the period in days, at least 0; {@code null} exactly for {@code FIXED_DATE}
 * @param fixedExpiry the expiry instant; set exactly for {@code FIXED_DATE}
 * @param policyId    the policy engine policy of a {@link RuleSource#POLICY} rule, otherwise {@code null}
 * @param mode        the {@code setRetention} mode ({@code soft} or {@code hard}), or {@code null}
 */
public record RetentionRule(
        UUID ruleId,
        RuleSource source,
        RetentionBasis basis,
        Integer days,
        Instant fixedExpiry,
        UUID policyId,
        String mode) {

    /** Orders rules by source rank, then basis, then period, then identifiers: a stable total order. */
    public static final Comparator<RetentionRule> TIE_ORDER = Comparator
            .comparing(RetentionRule::source)
            .thenComparing(RetentionRule::basis)
            .thenComparing(r -> r.days == null ? Integer.MIN_VALUE : r.days)
            .thenComparing(r -> r.fixedExpiry == null ? Instant.MIN : r.fixedExpiry)
            .thenComparing(r -> r.policyId == null ? "" : r.policyId.toString())
            .thenComparing(r -> r.ruleId == null ? "" : r.ruleId.toString());

    /**
     * Checks the shape: a {@code FIXED_DATE} rule has an expiry and no days, every other rule has
     * days of at least 0 and no expiry, and a {@code POLICY} rule names its policy.
     *
     * @throws IllegalArgumentException when the shape is wrong
     */
    public RetentionRule {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(basis, "basis");
        if (basis.requiresDays()) {
            if (days == null || days < 0) {
                throw new IllegalArgumentException("A " + basis + " rule needs days of at least 0");
            }
            if (fixedExpiry != null) {
                throw new IllegalArgumentException("A " + basis + " rule has no fixed expiry");
            }
        } else {
            if (fixedExpiry == null) {
                throw new IllegalArgumentException("A FIXED_DATE rule needs an expiry");
            }
            if (days != null) {
                throw new IllegalArgumentException("A FIXED_DATE rule has no days");
            }
        }
        if (source == RuleSource.POLICY && policyId == null) {
            throw new IllegalArgumentException("A POLICY rule needs its policy id");
        }
    }

    /**
     * A day-based rule.
     *
     * @param source   the source
     * @param basis    a basis other than {@code FIXED_DATE}
     * @param days     the period in days
     * @return the rule without an id or policy
     */
    public static RetentionRule ofDays(RuleSource source, RetentionBasis basis, int days) {
        return new RetentionRule(null, source, basis, days, null, null, null);
    }

    /**
     * A rule with a fixed expiry.
     *
     * @param source the source
     * @param expiry the expiry instant
     * @return the rule without an id or policy
     */
    public static RetentionRule ofFixedDate(RuleSource source, Instant expiry) {
        return new RetentionRule(null, source, RetentionBasis.FIXED_DATE, null, expiry, null, null);
    }

    /**
     * A policy's day-based rule.
     *
     * @param basis    a basis other than {@code FIXED_DATE}
     * @param days     the period in days
     * @param policyId the policy engine's policy id
     * @return the rule without a row id
     */
    public static RetentionRule ofPolicy(RetentionBasis basis, int days, UUID policyId) {
        return new RetentionRule(null, RuleSource.POLICY, basis, days, null, policyId, null);
    }
}
