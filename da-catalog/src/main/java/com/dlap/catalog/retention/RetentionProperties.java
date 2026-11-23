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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.dlap.common.model.RetentionBasis;

/**
 * Settings of the retention sweep and its consumers ({@code dlap.catalog.retention.*}, CAT-03).
 * The defaults are in {@code da-catalog/src/main/resources/application.properties}; the keys read
 * directly by annotations ({@code enabled}, {@code cron}, {@code lock-at-most-for},
 * {@code publish.enabled}, {@code publish.interval}, the two listener switches) are documented
 * there.
 */
@ConfigurationProperties(prefix = "dlap.catalog.retention")
public class RetentionProperties {

    /** The longest period a rule may carry: 1000 years, far inside the range of an instant. */
    public static final int MAX_DAYS = 365_000;

    /** Page size bounds of the sweep. */
    static final int MAX_PAGE_SIZE = 5_000;

    private int pageSize = 200;

    private Duration evaluatedAtRefresh = Duration.ofDays(1);

    private List<LabelDefault> labelDefaults = new ArrayList<>();

    private String legalHoldLabel = "";

    private final Publish publish = new Publish();

    /**
     * Assets per page of the sweep.
     *
     * @return the page size
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * Sets the page size.
     *
     * @param pageSize assets per page, 1 to {@value #MAX_PAGE_SIZE}
     */
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * How old {@code retention_evaluated_at} may get before the sweep rewrites an asset whose
     * result did not change. Zero rewrites every asset on every run.
     *
     * @return the refresh age
     */
    public Duration getEvaluatedAtRefresh() {
        return evaluatedAtRefresh;
    }

    /**
     * Sets the refresh age.
     *
     * @param evaluatedAtRefresh a non-negative duration
     */
    public void setEvaluatedAtRefresh(Duration evaluatedAtRefresh) {
        this.evaluatedAtRefresh = evaluatedAtRefresh;
    }

    /**
     * The retention defaults per label.
     *
     * @return the defaults, never {@code null}
     */
    public List<LabelDefault> getLabelDefaults() {
        return labelDefaults;
    }

    /**
     * Sets the label defaults.
     *
     * @param labelDefaults the defaults; {@code null} means none
     */
    public void setLabelDefaults(List<LabelDefault> labelDefaults) {
        this.labelDefaults = labelDefaults == null ? new ArrayList<>() : new ArrayList<>(labelDefaults);
    }

    /**
     * The name of the label that counts as a legal hold, or blank when labels do not place holds.
     *
     * @return the label name
     */
    public String getLegalHoldLabel() {
        return legalHoldLabel;
    }

    /**
     * Sets the hold label.
     *
     * @param legalHoldLabel a label name; {@code null} or blank disables the mapping
     */
    public void setLegalHoldLabel(String legalHoldLabel) {
        this.legalHoldLabel = legalHoldLabel == null ? "" : legalHoldLabel.trim();
    }

    /**
     * Settings of the retention-due publisher.
     *
     * @return the nested settings
     */
    public Publish getPublish() {
        return publish;
    }

    /**
     * Fails fast on a value that would make the sweep misbehave.
     *
     * @throws IllegalStateException on an invalid setting
     */
    public void validate() {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalStateException("dlap.catalog.retention.page-size must be between 1 and "
                    + MAX_PAGE_SIZE + ", got " + pageSize);
        }
        if (evaluatedAtRefresh == null || evaluatedAtRefresh.isNegative()) {
            throw new IllegalStateException("dlap.catalog.retention.evaluated-at-refresh must not be negative");
        }
        Set<String> seen = new HashSet<>();
        for (LabelDefault d : labelDefaults) {
            d.validate();
            if (!seen.add(d.getLabel().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("dlap.catalog.retention.label-defaults names label '"
                        + d.getLabel() + "' twice");
            }
        }
        publish.validate();
    }

    /** One label's default retention: a period in days from a basis. */
    public static class LabelDefault {

        private String label = "";
        private Integer days;
        private RetentionBasis basis = RetentionBasis.LAST_ACCESSED;

        /**
         * The label name, matched without regard to case.
         *
         * @return the name
         */
        public String getLabel() {
            return label;
        }

        /**
         * Sets the label name.
         *
         * @param label the name
         */
        public void setLabel(String label) {
            this.label = label == null ? "" : label.trim();
        }

        /**
         * The period in days.
         *
         * @return the days
         */
        public Integer getDays() {
            return days;
        }

        /**
         * Sets the period.
         *
         * @param days days from the basis, 0 to {@value RetentionProperties#MAX_DAYS}
         */
        public void setDays(Integer days) {
            this.days = days;
        }

        /**
         * What the period counts from; {@code LAST_ACCESSED} by default.
         *
         * @return the basis
         */
        public RetentionBasis getBasis() {
            return basis;
        }

        /**
         * Sets the basis.
         *
         * @param basis a day-based basis
         */
        public void setBasis(RetentionBasis basis) {
            this.basis = basis;
        }

        void validate() {
            if (label.isBlank()) {
                throw new IllegalStateException("dlap.catalog.retention.label-defaults entry without a label");
            }
            if (days == null || days < 0 || days > MAX_DAYS) {
                throw new IllegalStateException("dlap.catalog.retention.label-defaults for '" + label
                        + "' needs days between 0 and " + MAX_DAYS);
            }
            if (basis == null || !basis.requiresDays()) {
                throw new IllegalStateException("dlap.catalog.retention.label-defaults for '" + label
                        + "' needs a day-based basis (CREATED, LAST_MODIFIED or LAST_ACCESSED)");
            }
        }

        /**
         * The rule this default gives an asset that carries the label.
         *
         * @return a {@code LABEL_DEFAULT} rule
         */
        RetentionRule toRule() {
            return RetentionRule.ofDays(RuleSource.LABEL_DEFAULT, basis, days);
        }
    }

    /** Settings of the publisher that drains the retention-due outbox. */
    public static class Publish {

        private int batchSize = 100;
        private Duration sendTimeout = Duration.ofSeconds(30);
        private Duration outboxRetention = Duration.ofDays(7);

        /**
         * Events per publisher run.
         *
         * @return the batch size
         */
        public int getBatchSize() {
            return batchSize;
        }

        /**
         * Sets the batch size.
         *
         * @param batchSize 1 to 10000
         */
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        /**
         * How long the publisher waits for the broker to acknowledge one event.
         *
         * @return the timeout
         */
        public Duration getSendTimeout() {
            return sendTimeout;
        }

        /**
         * Sets the send timeout.
         *
         * @param sendTimeout a positive duration
         */
        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }

        /**
         * How long published outbox rows are kept.
         *
         * @return the retention
         */
        public Duration getOutboxRetention() {
            return outboxRetention;
        }

        /**
         * Sets the outbox retention.
         *
         * @param outboxRetention a positive duration
         */
        public void setOutboxRetention(Duration outboxRetention) {
            this.outboxRetention = outboxRetention;
        }

        void validate() {
            if (batchSize < 1 || batchSize > 10_000) {
                throw new IllegalStateException("dlap.catalog.retention.publish.batch-size must be between 1 and 10000");
            }
            if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
                throw new IllegalStateException("dlap.catalog.retention.publish.send-timeout must be positive");
            }
            if (outboxRetention == null || outboxRetention.isZero() || outboxRetention.isNegative()) {
                throw new IllegalStateException("dlap.catalog.retention.publish.outbox-retention must be positive");
            }
        }
    }
}
