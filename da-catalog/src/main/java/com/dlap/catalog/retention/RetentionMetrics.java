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

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.dlap.common.model.RetentionStatus;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counters of the retention sweep and its consumers. Tag values come from closed sets (status
 * names, fixed outcome words), never from asset identifiers, so cardinality stays bounded.
 * Prometheus names are the meter names with dots as underscores plus {@code _total}.
 */
@Component
public class RetentionMetrics {

    /** Assets the sweep looked at, by outcome (changed, unchanged, error). {@code dlap_catalog_retention_sweep_assets_total}. */
    public static final String SWEEP_ASSETS = "dlap.catalog.retention.sweep.assets";
    /** Sweep runs, by outcome (completed, aborted). {@code dlap_catalog_retention_sweep_runs_total}. */
    public static final String SWEEP_RUNS = "dlap.catalog.retention.sweep.runs";
    /** Retention status changes, by previous and new status. {@code dlap_catalog_retention_transitions_total}. */
    public static final String TRANSITIONS = "dlap.catalog.retention.transitions";
    /** Outcome of each setRetention action. {@code dlap_catalog_retention_actions_total}. */
    public static final String ACTIONS = "dlap.catalog.retention.actions";
    /** Outcome of each binding status message. {@code dlap_catalog_retention_binding_status_total}. */
    public static final String BINDING_STATUS = "dlap.catalog.retention.binding.status";
    /** Retention-due events published or failed. {@code dlap_catalog_retention_published_total}. */
    public static final String PUBLISHED = "dlap.catalog.retention.published";
    /** Provider retention settings that could not be read. {@code dlap_catalog_retention_rule_invalid_total}. */
    public static final String RULE_INVALID = "dlap.catalog.retention.rule.invalid";
    /** Events waiting in the outbox. {@code dlap_catalog_retention_outbox_backlog}. */
    public static final String OUTBOX_BACKLOG = "dlap.catalog.retention.outbox.backlog";

    private final MeterRegistry registry;
    private final AtomicLong backlog = new AtomicLong();

    /**
     * Creates the metrics.
     *
     * @param registry the meter registry
     */
    public RetentionMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(OUTBOX_BACKLOG, backlog, AtomicLong::get)
                .description("Retention-due events waiting to be published").register(registry);
    }

    /**
     * Counts one asset of a sweep.
     *
     * @param outcome changed, unchanged or error
     */
    public void sweepAsset(String outcome) {
        registry.counter(SWEEP_ASSETS, "outcome", outcome).increment();
    }

    /**
     * Counts one sweep run.
     *
     * @param outcome completed or aborted
     */
    public void sweepRun(String outcome) {
        registry.counter(SWEEP_RUNS, "outcome", outcome).increment();
    }

    /**
     * Counts a status change.
     *
     * @param from the previous status
     * @param to   the new status
     */
    public void transition(RetentionStatus from, RetentionStatus to) {
        registry.counter(TRANSITIONS, "from", from.name(), "to", to.name()).increment();
    }

    /**
     * Counts the outcome of a setRetention action.
     *
     * @param outcome recorded, stale, ignored, dry_run, approval_pending, unmatched or rejected
     */
    public void action(String outcome) {
        registry.counter(ACTIONS, "outcome", outcome).increment();
    }

    /**
     * Counts the outcome of a binding status message.
     *
     * @param outcome stored, stale, removed or ignored
     */
    public void bindingStatus(String outcome) {
        registry.counter(BINDING_STATUS, "outcome", outcome).increment();
    }

    /**
     * Counts retention-due events.
     *
     * @param outcome published or failed
     * @param count   how many
     */
    public void published(String outcome, int count) {
        registry.counter(PUBLISHED, "outcome", outcome).increment(count);
    }

    /**
     * Counts a provider retention setting that was present but unreadable.
     */
    public void invalidProviderRule() {
        registry.counter(RULE_INVALID, "source", RuleSource.PROVIDER.name()).increment();
    }

    /**
     * Records the current outbox backlog.
     *
     * @param events events waiting
     */
    public void outboxBacklog(long events) {
        backlog.set(events);
    }
}
