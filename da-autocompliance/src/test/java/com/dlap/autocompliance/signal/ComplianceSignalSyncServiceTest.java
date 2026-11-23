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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.dlap.autocompliance.signal.ComplianceSignal.ComplianceStatus;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ComplianceSignalSyncService} composition (plain JUnit; hand-rolled
 * provider + sink fakes - no Mockito).
 */
class ComplianceSignalSyncServiceTest {

    private final ComplianceLabelMapper mapper = new ComplianceLabelMapper();

    @Test
    void sync_appliesLabelsForActionableSignalsOnly() {
        List<ComplianceSignal> signals = List.of(
                new ComplianceSignal("uri1", "GDPR", "art-17", ComplianceStatus.VIOLATION, "retention"),
                new ComplianceSignal("uri2", "HIPAA", "164.312", ComplianceStatus.UNKNOWN, null), // no label
                new ComplianceSignal(null, "SOC2", "cc6.1", ComplianceStatus.VIOLATION, null));   // no assetUri

        ComplianceSignalProvider provider = new ComplianceSignalProvider() {
            @Override public List<ComplianceSignal> fetchSignals() { return signals; }
            @Override public boolean isEnabled() { return true; }
        };
        List<String> applied = new ArrayList<>();
        ComplianceLabelSink sink = (uri, label, detail) -> applied.add(uri + "|" + label);

        int count = new ComplianceSignalSyncService(provider, mapper, sink).sync();

        assertThat(count).isEqualTo(1);
        assertThat(applied).containsExactly("uri1|compliance:gdpr:violation");
    }

    @Test
    void sync_whenProviderDisabled_appliesNothing() {
        List<String> applied = new ArrayList<>();
        ComplianceSignalSyncService svc = new ComplianceSignalSyncService(
                new NoopComplianceSignalProvider(), mapper, (uri, label, detail) -> applied.add(label));

        assertThat(svc.sync()).isZero();
        assertThat(applied).isEmpty();
    }
}
