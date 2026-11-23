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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import com.dlap.policyengine.approval.ActionClass;
import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.approval.ApprovalService;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.dsl.PolicyDslEvaluator;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dto.PolicyEvaluationOutputDTO;
import com.dlap.policyengine.dto.PolicyEvaluationRequestDTO;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyEvaluation;
import com.dlap.policyengine.model.PolicyEvaluationStatus;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyEvaluationRepository;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * APPR-01 (Q-569): the on-demand evaluation endpoint, {@code POST /{policyId}/evaluations}, fires
 * through the real {@link ApprovalGate}, like the event and schedule paths. A hard delete, a soft
 * delete and an archive that are not dry runs wait as approval requests and reach the producer
 * only when the request is released; a dry run and a non-destructive action publish at once; a
 * database failure while recording the request fails the call and publishes nothing.
 */
class PolicyEvaluationApprovalTest {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final PolicyEvaluationRepository evaluationRepository = mock(PolicyEvaluationRepository.class);
    private final PolicyMapper policyMapper = mock(PolicyMapper.class);
    private final PolicyActionProducer producer = mock(PolicyActionProducer.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private PolicyEvaluationService service;
    private final UUID policyId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void wire() {
        when(producer.actionsEnabled()).thenReturn(true);
        when(producer.send(any())).thenReturn(PolicyActionProducer.Outcome.PUBLISHED);
        when(approvals.hold(any(), any(), any())).thenReturn(true);
        service = new PolicyEvaluationService(policyRepository, evaluationRepository, policyMapper,
                new ObjectMapper(), new ApprovalGate(producer, approvals), catalog, new PolicyDslParser(),
                new PolicyDslEvaluator(clock), clock);
        when(evaluationRepository.save(any(PolicyEvaluation.class))).thenAnswer(inv -> {
            PolicyEvaluation e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
        when(policyMapper.toPolicyEvaluationOutputDTO(any(PolicyEvaluation.class), eq("gcp-lifecycle")))
                .thenAnswer(inv -> {
                    PolicyEvaluation e = inv.getArgument(0);
                    PolicyEvaluationOutputDTO dto = new PolicyEvaluationOutputDTO();
                    dto.setStatus(e.getStatus());
                    dto.setEvaluationDetails(e.getEvaluationDetails());
                    dto.setTriggeredActions(e.getTriggeredActions());
                    return dto;
                });
        CatalogAssetDTO asset = new CatalogAssetDTO();
        asset.setAssetId(assetId);
        asset.setAssetUri("gs://dlap-gcp-e2e-bucket");
        asset.setCloudProvider("GCP");
        asset.setServiceType("GCS_BUCKET");
        asset.setAssetType("GCS_BUCKET");
        when(catalog.getAssetById(assetId)).thenReturn(Optional.of(asset));
    }

    /** A dlap/v1 policy matching GCP assets whose legacy actions map carries one action. */
    private void policyWith(String documentAction, String actionKey, Map<String, Object> parameters) {
        Policy p = new Policy();
        p.setId(policyId);
        p.setName("gcp-lifecycle");
        p.setStatus(PolicyStatus.ENABLED);
        p.setPolicyDocument("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: gcp-lifecycle, name: gcp-lifecycle}\n"
                + "spec:\n  triggers: [{type: schedule, cron: '0 0 0 1 1 ?'}]\n  match: {cloudProvider: GCP}\n"
                + "  action: " + documentAction + "\n");
        p.setActions(Map.of(actionKey, parameters));
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(p));
    }

    private PolicyEvaluationOutputDTO evaluate() {
        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());
        return service.evaluatePolicyForAsset(policyId, req, UUID.randomUUID());
    }

    @Test
    void aHardDeleteFromTheOnDemandPathIsHeldForApproval() {
        policyWith("{type: delete}", "DELETE", Map.of("softDeleteWindowDays", 0));

        PolicyEvaluationOutputDTO out = evaluate();

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.PASS);
        ArgumentCaptor<PolicyActionEvent> held = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(approvals).hold(held.capture(), eq(ActionClass.HARD_DELETE), any());
        verify(producer, never()).send(any());
        PolicyActionEvent fire = held.getValue();
        assertThat(fire.triggerType()).isEqualTo(PolicyActionEvent.TRIGGER_MANUAL);
        assertThat(fire.actionType()).isEqualTo("DELETE");
        assertThat(fire.assetId()).isEqualTo(assetId.toString());
        assertThat(fire.policyDbId()).isEqualTo(policyId.toString());
        assertThat(fire.dryRun()).isFalse();
        assertThat(fire.evaluationId()).isNotNull();
        assertThat(out.getTriggeredActions()).containsKey("DELETE");
        assertThat(out.getEvaluationDetails()).containsEntry("actionRoutes", Map.of("DELETE", "HELD"));
    }

    @Test
    void aDeleteWithoutAWindowAndAnArchiveAreHeldToo() {
        policyWith("{type: delete}", "DELETE", Map.of("auditNote", "soft delete"));
        evaluate();
        verify(approvals).hold(any(), eq(ActionClass.SOFT_DELETE), any());

        policyWith("{type: archive}", "ARCHIVE", Map.of("tier", "COLD"));
        evaluate();
        verify(approvals).hold(any(), eq(ActionClass.ARCHIVE), any());

        verify(producer, never()).send(any());
    }

    @Test
    void aDryRunFromTheOnDemandPathIsNotHeld() {
        policyWith("{type: delete, dryRun: true}", "DELETE", Map.of("softDeleteWindowDays", 0));

        PolicyEvaluationOutputDTO out = evaluate();

        assertThat(out.getStatus()).isEqualTo(PolicyEvaluationStatus.PASS);
        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(producer).send(sent.capture());
        verify(approvals, never()).hold(any(), any(), any());
        assertThat(sent.getValue().dryRun()).isTrue();
        assertThat(sent.getValue().actionType()).isEqualTo("DELETE");
        assertThat(out.getEvaluationDetails()).containsEntry("actionRoutes", Map.of("DELETE", "PUBLISHED"));
    }

    @Test
    void aNonDestructiveActionIsPublishedAtOnce() {
        policyWith("{type: addLabel}", "addLabel", Map.of("label", "E2E_REVIEWED"));

        PolicyEvaluationOutputDTO out = evaluate();

        verify(producer).send(any());
        verify(approvals, never()).hold(any(), any(), any());
        assertThat(out.getEvaluationDetails()).containsEntry("actionRoutes", Map.of("addLabel", "PUBLISHED"));
    }

    @Test
    void aRequireApprovalActionIsHeldEvenWhenItIsNotDestructive() {
        policyWith("{type: addLabel, requireApproval: true}", "addLabel", Map.of("label", "E2E_REVIEWED"));

        evaluate();

        verify(approvals).hold(any(), eq(ActionClass.OTHER), any());
        verify(producer, never()).send(any());
    }

    @Test
    void aFailureToRecordTheRequestFailsTheCallAndPublishesNothing() {
        policyWith("{type: delete}", "DELETE", Map.of("softDeleteWindowDays", 0));
        when(approvals.hold(any(), any(), any())).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(this::evaluate).isInstanceOf(DataAccessResourceFailureException.class);

        verify(producer, never()).send(any());
    }

    @Test
    void aFireThatAlreadyWaitsIsReportedAsHeld() {
        policyWith("{type: archive}", "ARCHIVE", Map.of("tier", "COLD"));
        when(approvals.hold(any(), any(), any())).thenReturn(false);

        PolicyEvaluationOutputDTO out = evaluate();

        assertThat(out.getEvaluationDetails()).containsEntry("actionRoutes", Map.of("ARCHIVE", "ALREADY_HELD"));
        verify(producer, never()).send(any());
        assertThat(out.getTriggeredActions()).containsOnlyKeys("ARCHIVE");
    }

    @Test
    void theCallersSubjectReachesTheGateAsTheTriggeringParty() {
        policyWith("{type: archive}", "ARCHIVE", Map.of("tier", "COLD"));
        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());

        service.triggerPolicyEvaluation(policyId, req, UUID.randomUUID(), "sub-admin-alice");

        verify(approvals).hold(any(), eq(ActionClass.ARCHIVE), eq("sub-admin-alice"));
    }

    @Test
    void theThreeArgumentFormTreatsTheUserIdAsTheTriggeringSubject() {
        policyWith("{type: archive}", "ARCHIVE", Map.of("tier", "COLD"));
        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());
        UUID user = UUID.randomUUID();

        service.triggerPolicyEvaluation(policyId, req, user);

        verify(approvals).hold(any(), eq(ActionClass.ARCHIVE), eq(user.toString()));
    }
}
