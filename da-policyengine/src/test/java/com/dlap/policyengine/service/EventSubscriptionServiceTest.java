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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dlap.policyengine.dto.EventStreamDTO;
import com.dlap.policyengine.dto.EventSubscriptionInputDTO;
import com.dlap.policyengine.dto.EventSubscriptionInputDTO.EventRuleInputDTO;
import com.dlap.policyengine.dto.EventSubscriptionOutputDTO;
import com.dlap.policyengine.filter.RetiredRules;
import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventSubscription;
import com.dlap.policyengine.model.EventSubscriptionStatus;
import com.dlap.policyengine.model.EventType;
import com.dlap.policyengine.model.StreamEventLedger;
import com.dlap.policyengine.model.SubscriptionEventMatch;
import com.dlap.policyengine.repository.EventRuleRepository;
import com.dlap.policyengine.repository.EventSubscriptionRepository;
import com.dlap.policyengine.repository.StreamEventLedgerRepository;
import com.dlap.policyengine.repository.SubscriptionEventMatchRepository;

/**
 * SEC-04: subscriptions select events with typed filters. MVEL rule conditions are refused
 * for every caller (including ROLE_ADMIN), are never stored and are never evaluated.
 */
class EventSubscriptionServiceTest {

    private static final String PROBE_KEY = "sec04.event.rule.probe";
    private static final String PAYLOAD_CONDITION =
            "java.lang.System.setProperty('" + PROBE_KEY + "', '1') == null";

    private EventSubscriptionRepository subscriptionRepository;
    private EventRuleRepository ruleRepository;
    private StreamEventLedgerRepository ledgerRepository;
    private SubscriptionEventMatchRepository matchRepository;
    private EventSubscriptionService service;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(EventSubscriptionRepository.class);
        ruleRepository = mock(EventRuleRepository.class);
        ledgerRepository = mock(StreamEventLedgerRepository.class);
        matchRepository = mock(SubscriptionEventMatchRepository.class);
        service = new EventSubscriptionService(subscriptionRepository, ruleRepository, ledgerRepository, matchRepository);
        when(subscriptionRepository.save(any(EventSubscription.class))).thenAnswer(inv -> inv.getArgument(0));
        when(matchRepository.findFirstBySubscriptionIdOrderByMatchedAtDesc(any())).thenReturn(Optional.empty());
        System.clearProperty(PROBE_KEY);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        System.clearProperty(PROBE_KEY);
    }

    private static void authenticateAs(String... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("tester", "n/a", authorities));
    }

    private static EventSubscriptionInputDTO input(EventRuleInputDTO... rules) {
        EventSubscriptionInputDTO dto = new EventSubscriptionInputDTO("subscription");
        dto.setNotificationConfig(Map.of("email", true));
        if (rules.length > 0) {
            dto.setRules(List.of(rules));
        }
        return dto;
    }

    private static EventRuleInputDTO rule(String name, String condition) {
        return new EventRuleInputDTO(name, condition);
    }

    // ----- retired rule conditions ----------------------------------------------------------

    @Test
    void validate_refusesAnyRuleWithoutCompilingOrRunningIt() {
        assertThatThrownBy(() -> service.validateSubscriptionConfiguration(input(rule("r1", PAYLOAD_CONDITION))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(RetiredRules.SUBSCRIPTION_RULES_MESSAGE);
        assertThatThrownBy(() -> service.validateSubscriptionConfiguration(input(rule("r1", "((( not mvel ==="))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(System.getProperty(PROBE_KEY)).as("a condition must never be evaluated").isNull();
    }

    @Test
    void create_withRules_isRefusedForEveryRoleAndNothingIsSaved() {
        for (String role : new String[] {"ROLE_ADMIN", "ROLE_POLICY_MANAGER", "ROLE_DATA_ANALYST"}) {
            authenticateAs(role);
            assertThatThrownBy(() -> service.createSubscription(input(rule("r1", PAYLOAD_CONDITION)), UUID.randomUUID()))
                    .as(role).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ADR-0057");
        }
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.createSubscription(input(rule("r1", "true")), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(subscriptionRepository, never()).save(any());
        verify(ruleRepository, never()).save(any());
        assertThat(System.getProperty(PROBE_KEY)).isNull();
    }

    @Test
    void update_withRules_isRefusedBeforeAnythingIsLoadedOrChanged() {
        authenticateAs("ROLE_ADMIN");
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.updateSubscription(id, input(rule("r1", PAYLOAD_CONDITION)), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(subscriptionRepository, never()).findById(any());
        verify(subscriptionRepository, never()).save(any());
        verify(ruleRepository, never()).save(any());
        verify(ruleRepository, never()).deleteAllBySubscriptionId(any());
    }

    @Test
    void emptyRuleListIsAccepted() {
        EventSubscriptionInputDTO dto = input();
        dto.setRules(List.of());
        assertThatCode(() -> service.validateSubscriptionConfiguration(dto)).doesNotThrowAnyException();
    }

    // ----- structure ------------------------------------------------------------------------

    @Test
    void validate_rejectsStructurallyInvalidInput() {
        assertThatThrownBy(() -> service.validateSubscriptionConfiguration(null))
                .isInstanceOf(IllegalArgumentException.class);

        EventSubscriptionInputDTO noName = input();
        noName.setName(" ");
        assertThatThrownBy(() -> service.validateSubscriptionConfiguration(noName))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name is required");

        EventSubscriptionInputDTO longName = input();
        longName.setName("n".repeat(256));
        assertThatThrownBy(() -> service.validateSubscriptionConfiguration(longName))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("255");

        EventSubscriptionInputDTO noNotification = input();
        noNotification.setNotificationConfig(Map.of());
        assertThatThrownBy(() -> service.validateSubscriptionConfiguration(noNotification))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("notification");
    }

    @Test
    void validate_rejectsBadTypedFilterValues() {
        EventSubscriptionInputDTO unknownSource = input();
        unknownSource.setSourceServices(List.of("da-catalog", "not-a-service"));
        assertThatThrownBy(() -> service.validateSubscriptionConfiguration(unknownSource))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown source service");

        EventSubscriptionInputDTO blankPrefix = input();
        blankPrefix.setAssetPrefixes(List.of("s3://b/", " "));
        assertThatThrownBy(() -> service.validateSubscriptionConfiguration(blankPrefix))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("assetPrefixes");

        EventSubscriptionInputDTO longLabel = input();
        longLabel.setLabels(List.of("l".repeat(256)));
        assertThatThrownBy(() -> service.validateSubscriptionConfiguration(longLabel))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("labels");
    }

    // ----- typed filters are stored -------------------------------------------------------------

    @Test
    void create_storesTheCleanedTypedFilter() {
        authenticateAs("ROLE_DATA_ANALYST");
        EventSubscriptionInputDTO dto = input();
        dto.setEventTypes(List.of(EventType.ASSET_DISCOVERED, EventType.ASSET_DISCOVERED));
        dto.setSeverities(List.of(EventSeverity.HIGH));
        dto.setSourceServices(List.of("da-catalog"));
        dto.setAssetPrefixes(List.of(" S3://finance/ ", "bq://analytics-prod/sales"));
        dto.setLabels(List.of("PII", "pii", "Finance"));

        EventSubscriptionOutputDTO out = service.createSubscription(dto, UUID.randomUUID());

        ArgumentCaptor<EventSubscription> saved = ArgumentCaptor.forClass(EventSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        EventSubscription s = saved.getValue();
        assertThat(s.getEventTypes()).containsExactly(EventType.ASSET_DISCOVERED);
        assertThat(s.getSeverities()).containsExactly(EventSeverity.HIGH);
        assertThat(s.getSourceServices()).containsExactly("da-catalog");
        assertThat(s.getAssetPrefixes()).containsExactlyInAnyOrder("s3://finance/", "bq://analytics-prod/sales");
        assertThat(s.getLabels()).containsExactlyInAnyOrder("PII", "Finance");
        assertThat(s.getRules()).isEmpty();
        verify(ruleRepository, never()).save(any());

        assertThat(out.getAssetPrefixes()).containsExactly("bq://analytics-prod/sales", "s3://finance/");
        assertThat(out.getLabels()).containsExactly("Finance", "PII");
        assertThat(out.getRules()).isEmpty();
        assertThat(out.getTotalRulesCount()).isZero();
    }

    @Test
    void update_replacesOnlyTheFilterListsThatArePresent() {
        UUID id = UUID.randomUUID();
        EventSubscription existing = new EventSubscription("old", UUID.randomUUID());
        existing.setAssetPrefixes(List.of("s3://old/"));
        existing.setLabels(List.of("Old"));
        existing.setEventTypes(new java.util.ArrayList<>(List.of(EventType.SYSTEM_ALERT)));
        when(subscriptionRepository.findById(id)).thenReturn(Optional.of(existing));

        EventSubscriptionInputDTO dto = input();
        dto.setLabels(List.of("New"));

        service.updateSubscription(id, dto, UUID.randomUUID());

        assertThat(existing.getLabels()).containsExactly("New");
        assertThat(existing.getAssetPrefixes()).as("absent list keeps the stored values").containsExactly("s3://old/");
        assertThat(existing.getEventTypes()).containsExactly(EventType.SYSTEM_ALERT);
        verify(ruleRepository, never()).save(any());
    }

    // ----- typed filters decide the matches -------------------------------------------------

    @Test
    void processIncomingEvent_recordsAMatchOnlyForSubscriptionsWhoseFilterHolds() {
        EventSubscription financePii = active("finance-pii");
        financePii.setAssetPrefixes(List.of("s3://finance/"));
        financePii.setLabels(List.of("PII"));
        EventSubscription prodOnly = active("prod-only");
        prodOnly.setAssetPrefixes(List.of("s3://prod/"));
        EventSubscription everything = active("everything");
        when(subscriptionRepository.findByStatus(EventSubscriptionStatus.ACTIVE))
                .thenReturn(List.of(financePii, prodOnly, everything));
        when(ledgerRepository.save(any(StreamEventLedger.class))).thenAnswer(inv -> inv.getArgument(0));

        EventStreamDTO event = new EventStreamDTO();
        event.setEventId(UUID.randomUUID());
        event.setEventType(EventType.ASSET_CLASSIFICATION_CHANGED);
        event.setSourceService("da-catalog");
        event.setEventData(Map.of(
                "assetUri", "s3://finance/reports/q1.csv",
                "labelDetails", Map.of("details", Map.of("labels_assigned", "pii"))));

        service.processIncomingEvent(event);

        ArgumentCaptor<SubscriptionEventMatch> matches = ArgumentCaptor.forClass(SubscriptionEventMatch.class);
        verify(matchRepository, times(2)).save(matches.capture());
        assertThat(matches.getAllValues()).extracting(SubscriptionEventMatch::getSubscriptionId)
                .containsExactlyInAnyOrder(financePii.getId(), everything.getId());
    }

    private static EventSubscription active(String name) {
        EventSubscription s = new EventSubscription(name, UUID.randomUUID());
        s.setId(UUID.randomUUID());
        s.setStatus(EventSubscriptionStatus.ACTIVE);
        return s;
    }

    @Test
    void sourceServicesListIsTheValidationList() {
        assertThat(service.getAvailableSourceServices()).contains("da-catalog", "da-discovery", "da-autocompliance");
    }
}
