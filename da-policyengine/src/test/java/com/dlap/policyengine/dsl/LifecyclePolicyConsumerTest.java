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

package com.dlap.policyengine.dsl;

import static com.dlap.policyengine.dsl.CatalogLookups.uncached;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-01 on the event path: a log-spine event names its asset only by URI; the consumer resolves
 * the catalog id, completes the snapshot from the catalog, and the action carries assetId,
 * assetUri, provider and serviceType. An asset nobody can identify is never acted on.
 */
class LifecyclePolicyConsumerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000001");
    private static final String URI = "gs://prod-customer-exports";

    private static final String LABEL_PII_ON_ACCESS = ""
            + "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: tag-pii-access, name: Tag PII access}\n"
            + "spec:\n  triggers: [{type: event, lifecycle: accessed}]\n"
            + "  match: {cloudProvider: GCP, labels: {anyOf: [PII]}}\n"
            + "  action: {type: alert.webhook, dryRun: true}\n";

    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final ApprovalGate gate = mock(ApprovalGate.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private LifecyclePolicyConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new LifecyclePolicyConsumer(policies, new PolicyDslParser(), new PolicyDslEvaluator(clock),
                gate, new ObjectMapper(), uncached(catalog, clock), clock, registry);
    }

    private void givenPolicies(String... yamls) {
        List<Policy> list = new java.util.ArrayList<>();
        for (String y : yamls) {
            Policy p = new Policy();
            p.setId(UUID.randomUUID());
            p.setName("p");
            p.setStatus(PolicyStatus.ENABLED);
            p.setPolicyDocument(y);
            list.add(p);
        }
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(list);
    }

    private static CatalogAssetDTO catalogAsset() {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(ASSET);
        a.setAssetUri(URI);
        a.setCloudProvider("GCP");
        a.setServiceType("GCS_BUCKET");
        a.setAssetType("GCS_BUCKET");
        a.setLabels(List.of("PII"));
        a.setLastAccessedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return a;
    }

    /** The JSON the discovery log spine puts on a typed lifecycle topic (log-event.md 4.2). */
    private static Map<String, Object> logSpineEvent() {
        return Map.of(
                "eventId", "GCP_CLOUD_AUDIT|abc123",
                "sourceProvider", "GCP",
                "changeType", "ACCESS",
                "targetResource", Map.of(
                        "kind", "gcs_bucket",
                        "id", "projects/_/buckets/prod-customer-exports",
                        "name", "prod-customer-exports",
                        "canonicalUri", URI));
    }

    @Test
    void aLogSpineEventIsResolvedThroughTheCatalogAndTheActionCarriesTheIdentity() {
        givenPolicies(LABEL_PII_ON_ACCESS);
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.of(ASSET));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(catalogAsset()));

        int matched = consumer.evaluate(logSpineEvent(), DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED);

        assertThat(matched).isEqualTo(1);
        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate).submit(sent.capture());
        PolicyActionEvent e = sent.getValue();
        assertThat(e.assetId()).isEqualTo(ASSET.toString());
        assertThat(e.assetUri()).isEqualTo(URI);
        assertThat(e.provider()).isEqualTo("GCP");
        assertThat(e.serviceType()).isEqualTo("GCS_BUCKET");
        assertThat(e.triggerType()).isEqualTo("event");
        assertThat(e.triggerLifecycle()).isEqualTo("accessed");
        assertThat(e.sourceEventId()).isEqualTo("GCP_CLOUD_AUDIT|abc123");
        assertThat(e.firedAt()).isEqualTo(NOW.toString());
        assertThat(e.dryRun()).isTrue();
        // The event's own values win; the catalog fills the rest.
        assertThat(e.matchSnapshot()).containsEntry("assetType", "gcs_bucket")
                .containsEntry("labels", List.of("PII"))
                .containsEntry("lastAccessedAt", "2026-01-01T00:00:00Z");
    }

    @Test
    void aRedeliveredEventPublishesTheSameFireId() {
        givenPolicies(LABEL_PII_ON_ACCESS);
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.of(ASSET));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(catalogAsset()));

        consumer.evaluate(logSpineEvent(), DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED);
        consumer.evaluate(logSpineEvent(), DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED);

        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate, times(2)).submit(sent.capture());
        assertThat(sent.getAllValues().get(0).fireId()).isEqualTo(sent.getAllValues().get(1).fireId());
    }

    @Test
    void anAssetTheCatalogCannotIdentifyIsNeverActedOn() {
        givenPolicies("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: any-gcp}\nspec:\n"
                + "  triggers: [{type: event, lifecycle: accessed}]\n  match: {cloudProvider: GCP}\n"
                + "  action: {type: alert.email}\n");
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.empty());

        assertThat(consumer.evaluate(logSpineEvent(), DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED)).isZero();

        verify(gate, never()).submit(any());
        assertThat(registry.counter(LifecyclePolicyConsumer.SKIPPED_COUNTER, "reason", "unresolved-asset").count())
                .isEqualTo(1.0);
    }

    @Test
    void aStaleReadUnderPartialCoverageDoesNotArchive() {
        givenPolicies("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: archive-stale}\nspec:\n"
                + "  triggers: [{type: event, lifecycle: accessed}]\n  match: {lastAccessedAt: {olderThan: P90D}}\n"
                + "  action: {type: archive}\n");
        CatalogAssetDTO asset = catalogAsset();
        asset.setReadCoverage("PARTIAL");
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.of(ASSET));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset));

        assertThat(consumer.evaluate(logSpineEvent(), DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED)).isZero();
        verify(gate, never()).submit(any());
    }

    @Test
    void aMatchSnapshotWithACatalogIdNeedsNoUriResolution() {
        givenPolicies(LABEL_PII_ON_ACCESS);
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.empty());
        Map<String, Object> event = Map.of("assetId", ASSET.toString(),
                "matchSnapshot", Map.of("cloudProvider", "GCP", "labels", List.of("PII"), "assetUri", URI));

        assertThat(consumer.evaluate(event, DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED)).isEqualTo(1);
        verify(catalog, never()).resolveAssetIdByUri(any());
        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate).submit(sent.capture());
        assertThat(sent.getValue().assetId()).isEqualTo(ASSET.toString());
        assertThat(sent.getValue().assetUri()).isEqualTo(URI);
        assertThat(sent.getValue().serviceType()).as("unknown stays null, never guessed").isNull();
    }

    @Test
    void noCatalogCallWhenNoPolicyListensOnTheTopic() {
        givenPolicies(LABEL_PII_ON_ACCESS);
        assertThat(consumer.evaluate(logSpineEvent(), DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED)).isZero();
        verify(catalog, never()).resolveAssetIdByUri(any());
        verify(catalog, never()).getAssetById(any());
    }

    @Test
    void theListenerAcknowledgesAfterEvaluation() throws Exception {
        givenPolicies();
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onLifecycleEvent(new ObjectMapper().writeValueAsString(logSpineEvent()),
                DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED, ack);
        verify(ack).acknowledge();
    }

    @Test
    void aDestructiveFireGoesThroughTheApprovalGate() {
        givenPolicies("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: delete-on-access}\nspec:\n"
                + "  triggers: [{type: event, lifecycle: accessed}]\n  match: {cloudProvider: GCP}\n"
                + "  action: {type: delete, parameters: {softDeleteWindowDays: 0}}\n");
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.of(ASSET));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(catalogAsset()));
        when(gate.submit(any())).thenReturn(ApprovalGate.Route.HELD);

        assertThat(consumer.evaluate(logSpineEvent(), DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED)).isEqualTo(1);

        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate).submit(sent.capture());
        assertThat(sent.getValue().actionType()).isEqualTo("delete");
        assertThat(sent.getValue().requireApproval()).as("the gate, not the policy, decides it waits").isFalse();
    }

    @Test
    void anApprovalStoreOutageIsThrownSoTheRecordIsRetriedNotAcknowledged() throws Exception {
        givenPolicies("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: archive-on-access}\nspec:\n"
                + "  triggers: [{type: event, lifecycle: accessed}]\n  match: {cloudProvider: GCP}\n"
                + "  action: {type: archive}\n");
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.of(ASSET));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(catalogAsset()));
        when(gate.submit(any())).thenThrow(new DataAccessResourceFailureException("database down"));
        Acknowledgment ack = mock(Acknowledgment.class);

        assertThatThrownBy(() -> consumer.onLifecycleEvent(new ObjectMapper().writeValueAsString(logSpineEvent()),
                DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED, ack)).isInstanceOf(RuntimeException.class);
        verify(ack, never()).acknowledge();
    }
}
