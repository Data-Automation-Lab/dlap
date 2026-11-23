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

package com.dlap.policyengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.common.ResourceNotFoundException;
import com.dlap.policyengine.dsl.PolicyDslEvaluator;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dto.PolicyEvaluationOutputDTO;
import com.dlap.policyengine.dto.PolicyEvaluationRequestDTO;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyEvaluation;
import com.dlap.policyengine.model.PolicyEvaluationStatus;
import com.dlap.policyengine.model.PolicyRule;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyEvaluationRepository;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SEC-04: on-demand evaluation runs only the policy's dlap/v1 DSL document. Retired MVEL
 * rules are never compiled or run, and a policy without a document fires nothing.
 */
@ExtendWith(MockitoExtension.class)
class PolicyEvaluationServiceTest {

    private static final String S3_DOC = ""
            + "apiVersion: dlap/v1\n"
            + "kind: Policy\n"
            + "metadata: {id: s3-notify, name: s3-notify}\n"
            + "spec:\n"
            + "  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n"
            + "  match: {assetType: S3_BUCKET}\n"
            + "  action: {type: alert.email}\n";

    @Mock private PolicyRepository policyRepository;
    @Mock private PolicyEvaluationRepository policyEvaluationRepository;
    @Mock private PolicyMapper policyMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private ApprovalGate approvalGate;
    @Mock private ResilientCatalogAssetClient catalogAssetClient;
    @Spy private PolicyDslParser dslParser = new PolicyDslParser();
    /** The business clock every test pins (plan rule R6). */
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    @Spy private PolicyDslEvaluator dslEvaluator = new PolicyDslEvaluator(clock);

    private PolicyEvaluationService service;

    @BeforeEach
    void createService() {
        service = new PolicyEvaluationService(policyRepository, policyEvaluationRepository, policyMapper,
                objectMapper, approvalGate, catalogAssetClient, dslParser, dslEvaluator, clock);
        lenient().when(approvalGate.submit(any(), any())).thenReturn(ApprovalGate.Route.PUBLISHED);
    }

    private Policy policy(UUID id, PolicyStatus status, String document) {
        Policy p = new Policy();
        p.setId(id);
        p.setName("p1");
        p.setStatus(status);
        p.setPolicyDocument(document);
        p.setActions(Map.of("notify", Map.of("channel", "email")));
        return p;
    }

    private void stubSaveAndMap() {
        when(policyEvaluationRepository.save(any(PolicyEvaluation.class))).thenAnswer(inv -> {
            PolicyEvaluation e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
        when(policyMapper.toPolicyEvaluationOutputDTO(any(PolicyEvaluation.class), eq("p1"))).thenAnswer(inv -> {
            PolicyEvaluation e = inv.getArgument(0);
            PolicyEvaluationOutputDTO dto = new PolicyEvaluationOutputDTO();
            dto.setPolicyId(e.getPolicyId());
            dto.setPolicyName("p1");
            dto.setStatus(e.getStatus());
            dto.setTargetAssetId(e.getTargetAssetId());
            dto.setTriggeredActions(e.getTriggeredActions());
            return dto;
        });
    }

    private static PolicyEvaluationRequestDTO request(Map<String, Object> snapshot) {
        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId("asset123");
        req.setAssetSnapshotFromEvent(snapshot);
        return req;
    }

    private PolicyEvaluation firstSaved() {
        ArgumentCaptor<PolicyEvaluation> saved = ArgumentCaptor.forClass(PolicyEvaluation.class);
        verify(policyEvaluationRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().get(0);
    }

    @Test
    void evaluatePolicyForAsset_policyNotFound_throws() {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.evaluatePolicyForAsset(policyId, request(null), UUID.randomUUID()));
    }

    @Test
    void evaluatePolicyForAsset_disabledPolicy_recordsNotApplicableAndDoesNotSendActions() {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy(policyId, PolicyStatus.DISABLED, S3_DOC)));
        stubSaveAndMap();

        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId, request(null), UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.NOT_APPLICABLE);
        verify(approvalGate, never()).submit(any(), any());
    }

    @Test
    void evaluatePolicyForAsset_dslMatch_passesAndTriggersPolicyActionEvents() {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy(policyId, PolicyStatus.ENABLED, S3_DOC)));
        stubSaveAndMap();

        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId,
                request(Map.of("name", "Sensitive S3 Bucket", "assetType", "S3_BUCKET", "region", "us-east-1")),
                UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.PASS);
        ArgumentCaptor<PolicyActionEvent> eventCap = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(approvalGate).submit(eventCap.capture(), any());
        assertThat(eventCap.getValue().policyId()).isEqualTo(policyId.toString());
        assertThat(eventCap.getValue().actionType()).isEqualTo("notify");

        Map<String, Object> details = firstSaved().getEvaluationDetails();
        assertThat(details).containsEntry("engine", "dlap/v1").containsEntry("matched", true);
        assertThat(details).doesNotContainKeys("rulesEvaluated", "factsSnapshot");
    }

    @Test
    void evaluatePolicyForAsset_dslNoMatch_failsAndDoesNotSendActions() {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy(policyId, PolicyStatus.ENABLED, S3_DOC)));
        stubSaveAndMap();

        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId,
                request(Map.of("name", "bucket", "assetType", "GCS_BUCKET")), UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.FAIL);
        verify(approvalGate, never()).submit(any(), any());
        assertThat(firstSaved().getEvaluationDetails()).containsEntry("matched", false);
    }

    /**
     * The document shape the lifecycle e2e harnesses send once they stop sending rules[]
     * (patch in the SEC-04 integrator notes): a provider match and a schedule trigger, with
     * the legacy actions map carrying ARCHIVE. The catalog asset of that provider passes and
     * the ARCHIVE action is published, as the harness gates expect.
     */
    @Test
    void evaluatePolicyForAsset_harnessDocument_passesForTheProviderAndPublishesTheAction() {
        UUID policyId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        String harnessDoc = "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: e2e-gcp-archive, name: e2e-gcp-archive}\n"
                + "spec:\n  triggers: [{type: schedule, cron: '0 0 0 1 1 ?'}]\n  match: {cloudProvider: GCP}\n"
                + "  action: {type: archive}\n";
        Policy p = policy(policyId, PolicyStatus.ENABLED, harnessDoc);
        p.setActions(Map.of("ARCHIVE", Map.of("tier", "COLD")));
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(p));
        CatalogAssetDTO asset = new CatalogAssetDTO();
        asset.setAssetId(assetId);
        asset.setCloudProvider("GCP");
        asset.setAssetType("GCS_BUCKET");
        when(catalogAssetClient.getAssetById(assetId)).thenReturn(Optional.of(asset));
        stubSaveAndMap();

        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());
        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId, req, UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.PASS);
        ArgumentCaptor<PolicyActionEvent> eventCap = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(approvalGate).submit(eventCap.capture(), any());
        assertThat(eventCap.getValue().actionType()).isEqualTo("ARCHIVE");
        assertThat(eventCap.getValue().assetId()).isEqualTo(assetId.toString());
    }

    @Test
    void evaluatePolicyForAsset_catalogLabelsReachTheDslLabelsClause() {
        UUID policyId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        String labelDoc = S3_DOC.replace("match: {assetType: S3_BUCKET}", "match: {labels: {anyOf: [PII]}}");
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy(policyId, PolicyStatus.ENABLED, labelDoc)));
        CatalogAssetDTO asset = new CatalogAssetDTO();
        asset.setAssetId(assetId);
        asset.setAssetName("customers");
        asset.setLabels(List.of("PII"));
        when(catalogAssetClient.getAssetById(assetId)).thenReturn(Optional.of(asset));
        stubSaveAndMap();

        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());
        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId, req, UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.PASS);
    }

    /**
     * SEC-04: a policy that still carries a legacy MVEL rule (as a pre-V5 row would) but no
     * DSL document is not applicable. The rule's condition and name are never executed.
     */
    @Test
    void evaluatePolicyForAsset_legacyRuleWithoutDocument_isNotApplicableAndNeverExecuted() {
        final String probeKey = "sec04.rule.probe";
        System.clearProperty(probeKey);
        try {
            UUID policyId = UUID.randomUUID();
            PolicyRule rule = new PolicyRule();
            rule.setName("x\", true); java.lang.System.setProperty(\"" + probeKey + "\", \"1\"); ruleResults.put(\"y");
            rule.setCondition("java.lang.System.setProperty('" + probeKey + "', '2') == null");
            Policy p = policy(policyId, PolicyStatus.ENABLED, null);
            p.addRule(rule);
            when(policyRepository.findById(policyId)).thenReturn(Optional.of(p));
            stubSaveAndMap();

            PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId,
                    request(Map.of("assetType", "S3_BUCKET")), UUID.randomUUID());

            assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.NOT_APPLICABLE);
            assertThat(System.getProperty(probeKey)).as("retired MVEL must never run").isNull();
            assertThat(firstSaved().getEvaluationDetails().get("reason").toString()).contains("ADR-0057");
            verify(approvalGate, never()).submit(any(), any());
        } finally {
            System.clearProperty(probeKey);
        }
    }

    @Test
    void evaluatePolicyForAsset_invalidDocument_isRecordedAsErrorWithoutActions() {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId))
                .thenReturn(Optional.of(policy(policyId, PolicyStatus.ENABLED, "apiVersion: dlap/v0\n")));
        stubSaveAndMap();

        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId,
                request(Map.of("assetType", "S3_BUCKET")), UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.ERROR);
        assertThat(firstSaved().getEvaluationDetails().get("reason").toString()).startsWith("Invalid policy DSL");
        verify(approvalGate, never()).submit(any(), any());
    }

    @Test
    void evaluatePolicyForAsset_unboundedRegexFailsClosed() {
        UUID policyId = UUID.randomUUID();
        String longRegexDoc = S3_DOC.replace("match: {assetType: S3_BUCKET}",
                "match: {nameMatches: '" + "a".repeat(300) + "'}");
        when(policyRepository.findById(policyId))
                .thenReturn(Optional.of(policy(policyId, PolicyStatus.ENABLED, longRegexDoc)));
        stubSaveAndMap();

        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId,
                request(Map.of("assetName", "a".repeat(300))), UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.FAIL);
        verify(approvalGate, never()).submit(any(), any());
    }

    /** POL-01: the catalog asset carries the lifecycle fields; unknown age is not stale. */
    private CatalogAssetDTO staleCandidate(UUID assetId, String coverage) {
        CatalogAssetDTO asset = new CatalogAssetDTO();
        asset.setAssetId(assetId);
        asset.setAssetUri("gs://old-exports");
        asset.setCloudProvider("GCP");
        asset.setServiceType("GCS_BUCKET");
        asset.setAssetType("GCS_BUCKET");
        asset.setLastAccessedAt(Instant.parse("2025-01-01T00:00:00Z"));
        asset.setReadCoverage(coverage);
        asset.setDetailsJson(Map.of("lastAccessedAt", "2020-01-01T00:00:00Z", "storageClass", "STANDARD"));
        return asset;
    }

    private static final String STALE_DOC = "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: stale, name: stale}\n"
            + "spec:\n  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n"
            + "  match: {lastAccessedAt: {olderThan: P90D}}\n  action: {type: archive, dryRun: true}\n";

    @Test
    void evaluatePolicyForAsset_destructiveActionUnderPartialCoverage_isUnknownAndFiresNothing() {
        UUID policyId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        Policy p = policy(policyId, PolicyStatus.ENABLED, STALE_DOC);
        p.setActions(Map.of());
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(p));
        when(catalogAssetClient.getAssetById(assetId)).thenReturn(Optional.of(staleCandidate(assetId, "PARTIAL")));
        stubSaveAndMap();

        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());
        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId, req, UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.FAIL);
        assertThat(firstSaved().getEvaluationDetails()).containsEntry("outcome", "UNKNOWN")
                .containsEntry("unknownClauses", List.of("lastAccessedAt"));
        verify(approvalGate, never()).submit(any(), any());
    }

    @Test
    void evaluatePolicyForAsset_withoutLegacyActions_publishesTheDocumentActionWithIdentityAndFlags() {
        UUID policyId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        Policy p = policy(policyId, PolicyStatus.ENABLED, STALE_DOC);
        p.setActions(Map.of());
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(p));
        when(catalogAssetClient.getAssetById(assetId)).thenReturn(Optional.of(staleCandidate(assetId, "FULL")));
        stubSaveAndMap();

        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());
        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId, req, UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.PASS);
        ArgumentCaptor<PolicyActionEvent> eventCap = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(approvalGate).submit(eventCap.capture(), any());
        PolicyActionEvent e = eventCap.getValue();
        assertThat(e.actionType()).isEqualTo("archive");
        assertThat(e.assetId()).isEqualTo(assetId.toString());
        assertThat(e.assetUri()).isEqualTo("gs://old-exports");
        assertThat(e.provider()).isEqualTo("GCP");
        assertThat(e.serviceType()).isEqualTo("GCS_BUCKET");
        assertThat(e.triggerType()).isEqualTo("manual");
        assertThat(e.dryRun()).isTrue();
        assertThat(e.firedAt()).isEqualTo(NOW.toString());
        assertThat(e.evaluationId()).isNotNull();
        // A free-form details key never stands in for the catalog's own event time.
        assertThat(e.matchSnapshot()).containsEntry("lastAccessedAt", "2025-01-01T00:00:00Z")
                .containsEntry("storageClass", "STANDARD");
    }

    @Test
    void evaluatePolicyForAsset_legacyDeleteKeyRequiresFullCoverageToo() {
        UUID policyId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        String alertDoc = STALE_DOC.replace("{type: archive, dryRun: true}", "{type: alert.email}");
        Policy p = policy(policyId, PolicyStatus.ENABLED, alertDoc);
        p.setActions(Map.of("DELETE", Map.of("softDeleteWindowDays", 30)));
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(p));
        when(catalogAssetClient.getAssetById(assetId)).thenReturn(Optional.of(staleCandidate(assetId, null)));
        stubSaveAndMap();

        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());
        PolicyEvaluationOutputDTO out = service.evaluatePolicyForAsset(policyId, req, UUID.randomUUID());

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.FAIL);
        verify(approvalGate, never()).submit(any(), any());
    }

    @Test
    void evaluatePolicyForAsset_recordsTheEvaluationTimeFromTheClock() {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy(policyId, PolicyStatus.ENABLED, S3_DOC)));
        stubSaveAndMap();

        service.evaluatePolicyForAsset(policyId, request(Map.of("assetType", "S3_BUCKET")), UUID.randomUUID());

        assertThat(firstSaved().getEvaluatedAt()).isEqualTo(NOW);
    }
}
