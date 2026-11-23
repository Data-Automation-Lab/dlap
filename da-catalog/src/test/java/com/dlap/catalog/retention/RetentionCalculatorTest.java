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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

/**
 * The retention evaluation of ADR-0046 as the spec states it ("Retention > Evaluation"): a rule is
 * DUE when the latest possible expiry has passed, RETAINED when the earliest possible expiry has
 * not, and UNKNOWN otherwise, so missing evidence never proves an asset stale or fresh. Every
 * test fixes the as-of instant, the evidence and the expected expiry date, so the dates in the
 * assertions can be checked by hand.
 */
class RetentionCalculatorTest {

    /** The evaluation instant of every test unless it says otherwise. */
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    private static RetentionEvidence evidence(String created, String discovered, String lastRead, String lastWrite) {
        return new RetentionEvidence(created == null ? null : at(created), discovered == null ? null : at(discovered),
                lastRead == null ? null : at(lastRead), lastWrite == null ? null : at(lastWrite), false, false, false);
    }

    private static RetentionEvidence held(RetentionEvidence e, boolean legalHold, boolean byLabel) {
        return new RetentionEvidence(e.cloudCreatedAt(), e.discoveredAt(), e.lastAccessedAt(), e.lastModifiedAt(),
                legalHold, byLabel, e.providerRuleUnreadable());
    }

    private static RetentionRule days(RuleSource source, RetentionBasis basis, int days) {
        return RetentionRule.ofDays(source, basis, days);
    }

    private static ReadCoverage coverage(String since, String validThrough) {
        return new ReadCoverage(at(since), at(validThrough));
    }

    // ---- no rule ----------------------------------------------------------------------------

    @Test
    void noRuleIsNoPolicyWithNoExpiry() {
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW, evidence("2020-01-01T00:00:00Z", null, null, null),
                List.of(), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.NO_POLICY);
        assertThat(e.decidingRule()).isNull();
        assertThat(e.expiresAt()).isNull();
        assertThat(e.reason()).isEqualTo(RetentionEvaluation.NO_RULE);
    }

    // ---- CREATED ----------------------------------------------------------------------------

    @Test
    void createdRuleIsDueOnceCreationPlusDaysHasPassed() {
        // Created 2025-01-01 + 365 days = 2026-01-01, before 2026-10-20.
        RetentionRule rule = days(RuleSource.MANUAL, RetentionBasis.CREATED, 365);

        RetentionEvaluation e = RetentionCalculator.evaluate(NOW, evidence("2025-01-01T00:00:00Z", null, null, null),
                List.of(rule), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.DUE);
        assertThat(e.expiresAt()).isEqualTo(at("2026-01-01T00:00:00Z"));
        assertThat(e.decidingRule()).isEqualTo(rule);
        assertThat(e.retentionDays()).isEqualTo(365);
        assertThat(e.retentionBasis()).isEqualTo(RetentionBasis.CREATED);
        assertThat(e.retentionSource()).isEqualTo("MANUAL");
        assertThat(e.reason()).isEqualTo(RetentionEvaluation.EXPIRY_PASSED);
    }

    @Test
    void createdRuleIsRetainedUntilCreationPlusDays() {
        // Created 2026-06-01 + 365 days = 2027-06-01, after 2026-10-20.
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW, evidence("2026-06-01T00:00:00Z", null, null, null),
                List.of(RetentionRule.ofPolicy(RetentionBasis.CREATED, 365, UUID.randomUUID())), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.RETAINED);
        assertThat(e.expiresAt()).isEqualTo(at("2027-06-01T00:00:00Z"));
        assertThat(e.reason()).isEqualTo(RetentionEvaluation.NOT_EXPIRED);
    }

    @Test
    void unknownCreationFallsBackToDiscoveryAsAnUpperBoundOnly() {
        // Never proves RETAINED (creation could be much earlier) but proves DUE when even the
        // latest possible creation, the discovery time 2024-01-01, plus 30 days is in the past.
        RetentionRule rule = days(RuleSource.MANUAL, RetentionBasis.CREATED, 30);

        RetentionEvaluation old = RetentionCalculator.evaluate(NOW, evidence(null, "2024-01-01T00:00:00Z", null, null),
                List.of(rule), null);
        RetentionEvaluation recent = RetentionCalculator.evaluate(NOW,
                evidence(null, "2026-10-01T00:00:00Z", null, null), List.of(rule), null);

        assertThat(old.status()).isEqualTo(RetentionStatus.DUE);
        assertThat(old.expiresAt()).isEqualTo(at("2024-01-31T00:00:00Z"));
        assertThat(recent.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(recent.reason()).isEqualTo(RetentionEvaluation.BASIS_UNKNOWN);
        assertThat(recent.expiresAt()).isNull();
    }

    @Test
    void createdRuleWithNoCreationAndNoDiscoveryIsUnknown() {
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW, evidence(null, null, null, null),
                List.of(days(RuleSource.MANUAL, RetentionBasis.CREATED, 30)), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(e.reason()).isEqualTo(RetentionEvaluation.BASIS_UNKNOWN);
    }

    // ---- LAST_ACCESSED and the read signal --------------------------------------------------

    @Test
    void anOldLastReadIsNotDueWhileReadsAreNotCaptured() {
        // The headline rule: last read 2024-01-01 + 90 days is long past, but without a read
        // signal DLAP cannot show the asset was not read since, so it is UNKNOWN, never DUE.
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW,
                evidence("2023-01-01T00:00:00Z", null, "2024-01-01T00:00:00Z", null),
                List.of(RetentionRule.ofPolicy(RetentionBasis.LAST_ACCESSED, 90, UUID.randomUUID())), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(e.reason()).isEqualTo(RetentionEvaluation.READ_COVERAGE_NOT_CAPTURED);
        assertThat(e.expiresAt()).isNull();
    }

    @Test
    void aRecentLastReadRetainsEvenWithoutAReadSignal() {
        // Last read 2026-09-01 + 90 days = 2026-11-30 is after 2026-10-20: a recorded read is proof.
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW,
                evidence("2023-01-01T00:00:00Z", null, "2026-09-01T00:00:00Z", null),
                List.of(days(RuleSource.MANUAL, RetentionBasis.LAST_ACCESSED, 90)), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.RETAINED);
        assertThat(e.expiresAt()).isEqualTo(at("2026-11-30T00:00:00Z"));
    }

    @Test
    void withCapturedReadsAnOldLastReadIsDue() {
        // Reads captured since 2025-01-01 through now. Last read 2025-03-01 + 90 days = 2025-05-30.
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW,
                evidence("2024-01-01T00:00:00Z", null, "2025-03-01T00:00:00Z", null),
                List.of(days(RuleSource.MANUAL, RetentionBasis.LAST_ACCESSED, 90)),
                coverage("2025-01-01T00:00:00Z", "2026-10-20T12:00:00Z"));

        assertThat(e.status()).isEqualTo(RetentionStatus.DUE);
        assertThat(e.expiresAt()).isEqualTo(at("2025-05-30T00:00:00Z"));
    }

    @Test
    void anAssetNeverReadIsIdleSinceCaptureStartOrCreationWhicheverIsLater() {
        RetentionRule rule = days(RuleSource.MANUAL, RetentionBasis.LAST_ACCESSED, 90);

        // Never read, created 2025-01-01, capture since 2026-01-01: idle at least since
        // 2026-01-01, upper bound 2026-01-01 + 90 days = 2026-04-01, before now: DUE.
        RetentionEvaluation lateCapture = RetentionCalculator.evaluate(NOW,
                evidence("2025-01-01T00:00:00Z", null, null, null), List.of(rule),
                coverage("2026-01-01T00:00:00Z", "2026-10-20T12:00:00Z"));
        // Created 2026-09-01 (after capture start 2025-01-01): upper bound is the creation,
        // 2026-09-01 + 90 days = 2026-11-30, after now: not DUE; lower bound is also the creation,
        // so RETAINED until 2026-11-30.
        RetentionEvaluation recentlyCreated = RetentionCalculator.evaluate(NOW,
                evidence("2026-09-01T00:00:00Z", null, null, null), List.of(rule),
                coverage("2025-01-01T00:00:00Z", "2026-10-20T12:00:00Z"));

        assertThat(lateCapture.status()).isEqualTo(RetentionStatus.DUE);
        assertThat(lateCapture.expiresAt()).isEqualTo(at("2026-04-01T00:00:00Z"));
        assertThat(recentlyCreated.status()).isEqualTo(RetentionStatus.RETAINED);
        assertThat(recentlyCreated.expiresAt()).isEqualTo(at("2026-11-30T00:00:00Z"));
    }

    @Test
    void captureThatIsCompleteOnlyThroughAnEarlierInstantCannotProveDueBeyondIt() {
        RetentionRule rule = days(RuleSource.MANUAL, RetentionBasis.LAST_ACCESSED, 90);
        RetentionEvidence e = evidence("2024-01-01T00:00:00Z", null, "2026-06-01T00:00:00Z", null);

        // Last read 2026-06-01 + 90 days = 2026-08-30, before now (2026-10-20). Capture complete
        // only through 2026-07-15: reads after that may still be in flight, so not provable.
        RetentionEvaluation behind = RetentionCalculator.evaluate(NOW, e, List.of(rule),
                coverage("2025-01-01T00:00:00Z", "2026-07-15T00:00:00Z"));
        // Complete through 2026-09-15, after the expiry 2026-08-30: provable.
        RetentionEvaluation caughtUp = RetentionCalculator.evaluate(NOW, e, List.of(rule),
                coverage("2025-01-01T00:00:00Z", "2026-09-15T00:00:00Z"));

        assertThat(behind.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(behind.reason()).isEqualTo(RetentionEvaluation.READ_COVERAGE_STALE);
        assertThat(caughtUp.status()).isEqualTo(RetentionStatus.DUE);
        assertThat(caughtUp.expiresAt()).isEqualTo(at("2026-08-30T00:00:00Z"));
    }

    @Test
    void aLastReadBeforeCaptureStartDoesNotLowerTheUpperBound() {
        // Last read 2024-01-01, but capture only began 2026-06-01: the asset may have been read
        // any time since 2024-01-01 up to 2026-06-01, so the upper bound is 2026-06-01 + 90 days
        // = 2026-08-30, still before now: DUE, with the expiry from the capture start.
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW,
                evidence("2023-01-01T00:00:00Z", null, "2024-01-01T00:00:00Z", null),
                List.of(days(RuleSource.MANUAL, RetentionBasis.LAST_ACCESSED, 90)),
                coverage("2026-06-01T00:00:00Z", "2026-10-20T12:00:00Z"));

        assertThat(e.status()).isEqualTo(RetentionStatus.DUE);
        assertThat(e.expiresAt()).isEqualTo(at("2026-08-30T00:00:00Z"));
    }

    @Test
    void captureStartingTooRecentlyLeavesTheRuleUndetermined() {
        // Capture since 2026-09-01 with 90 days: upper bound 2026-11-30 is after now, and the
        // lower bound (last read 2024-01-01 + 90) is long past: neither side is proven.
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW,
                evidence("2023-01-01T00:00:00Z", null, "2024-01-01T00:00:00Z", null),
                List.of(days(RuleSource.MANUAL, RetentionBasis.LAST_ACCESSED, 90)),
                coverage("2026-09-01T00:00:00Z", "2026-10-20T12:00:00Z"));

        assertThat(e.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(e.reason()).isEqualTo(RetentionEvaluation.EXPIRY_UNDETERMINED);
    }

    // ---- LAST_MODIFIED ----------------------------------------------------------------------

    @Test
    void lastModifiedHasNoUpperBoundSoItIsNeverDue() {
        RetentionRule rule = days(RuleSource.MANUAL, RetentionBasis.LAST_MODIFIED, 30);

        RetentionEvaluation old = RetentionCalculator.evaluate(NOW,
                evidence("2020-01-01T00:00:00Z", null, null, "2021-01-01T00:00:00Z"), List.of(rule), null);
        RetentionEvaluation oldWithCoverage = RetentionCalculator.evaluate(NOW,
                evidence("2020-01-01T00:00:00Z", null, null, "2021-01-01T00:00:00Z"), List.of(rule),
                coverage("2020-01-01T00:00:00Z", "2026-10-20T12:00:00Z"));
        RetentionEvaluation recent = RetentionCalculator.evaluate(NOW,
                evidence("2020-01-01T00:00:00Z", null, null, "2026-10-10T00:00:00Z"), List.of(rule), null);
        RetentionEvaluation never = RetentionCalculator.evaluate(NOW,
                evidence("2020-01-01T00:00:00Z", null, null, null), List.of(rule), null);

        assertThat(old.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(old.reason()).isEqualTo(RetentionEvaluation.MODIFIED_TIME_UNBOUNDED);
        assertThat(oldWithCoverage.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(recent.status()).isEqualTo(RetentionStatus.RETAINED);
        assertThat(recent.expiresAt()).isEqualTo(at("2026-11-09T00:00:00Z"));
        assertThat(never.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(never.reason()).isEqualTo(RetentionEvaluation.BASIS_UNKNOWN);
    }

    // ---- FIXED_DATE -------------------------------------------------------------------------

    @Test
    void fixedDateRuleIsExactBothWays() {
        RetentionEvaluation past = RetentionCalculator.evaluate(NOW, evidence(null, null, null, null),
                List.of(RetentionRule.ofFixedDate(RuleSource.PROVIDER, at("2026-03-01T00:00:00Z"))), null);
        RetentionEvaluation future = RetentionCalculator.evaluate(NOW, evidence(null, null, null, null),
                List.of(RetentionRule.ofFixedDate(RuleSource.PROVIDER, at("2027-03-01T00:00:00Z"))), null);

        assertThat(past.status()).isEqualTo(RetentionStatus.DUE);
        assertThat(past.expiresAt()).isEqualTo(at("2026-03-01T00:00:00Z"));
        assertThat(past.retentionDays()).isNull();
        assertThat(past.retentionBasis()).isEqualTo(RetentionBasis.FIXED_DATE);
        assertThat(future.status()).isEqualTo(RetentionStatus.RETAINED);
        assertThat(future.expiresAt()).isEqualTo(at("2027-03-01T00:00:00Z"));
    }

    // ---- boundaries and the clock -----------------------------------------------------------

    @Test
    void anExpiryExactlyAtTheAsOfInstantIsDueAndOneNanosecondLaterIsRetained() {
        RetentionRule atNow = RetentionRule.ofFixedDate(RuleSource.MANUAL, NOW);
        RetentionRule afterNow = RetentionRule.ofFixedDate(RuleSource.MANUAL, NOW.plusNanos(1));

        assertThat(RetentionCalculator.evaluate(NOW, evidence(null, null, null, null), List.of(atNow), null)
                .status()).isEqualTo(RetentionStatus.DUE);
        assertThat(RetentionCalculator.evaluate(NOW, evidence(null, null, null, null), List.of(afterNow), null)
                .status()).isEqualTo(RetentionStatus.RETAINED);
    }

    @Test
    void zeroDaysExpiresAtTheBasisTime() {
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW, evidence("2026-10-20T11:59:59Z", null, null, null),
                List.of(days(RuleSource.MANUAL, RetentionBasis.CREATED, 0)), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.DUE);
        assertThat(e.expiresAt()).isEqualTo(at("2026-10-20T11:59:59Z"));
    }

    @Test
    void theSameEvidenceMovesFromRetainedToDueAsTheInjectedClockAdvances() {
        RetentionRule rule = days(RuleSource.MANUAL, RetentionBasis.CREATED, 10);
        RetentionEvidence e = evidence("2026-10-01T00:00:00Z", null, null, null);

        assertThat(RetentionCalculator.evaluate(at("2026-10-10T23:59:59Z"), e, List.of(rule), null).status())
                .isEqualTo(RetentionStatus.RETAINED);
        assertThat(RetentionCalculator.evaluate(at("2026-10-11T00:00:00Z"), e, List.of(rule), null).status())
                .isEqualTo(RetentionStatus.DUE);
        assertThat(RetentionCalculator.evaluate(at("2026-12-01T00:00:00Z"), e, List.of(rule), null).status())
                .isEqualTo(RetentionStatus.DUE);
    }

    @Test
    void aPeriodTooLongForAnInstantNeverExpires() {
        RetentionRule rule = days(RuleSource.MANUAL, RetentionBasis.CREATED, RetentionProperties.MAX_DAYS);
        Instant nearTheEnd = Instant.parse("+999999999-12-31T00:00:00Z");

        assertThat(RetentionCalculator.expiry(rule, nearTheEnd)).isEqualTo(Instant.MAX);
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW,
                evidence("+999999999-12-31T00:00:00Z", null, null, null), List.of(rule), null);
        assertThat(e.status()).isEqualTo(RetentionStatus.RETAINED);
        assertThat(e.expiresAt()).isEqualTo(Instant.MAX);
    }

    // ---- several rules ----------------------------------------------------------------------

    @Test
    void anAssetIsDueOnlyWhenEveryRuleIsDueAndTheLatestExpiryDecides() {
        RetentionRule short30 = days(RuleSource.LABEL_DEFAULT, RetentionBasis.CREATED, 30);
        RetentionRule long400 = days(RuleSource.MANUAL, RetentionBasis.CREATED, 400);

        // Created 2025-01-01: +30d = 2025-01-31, +400d = 2026-02-05, both before now.
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW, evidence("2025-01-01T00:00:00Z", null, null, null),
                List.of(short30, long400), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.DUE);
        assertThat(e.decidingRule()).isEqualTo(long400);
        assertThat(e.expiresAt()).isEqualTo(at("2026-02-05T00:00:00Z"));
    }

    @Test
    void oneRetainingRuleRetainsTheAssetAndTheLongestRetainingRuleDecides() {
        RetentionRule expired = days(RuleSource.MANUAL, RetentionBasis.CREATED, 30);
        RetentionRule retainsToMay = RetentionRule.ofPolicy(RetentionBasis.CREATED, 600, UUID.randomUUID());
        RetentionRule retainsToNov = days(RuleSource.LABEL_DEFAULT, RetentionBasis.CREATED, 700);

        RetentionEvaluation e = RetentionCalculator.evaluate(NOW, evidence("2025-06-01T00:00:00Z", null, null, null),
                List.of(expired, retainsToMay, retainsToNov), null);

        // +30d = 2025-07-01 (expired), +600d = 2027-01-22 and +700d = 2027-05-02 (both retain).
        assertThat(e.status()).isEqualTo(RetentionStatus.RETAINED);
        assertThat(e.decidingRule()).isEqualTo(retainsToNov);
        assertThat(e.expiresAt()).isEqualTo(at("2027-05-02T00:00:00Z"));
    }

    @Test
    void anUndecidedRuleKeepsTheAssetUnknownEvenWhenTheOthersAreDue() {
        RetentionRule due = days(RuleSource.MANUAL, RetentionBasis.CREATED, 30);
        RetentionRule undecided = RetentionRule.ofPolicy(RetentionBasis.LAST_ACCESSED, 90, UUID.randomUUID());

        RetentionEvaluation e = RetentionCalculator.evaluate(NOW,
                evidence("2020-01-01T00:00:00Z", null, "2021-01-01T00:00:00Z", null), List.of(due, undecided), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(e.decidingRule()).isEqualTo(undecided);
        assertThat(e.reason()).isEqualTo(RetentionEvaluation.READ_COVERAGE_NOT_CAPTURED);
        assertThat(e.expiresAt()).isNull();
    }

    @Test
    void equalRulesTieInTheOrderManualPolicyLabelDefaultProvider() {
        UUID policy = UUID.randomUUID();
        RetentionRule provider = days(RuleSource.PROVIDER, RetentionBasis.CREATED, 30);
        RetentionRule label = days(RuleSource.LABEL_DEFAULT, RetentionBasis.CREATED, 30);
        RetentionRule policyRule = RetentionRule.ofPolicy(RetentionBasis.CREATED, 30, policy);
        RetentionRule manual = days(RuleSource.MANUAL, RetentionBasis.CREATED, 30);
        RetentionEvidence e = evidence("2025-01-01T00:00:00Z", null, null, null);

        assertThat(RetentionCalculator.evaluate(NOW, e, List.of(provider, label, policyRule, manual), null)
                .decidingRule()).isEqualTo(manual);
        assertThat(RetentionCalculator.evaluate(NOW, e, List.of(provider, label, policyRule), null)
                .decidingRule()).isEqualTo(policyRule);
        assertThat(RetentionCalculator.evaluate(NOW, e, List.of(provider, label), null)
                .decidingRule()).isEqualTo(label);
        assertThat(RetentionCalculator.evaluate(NOW, e, List.of(provider), null)
                .decidingRule()).isEqualTo(provider);
    }

    // ---- legal hold -------------------------------------------------------------------------

    @Test
    void aLegalHoldOverridesADueResultButKeepsTheRuleAndExpiryForInformation() {
        RetentionRule rule = days(RuleSource.MANUAL, RetentionBasis.CREATED, 365);

        RetentionEvaluation e = RetentionCalculator.evaluate(NOW,
                held(evidence("2025-01-01T00:00:00Z", null, null, null), true, false), List.of(rule), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.ON_HOLD);
        assertThat(e.reason()).isEqualTo(RetentionEvaluation.HOLD_PLACED);
        assertThat(e.decidingRule()).isEqualTo(rule);
        assertThat(e.expiresAt()).isEqualTo(at("2026-01-01T00:00:00Z"));
    }

    @Test
    void aHoldWithUndecidedRulesHasNoExpiryAndAHoldWithNoRulesHasNoRule() {
        RetentionEvaluation undecided = RetentionCalculator.evaluate(NOW,
                held(evidence("2020-01-01T00:00:00Z", null, "2021-01-01T00:00:00Z", null), true, false),
                List.of(days(RuleSource.MANUAL, RetentionBasis.LAST_ACCESSED, 90)), null);
        RetentionEvaluation none = RetentionCalculator.evaluate(NOW, held(evidence(null, null, null, null), true, false),
                List.of(), null);

        assertThat(undecided.status()).isEqualTo(RetentionStatus.ON_HOLD);
        assertThat(undecided.expiresAt()).isNull();
        assertThat(none.status()).isEqualTo(RetentionStatus.ON_HOLD);
        assertThat(none.decidingRule()).isNull();
        assertThat(none.expiresAt()).isNull();
    }

    @Test
    void aConfiguredHoldLabelHoldsWithItsOwnReason() {
        RetentionEvaluation e = RetentionCalculator.evaluate(NOW,
                held(evidence("2025-01-01T00:00:00Z", null, null, null), false, true),
                List.of(days(RuleSource.MANUAL, RetentionBasis.CREATED, 365)), null);

        assertThat(e.status()).isEqualTo(RetentionStatus.ON_HOLD);
        assertThat(e.reason()).isEqualTo(RetentionEvaluation.HOLD_LABEL);
    }

    // ---- unreadable provider setting --------------------------------------------------------

    @Test
    void anUnreadableProviderSettingFailsClosedButNeverWeakensARetainedResult() {
        RetentionEvidence unreadable = new RetentionEvidence(at("2025-01-01T00:00:00Z"), null, null, null, false,
                false, true);

        RetentionEvaluation due = RetentionCalculator.evaluate(NOW, unreadable,
                List.of(days(RuleSource.MANUAL, RetentionBasis.CREATED, 30)), null);
        RetentionEvaluation none = RetentionCalculator.evaluate(NOW, unreadable, List.of(), null);
        RetentionEvaluation retained = RetentionCalculator.evaluate(NOW, unreadable,
                List.of(days(RuleSource.MANUAL, RetentionBasis.CREATED, 3650)), null);

        assertThat(due.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(due.reason()).isEqualTo(RetentionEvaluation.PROVIDER_RULE_UNREADABLE);
        assertThat(none.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(retained.status()).isEqualTo(RetentionStatus.RETAINED);
    }

    // ---- rule shape -------------------------------------------------------------------------

    @Test
    void ruleShapeIsEnforced() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RetentionRule(null, RuleSource.MANUAL, RetentionBasis.CREATED, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RetentionRule(null, RuleSource.MANUAL, RetentionBasis.CREATED, -1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RetentionRule(null, RuleSource.MANUAL, RetentionBasis.FIXED_DATE, 5, NOW, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RetentionRule(null, RuleSource.MANUAL, RetentionBasis.FIXED_DATE, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RetentionRule(null, RuleSource.POLICY, RetentionBasis.CREATED, 5, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
