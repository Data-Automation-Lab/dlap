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

package com.dlap.policyengine.access;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps {@link AccessDecisionService}'s in-memory AccessPolicy cache fresh
 * (ADR-0040 - "the PDP must answer from memory").
 *
 * <p>{@link com.dlap.policyengine.dsl.LifecyclePolicyConsumer} re-reads ENABLED
 * policies from the repository on each fire; the PDP hot path (p99 ≤10ms) cannot
 * afford a DB round-trip per verdict, so it caches instead and refreshes here:
 * <ul>
 *   <li>once at {@link ApplicationReadyEvent} (warm the cache before serving), and</li>
 *   <li>periodically via {@link Scheduled} (bounded staleness; interval
 *       {@code dlap.pdp.refresh-interval-ms}, default 30s).</li>
 * </ul>
 *
 * <p><b>Immediate propagation.</b> {@code PolicyService} publishes a
 * {@link PolicyChangedEvent} on create/update/status-change/delete/draft-publish;
 * {@link #onPolicyChanged()} refreshes the cache after the transaction commits
 * so an authored AccessPolicy enforces at once. The scheduled refresh remains as
 * a backstop for bounded staleness.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccessPolicyRefreshListener {

    private final AccessDecisionService accessDecisionService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Warming AccessPolicy cache on startup");
        accessDecisionService.refresh();
    }

    @Scheduled(fixedDelayString = "${dlap.pdp.refresh-interval-ms:30000}")
    public void onSchedule() {
        accessDecisionService.refresh();
    }

    /**
     * Refresh immediately after a policy change commits. {@code AFTER_COMMIT} so
     * the reloaded set reflects the committed row (not an uncommitted one);
     * {@code fallbackExecution=true} so it still runs if a caller published the
     * event outside a transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPolicyChanged(PolicyChangedEvent event) {
        log.debug("Policy change ({}) - refreshing AccessPolicy cache", event.reason());
        accessDecisionService.refresh();
    }
}
