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

package com.dlap.autodelete.retire;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the delete executor (RET-02), bound from {@code dlap.autodelete.*}.
 *
 * <p>Floors are checked at start-up: a value below a floor stops the service with the property's
 * name. There is no switch for the approval, legal-hold or read-coverage rules; they always apply.
 *
 * @param dryRunCloudDelete          true (the default, decision D23): every rule is checked and the
 *                                   native rule or engine delete is planned and recorded, but
 *                                   nothing is applied in the cloud
 * @param softDeleteWindowDays       window when a policy or task names none
 * @param windowFloorDays            the shortest window any delete gets, at least 1
 * @param hardDeleteRequiredApprovals approvals the HARD_DELETE request at the end of the window
 *                                   asks for, 2 to 10 (approvals.md: never fewer than 2)
 * @param maxTaskAssets              most assets one task API submission may name
 * @param sweep                      the sweeper schedule
 * @param proof                      the log-proof settings
 * @param nativeRule                 native-rule settings
 * @param ruleInForce                how long a bucket, prefix or container rule stays in force (RET-06)
 */
@ConfigurationProperties(prefix = "dlap.autodelete")
public record RetireProperties(
        @DefaultValue("true") boolean dryRunCloudDelete,
        @DefaultValue("30") int softDeleteWindowDays,
        @DefaultValue("7") int windowFloorDays,
        @DefaultValue("2") int hardDeleteRequiredApprovals,
        @DefaultValue("100") int maxTaskAssets,
        @DefaultValue Sweep sweep,
        @DefaultValue Proof proof,
        @DefaultValue NativeRule nativeRule,
        @DefaultValue RuleInForce ruleInForce) {

    /** The hard-delete approval floor of approvals.md section 3. */
    public static final int HARD_DELETE_APPROVAL_FLOOR = 2;

    /** The approval ceiling of approvals.md section 3. */
    public static final int APPROVAL_CEILING = 10;

    /**
     * Rejects values below their floors.
     */
    public RetireProperties {
        if (windowFloorDays < 1) {
            throw new IllegalArgumentException("dlap.autodelete.window-floor-days must be at least 1, was "
                    + windowFloorDays);
        }
        if (softDeleteWindowDays < windowFloorDays) {
            throw new IllegalArgumentException("dlap.autodelete.soft-delete-window-days (" + softDeleteWindowDays
                    + ") must not be below dlap.autodelete.window-floor-days (" + windowFloorDays + ")");
        }
        if (hardDeleteRequiredApprovals < HARD_DELETE_APPROVAL_FLOOR
                || hardDeleteRequiredApprovals > APPROVAL_CEILING) {
            throw new IllegalArgumentException("dlap.autodelete.hard-delete-required-approvals must be 2 to 10, was "
                    + hardDeleteRequiredApprovals);
        }
        if (maxTaskAssets < 1 || maxTaskAssets > 1000) {
            throw new IllegalArgumentException("dlap.autodelete.max-task-assets must be 1 to 1000, was "
                    + maxTaskAssets);
        }
        sweep = sweep == null ? Sweep.defaults() : sweep;
        proof = proof == null ? Proof.defaults() : proof;
        nativeRule = nativeRule == null ? NativeRule.defaults() : nativeRule;
        ruleInForce = ruleInForce == null ? RuleInForce.defaults() : ruleInForce;
    }

    /**
     * Defaults, for tests and for code that builds the settings by hand.
     *
     * @return the defaults
     */
    public static RetireProperties defaults() {
        return new RetireProperties(true, 30, 7, 2, 100, Sweep.defaults(), Proof.defaults(), NativeRule.defaults(),
                RuleInForce.defaults());
    }

    /**
     * The same settings with another dry-run switch.
     *
     * @param dryRun the switch
     * @return the copy
     */
    public RetireProperties withDryRun(boolean dryRun) {
        return new RetireProperties(dryRun, softDeleteWindowDays, windowFloorDays, hardDeleteRequiredApprovals,
                maxTaskAssets, sweep, proof, nativeRule, ruleInForce);
    }

    /**
     * The same settings with other rule-in-force settings.
     *
     * @param settings the rule-in-force settings
     * @return the copy
     */
    public RetireProperties withRuleInForce(RuleInForce settings) {
        return new RetireProperties(dryRunCloudDelete, softDeleteWindowDays, windowFloorDays,
                hardDeleteRequiredApprovals, maxTaskAssets, sweep, proof, nativeRule, settings);
    }

    /**
     * The sweeper.
     *
     * @param batchSize            most rows one sweep handles
     * @param approvalPollInterval how long before a pending approval is read again
     * @param retryBackoff         how long before a row whose remote call failed is tried again
     * @param lockAtMostFor        the ShedLock lease (the job's own bound is in the annotation)
     */
    public record Sweep(
            @DefaultValue("50") int batchSize,
            @DefaultValue("PT5M") Duration approvalPollInterval,
            @DefaultValue("PT15M") Duration retryBackoff,
            @DefaultValue("PT10M") Duration lockAtMostFor) {

        /** Rejects values Kafka or the scheduler could not use. */
        public Sweep {
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("dlap.autodelete.sweep.batch-size must be 1 to 1000, was " + batchSize);
            }
            requirePositive("dlap.autodelete.sweep.approval-poll-interval", approvalPollInterval);
            requirePositive("dlap.autodelete.sweep.retry-backoff", retryBackoff);
            requirePositive("dlap.autodelete.sweep.lock-at-most-for", lockAtMostFor);
        }

        static Sweep defaults() {
            return new Sweep(50, Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(10));
        }
    }

    /**
     * The log proof.
     *
     * @param overdueAfter how long after applying a rule the proof is expected (S3, GCS and Azure run
     *                     lifecycle about once a day; BigQuery expirations can take up to a day)
     * @param clockSkew    how much earlier than the apply time a proof event may be stamped
     */
    public record Proof(
            @DefaultValue("P3D") Duration overdueAfter,
            @DefaultValue("PT10M") Duration clockSkew) {

        /** Rejects non-positive durations. */
        public Proof {
            requirePositive("dlap.autodelete.proof.overdue-after", overdueAfter);
            if (clockSkew == null || clockSkew.isNegative()) {
                throw new IllegalArgumentException("dlap.autodelete.proof.clock-skew must not be negative");
            }
        }

        static Proof defaults() {
            return new Proof(Duration.ofDays(3), Duration.ofMinutes(10));
        }
    }

    /**
     * Native rules.
     *
     * @param bigQueryExpirationDelay how far ahead of the apply time a BigQuery table expiration is set
     * @param azureStorageAccountIds  ARM ids of the storage accounts whose management policy DLAP may
     *                                change ({@code /subscriptions/{s}/resourceGroups/{rg}/providers/Microsoft.Storage/storageAccounts/{name}});
     *                                a container of an account not listed has no native rule
     * @param containerExpirationDays the age in days after which a bucket, prefix or container rule
     *                                deletes an object (S3 {@code Expiration.Days}, GCS {@code age}, Azure
     *                                {@code daysAfterModificationGreaterThan}); at least 1 (RET-06, Q-677)
     */
    public record NativeRule(
            @DefaultValue("PT1H") Duration bigQueryExpirationDelay,
            @DefaultValue List<String> azureStorageAccountIds,
            @DefaultValue("1") int containerExpirationDays) {

        /** The shortest age a container rule may use: a zero-day rule is refused (Q-677). */
        public static final int CONTAINER_EXPIRATION_FLOOR_DAYS = 1;

        /** Rejects a non-positive delay or age and copies the list. */
        public NativeRule {
            requirePositive("dlap.autodelete.native-rule.big-query-expiration-delay", bigQueryExpirationDelay);
            azureStorageAccountIds = azureStorageAccountIds == null ? List.of() : List.copyOf(azureStorageAccountIds);
            if (containerExpirationDays < CONTAINER_EXPIRATION_FLOOR_DAYS || containerExpirationDays > 3650) {
                throw new IllegalArgumentException("dlap.autodelete.native-rule.container-expiration-days must be 1 "
                        + "to 3650, was " + containerExpirationDays);
            }
        }

        static NativeRule defaults() {
            return new NativeRule(Duration.ofHours(1), List.of(), 1);
        }
    }

    /**
     * How long a bucket, prefix or container rule stays in force (RET-06, Q-677). Such a rule keeps
     * deleting matching objects, new ones included, until it is removed. A record that is
     * {@code RULE_IN_FORCE} for longer than {@code maxAge} since its rule was applied has the rule
     * removed by the executor and ends {@code RULE_ENDED}.
     *
     * @param maxAge        how long after the apply the executor removes its own rule (default 30 days)
     * @param retryAfter    how long before a removal the platform refused or did not answer is tried again
     * @param batchSize     most records one run ends
     * @param interval      how often the job runs (read by the schedule annotation)
     * @param lockAtMostFor the ShedLock lease of the job (read by the schedule annotation)
     */
    public record RuleInForce(
            @DefaultValue("P30D") Duration maxAge,
            @DefaultValue("PT6H") Duration retryAfter,
            @DefaultValue("50") int batchSize,
            @DefaultValue("PT1H") Duration interval,
            @DefaultValue("PT10M") Duration lockAtMostFor) {

        /** Rejects non-positive durations and a batch outside 1 to 1000. */
        public RuleInForce {
            requirePositive("dlap.autodelete.rule-in-force.max-age", maxAge);
            requirePositive("dlap.autodelete.rule-in-force.retry-after", retryAfter);
            requirePositive("dlap.autodelete.rule-in-force.interval", interval);
            requirePositive("dlap.autodelete.rule-in-force.lock-at-most-for", lockAtMostFor);
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("dlap.autodelete.rule-in-force.batch-size must be 1 to 1000, was "
                        + batchSize);
            }
        }

        static RuleInForce defaults() {
            return new RuleInForce(Duration.ofDays(30), Duration.ofHours(6), 50, Duration.ofHours(1),
                    Duration.ofMinutes(10));
        }
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration, was " + value);
        }
    }
}
