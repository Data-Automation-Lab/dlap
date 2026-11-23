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

package com.dlap.policyengine.approval;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The approval service's singleton job: expire overdue requests, then relay the outbox.
 *
 * <p>Every {@code dlap.policyengine.approvals.maintenance.tick} (default 5 s) under the ShedLock lock
 * {@value #LOCK_NAME} (JDBC, database time, plan rule R7). {@code lockAtMostFor} defaults to 10
 * minutes, more than twice the worst tick: one expiry pass plus 50 sends of at most 5 s each.
 * Gated by {@code dlap.policyengine.approvals.maintenance.enabled} (default true).
 */
@Component
@ConditionalOnProperty(name = "dlap.policyengine.approvals.maintenance.enabled", havingValue = "true",
        matchIfMissing = true)
public class ApprovalMaintenance {

    /** ShedLock name of the job. */
    public static final String LOCK_NAME = "approval-maintenance";

    private static final Logger log = LoggerFactory.getLogger(ApprovalMaintenance.class);

    private final ApprovalService service;
    private final ApprovalOutboxRelay relay;
    private final MeterRegistry meters;

    /**
     * Creates the job.
     *
     * @param service the approval service
     * @param relay   the outbox relay
     * @param meters  metrics
     */
    public ApprovalMaintenance(ApprovalService service, ApprovalOutboxRelay relay, MeterRegistry meters) {
        this.service = service;
        this.relay = relay;
        this.meters = meters;
    }

    /**
     * One tick. Never throws.
     */
    @Scheduled(fixedDelayString = "${dlap.policyengine.approvals.maintenance.tick:PT5S}",
            initialDelayString = "${dlap.policyengine.approvals.maintenance.initial-delay:PT20S}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtMostFor = "${dlap.policyengine.approvals.maintenance.lock-at-most-for:PT10M}",
            lockAtLeastFor = "${dlap.policyengine.approvals.maintenance.lock-at-least-for:PT1S}")
    public void tick() {
        try {
            service.expireOverdue();
        } catch (RuntimeException e) {
            log.error("Approval expiry failed: {}", e.getMessage(), e);
            meters.counter("policyengine.approvals.maintenance", "result", "expiry-error").increment();
        }
        try {
            relay.relay();
        } catch (RuntimeException e) {
            log.error("Approval outbox relay failed: {}", e.getMessage(), e);
            meters.counter("policyengine.approvals.maintenance", "result", "relay-error").increment();
        }
    }
}
