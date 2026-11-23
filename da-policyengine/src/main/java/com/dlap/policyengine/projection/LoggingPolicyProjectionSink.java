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

package com.dlap.policyengine.projection;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link PolicyProjectionSink}: observe-only. Instead of touching any
 * platform, it logs exactly which grant/revoke intents it <i>would</i> apply and
 * retains the last-projected set (queryable via {@link #recordedIntents()}) so a
 * deployment can turn the feature on and watch the projections before wiring a
 * real write path - the same "observe before enforce" default as ADR-0035's
 * {@code LoggingComplianceLabelSink}.
 *
 * <p>Zero-config safe: because this is the registered default and it never
 * mutates external state, enabling {@code dlap.policy.projection.enabled} alone
 * causes no platform change.
 */
public class LoggingPolicyProjectionSink implements PolicyProjectionSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingPolicyProjectionSink.class);

    /** Retained last-projected intents, for tests and observability. */
    private final List<GrantIntent> recorded = new ArrayList<>();

    @Override
    public void project(CompiledAccessPolicy compiled) {
        log("PROJECT", compiled);
    }

    @Override
    public void revoke(CompiledAccessPolicy compiled) {
        // On explicit reverse, invert each intent so the log shows the undo.
        List<GrantIntent> inverted = new ArrayList<>();
        for (GrantIntent i : compiled.intents()) {
            inverted.add(new GrantIntent(i.principal(), i.privilege(), i.securable(), !i.grant()));
        }
        log("REVOKE", new CompiledAccessPolicy(
                compiled.policyId(), compiled.policyName(), compiled.effect(),
                inverted, compiled.labelSelector()));
    }

    private void log(String phase, CompiledAccessPolicy compiled) {
        recorded.clear();
        recorded.addAll(compiled.intents());
        if (compiled.isEmpty()) {
            log.info("[policy-projection] {} {} ({}): no securables resolved; nothing to project",
                    phase, compiled.policyId(), compiled.labelSelector());
            return;
        }
        for (GrantIntent i : compiled.intents()) {
            log.info("[policy-projection] {} would {} {} ON {} TO {} (policy={})",
                    phase, i.grant() ? "GRANT" : "REVOKE", i.privilege(), i.securable(),
                    i.principal(), compiled.policyId());
        }
    }

    /** @return the intents recorded by the last {@link #project}/{@link #revoke} call. */
    public List<GrantIntent> recordedIntents() {
        return List.copyOf(recorded);
    }
}
