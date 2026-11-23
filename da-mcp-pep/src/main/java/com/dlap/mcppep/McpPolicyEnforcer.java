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

package com.dlap.mcppep;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The enforcement core: turns an {@link McpAccessRequest} into a final
 * {@link McpDecision}, applying the local cache and the PDP-unavailable policy.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Cache hit → return the cached verdict flagged {@code fromCache=true}
 *       (the PDP is not called).</li>
 *   <li>Cache miss → call the {@link PdpClient}; cache and return the verdict.</li>
 *   <li>{@link PdpUnavailableException} → apply the mode:
 *     <ul>
 *       <li>{@code enforce} → {@link McpDecision#deny} with {@code failClosed=true}.</li>
 *       <li>{@code observe} → {@link McpDecision#allow} with {@code failClosed=true}
 *           ("PDP unavailable, observe mode").</li>
 *     </ul>
 *     Fail-open/-closed verdicts are NOT cached, so recovery is immediate once
 *     the PDP returns.</li>
 * </ol>
 *
 * <p>This method never throws - a PEP must always yield a verdict.
 *
 * <p>On an ALLOW carrying obligations, the transport layer that returns the
 * data must honor them with
 * {@link com.dlap.mcppep.obligation.ObligationApplier} and fail-closed if it
 * cannot (docs/specs/enforcement-contract.md §3). That applier is standalone;
 * this enforcer's decision path is unchanged.
 */
@Component
public class McpPolicyEnforcer {

    private static final Logger log = LoggerFactory.getLogger(McpPolicyEnforcer.class);

    /** Counter incremented once per enforced verdict, tagged by outcome. */
    private static final String DECISIONS_COUNTER = "dlap.mcppep.decisions.total";
    /** Timer over the full enforce() path (cache lookup + optional PDP call). */
    private static final String ENFORCE_TIMER = "dlap.mcppep.enforce.duration";

    private final PdpClient pdpClient;
    private final DecisionCache cache;
    private final McpPepProperties properties;
    private final MeterRegistry meterRegistry;
    private final Timer enforceTimer;

    public McpPolicyEnforcer(PdpClient pdpClient, DecisionCache cache, McpPepProperties properties,
                             MeterRegistry meterRegistry) {
        this.pdpClient = pdpClient;
        this.cache = cache;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.enforceTimer = Timer.builder(ENFORCE_TIMER)
                .description("MCP PEP enforcement duration (cache + PDP)")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * @param req the MCP access request
     * @return the enforced verdict (never null, never thrown)
     */
    public McpDecision enforce(McpAccessRequest req) {
        Timer.Sample sample = Timer.start(meterRegistry);
        McpDecision decision = cache.get(req)
                .map(McpDecision::asCached)
                .orElseGet(() -> evaluateAndCache(req));
        sample.stop(enforceTimer);
        meterRegistry.counter(DECISIONS_COUNTER,
                "allow", Boolean.toString(decision.allow()),
                "failClosed", Boolean.toString(decision.failClosed()))
                .increment();
        return decision;
    }

    private McpDecision evaluateAndCache(McpAccessRequest req) {
        try {
            McpDecision decision = pdpClient.evaluate(req);
            cache.put(req, decision);
            return decision;
        } catch (PdpUnavailableException ex) {
            return failOnPdpUnavailable(req, ex);
        }
    }

    private McpDecision failOnPdpUnavailable(McpAccessRequest req, PdpUnavailableException ex) {
        if (properties.getMcppep().isObserveMode()) {
            log.warn("PDP unavailable for principal={} action={} resource={}; observe mode → ALLOW (fail-open)",
                    req.principalId(), req.action(), resourceLabel(req), ex);
            return new McpDecision(true, "PDP unavailable, observe mode", List.of(), false, true);
        }
        log.warn("PDP unavailable for principal={} action={} resource={}; enforce mode → DENY (fail-closed)",
                req.principalId(), req.action(), resourceLabel(req), ex);
        return new McpDecision(false, "PDP unavailable, enforce mode", List.of(), false, true);
    }

    private static String resourceLabel(McpAccessRequest req) {
        return (req.assetId() != null && !req.assetId().isBlank()) ? req.assetId() : req.resourceUri();
    }
}
