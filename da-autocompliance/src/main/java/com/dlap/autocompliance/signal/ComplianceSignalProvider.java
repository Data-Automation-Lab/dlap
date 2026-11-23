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

package com.dlap.autocompliance.signal;

import java.util.List;

/**
 * SPI for ingesting compliance posture from the customer's <b>existing</b>
 * compliance/GRC tool (ADR-0035). DLAP is the data-lifecycle orchestrator, not a
 * compliance scanner: rather than re-implement GDPR/HIPAA/SOC2/PCI evaluators, it
 * pulls {@link ComplianceSignal}s from whatever tool the customer already runs and
 * projects them onto asset labels, which the Policy DSL enforces.
 *
 * <p>Mirrors the platform's other cloud-neutral SPIs ({@code ILLMClient},
 * {@code ExternalCatalogIntegration}): the default binding is
 * {@link NoopComplianceSignalProvider} (zero integration); a deployment supplies a
 * concrete adapter as a Spring bean and enables sync via
 * {@code dlap.compliance.signal-sync.enabled=true}. Provider adapters that use a
 * cloud SDK belong in a {@code providers/<tool>} package, never in {@code core}.
 */
public interface ComplianceSignalProvider {

    /**
     * Fetch the current set of compliance signals to project onto asset labels.
     * Implementations should return an empty list (never null) when they have
     * nothing to report or are disabled.
     */
    List<ComplianceSignal> fetchSignals();

    /**
     * Whether this provider is configured to return real signals. The default
     * (no-op) provider returns {@code false}; the sync service skips work when the
     * active provider is not enabled.
     */
    default boolean isEnabled() {
        return false;
    }
}
