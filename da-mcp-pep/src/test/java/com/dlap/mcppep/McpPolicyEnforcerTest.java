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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Verifies the enforcement core: cache pass-through, cache-hit dedup, and the
 * PDP-unavailable fail-closed / fail-open behavior driven by mode.
 */
class McpPolicyEnforcerTest {

    private PdpClient pdpClient;

    private McpAccessRequest req() {
        return new McpAccessRequest(
                "p1", List.of("DATA_ANALYST"), "PRACTITIONER", "tool",
                "bq://x", "asset-1", "read", Map.of());
    }

    private McpPepProperties props(String mode) {
        McpPepProperties p = new McpPepProperties();
        p.getMcppep().setMode(mode);
        p.getMcppep().getCache().setTtlSeconds(60L);
        return p;
    }

    private McpPolicyEnforcer enforcer(String mode) {
        McpPepProperties p = props(mode);
        return new McpPolicyEnforcer(pdpClient, new DecisionCache(p), p, new SimpleMeterRegistry());
    }

    @BeforeEach
    void setUp() {
        pdpClient = Mockito.mock(PdpClient.class);
    }

    @Test
    void allowPassesThrough() {
        when(pdpClient.evaluate(Mockito.any())).thenReturn(
                McpDecision.allow("allowed by policy", List.of(new Obligation("mask", List.of("PII")))));

        McpDecision d = enforcer("enforce").enforce(req());

        assertThat(d.allow()).isTrue();
        assertThat(d.fromCache()).isFalse();
        assertThat(d.obligations()).hasSize(1);
        assertThat(d.obligations().get(0).type()).isEqualTo("mask");
    }

    @Test
    void denyBlocks() {
        when(pdpClient.evaluate(Mockito.any())).thenReturn(McpDecision.deny("denied by default"));

        McpDecision d = enforcer("enforce").enforce(req());

        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo("denied by default");
    }

    @Test
    void secondIdenticalCallServedFromCache() {
        when(pdpClient.evaluate(Mockito.any())).thenReturn(McpDecision.allow("allowed", List.of()));
        McpPolicyEnforcer enforcer = enforcer("enforce");
        McpAccessRequest r = req();

        McpDecision first = enforcer.enforce(r);
        McpDecision second = enforcer.enforce(r);

        assertThat(first.fromCache()).isFalse();
        assertThat(second.fromCache()).isTrue();
        assertThat(second.allow()).isTrue();
        // PDP consulted exactly once - the second verdict came from cache.
        verify(pdpClient, times(1)).evaluate(Mockito.any());
    }

    @Test
    void pdpUnavailableEnforceModeDeniesFailClosed() {
        when(pdpClient.evaluate(Mockito.any())).thenThrow(new PdpUnavailableException("boom"));

        McpDecision d = enforcer("enforce").enforce(req());

        assertThat(d.allow()).isFalse();
        assertThat(d.failClosed()).isTrue();
    }

    @Test
    void pdpUnavailableObserveModeAllowsFailOpen() {
        when(pdpClient.evaluate(Mockito.any())).thenThrow(new PdpUnavailableException("boom"));

        McpDecision d = enforcer("observe").enforce(req());

        assertThat(d.allow()).isTrue();
        assertThat(d.failClosed()).isTrue();
        assertThat(d.reason()).contains("observe mode");
    }

    @Test
    void failOpenVerdictIsNotCached() {
        // First call: PDP down → observe allow (fail-open, not cached).
        // Second call: PDP recovered → real deny must be returned, not a cached allow.
        when(pdpClient.evaluate(Mockito.any()))
                .thenThrow(new PdpUnavailableException("down"))
                .thenReturn(McpDecision.deny("now denied"));

        McpPolicyEnforcer enforcer = enforcer("observe");
        McpAccessRequest r = req();

        McpDecision first = enforcer.enforce(r);
        McpDecision second = enforcer.enforce(r);

        assertThat(first.allow()).isTrue();
        assertThat(first.failClosed()).isTrue();
        assertThat(second.allow()).isFalse();
        assertThat(second.fromCache()).isFalse();
        verify(pdpClient, times(2)).evaluate(Mockito.any());
    }
}
