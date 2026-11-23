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

package com.dlap.reporting.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure drift-correlation core (ADR-0040). No Kafka, no DB:
 * the join is a deterministic function of the input lists and window.
 */
class DriftDetectionServiceTest {

    private static final long WINDOW = 300L;
    private static final Instant T0 = Instant.parse("2026-07-06T12:00:00Z");

    private final DriftDetectionService service = new DriftDetectionService(WINDOW);

    @Test
    void unenforcedRead_isDetected_whenNoDecisionExists() {
        ReadRecord read = new ReadRecord("evt-1", "svc-agent", "asset-1", T0);

        List<DriftFinding> findings = service.detect(List.of(), List.of(read), WINDOW);

        assertThat(findings).hasSize(1);
        DriftFinding f = findings.get(0);
        assertThat(f.type()).isEqualTo(DriftType.UNENFORCED_READ);
        assertThat(f.assetId()).isEqualTo("asset-1");
        assertThat(f.subjectId()).isEqualTo("svc-agent");
        assertThat(f.decisionId()).isNull();
        assertThat(f.readAt()).isEqualTo(T0);
    }

    @Test
    void allowThenRead_withinWindow_producesNoFinding() {
        DecisionRecord allow = new DecisionRecord(
                "dec-allow", "svc-agent", "asset-1", true, T0.minusSeconds(60));
        ReadRecord read = new ReadRecord("evt-1", "svc-agent", "asset-1", T0);

        List<DriftFinding> findings = service.detect(List.of(allow), List.of(read), WINDOW);

        assertThat(findings).isEmpty();
    }

    @Test
    void denyThenRead_withinWindow_isEnforcementBypass() {
        DecisionRecord deny = new DecisionRecord(
                "dec-deny", "svc-agent", "asset-1", false, T0.minusSeconds(30));
        ReadRecord read = new ReadRecord("evt-1", "svc-agent", "asset-1", T0);

        List<DriftFinding> findings = service.detect(List.of(deny), List.of(read), WINDOW);

        assertThat(findings).hasSize(1);
        DriftFinding f = findings.get(0);
        assertThat(f.type()).isEqualTo(DriftType.ENFORCEMENT_BYPASS);
        assertThat(f.decisionId()).isEqualTo("dec-deny");
        assertThat(f.assetId()).isEqualTo("asset-1");
        assertThat(f.subjectId()).isEqualTo("svc-agent");
    }

    @Test
    void allowOutsideWindow_isTreatedAsUnenforced() {
        // ALLOW decided well before the window opens -> does not authorize the read.
        DecisionRecord staleAllow = new DecisionRecord(
                "dec-allow", "svc-agent", "asset-1", true, T0.minusSeconds(WINDOW + 60));
        ReadRecord read = new ReadRecord("evt-1", "svc-agent", "asset-1", T0);

        List<DriftFinding> findings = service.detect(List.of(staleAllow), List.of(read), WINDOW);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(DriftType.UNENFORCED_READ);
    }

    @Test
    void denyOutsideWindow_isNotABypass_fallsToUnenforced() {
        DecisionRecord staleDeny = new DecisionRecord(
                "dec-deny", "svc-agent", "asset-1", false, T0.minusSeconds(WINDOW + 1));
        ReadRecord read = new ReadRecord("evt-1", "svc-agent", "asset-1", T0);

        List<DriftFinding> findings = service.detect(List.of(staleDeny), List.of(read), WINDOW);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(DriftType.UNENFORCED_READ);
        assertThat(findings.get(0).decisionId()).isNull();
    }

    @Test
    void decisionAfterRead_doesNotApply_readIsUnenforced() {
        // A decision made AFTER the read cannot have authorized it.
        DecisionRecord laterAllow = new DecisionRecord(
                "dec-allow", "svc-agent", "asset-1", true, T0.plusSeconds(10));
        ReadRecord read = new ReadRecord("evt-1", "svc-agent", "asset-1", T0);

        List<DriftFinding> findings = service.detect(List.of(laterAllow), List.of(read), WINDOW);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(DriftType.UNENFORCED_READ);
    }

    @Test
    void denyOverridesAllow_evenWhenBothApply() {
        DecisionRecord allow = new DecisionRecord(
                "dec-allow", "svc-agent", "asset-1", true, T0.minusSeconds(120));
        DecisionRecord deny = new DecisionRecord(
                "dec-deny", "svc-agent", "asset-1", false, T0.minusSeconds(30));
        ReadRecord read = new ReadRecord("evt-1", "svc-agent", "asset-1", T0);

        List<DriftFinding> findings = service.detect(List.of(allow, deny), List.of(read), WINDOW);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(DriftType.ENFORCEMENT_BYPASS);
        assertThat(findings.get(0).decisionId()).isEqualTo("dec-deny");
    }

    @Test
    void decisionForDifferentSubject_doesNotAuthorize() {
        DecisionRecord otherSubjectAllow = new DecisionRecord(
                "dec-allow", "other-agent", "asset-1", true, T0.minusSeconds(30));
        ReadRecord read = new ReadRecord("evt-1", "svc-agent", "asset-1", T0);

        List<DriftFinding> findings = service.detect(
                List.of(otherSubjectAllow), List.of(read), WINDOW);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(DriftType.UNENFORCED_READ);
    }

    @Test
    void output_isDeterministicallyOrdered() {
        ReadRecord readB = new ReadRecord("evt-b", "svc-agent", "asset-b", T0.plusSeconds(5));
        ReadRecord readA = new ReadRecord("evt-a", "svc-agent", "asset-a", T0);

        List<DriftFinding> findings = service.detect(List.of(), List.of(readB, readA), WINDOW);

        assertThat(findings).hasSize(2);
        // Ordered by readAt ascending: readA (T0) before readB (T0+5).
        assertThat(findings.get(0).assetId()).isEqualTo("asset-a");
        assertThat(findings.get(1).assetId()).isEqualTo("asset-b");
    }

    @Test
    void nullInputs_yieldEmpty() {
        assertThat(service.detect(null, null, WINDOW)).isEmpty();
    }
}
