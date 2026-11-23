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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dsl.PolicyDslTranslator;
import com.dlap.policyengine.dto.PolicyDraftInputDTO;
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.dto.PolicyOutputDTO;
import com.dlap.policyengine.dto.PolicyRuleDTO;
import com.dlap.policyengine.filter.BoundedRegex;
import com.dlap.policyengine.filter.RetiredRules;
import com.dlap.policyengine.mapper.PolicyDraftMapper;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyRule;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyDraftRepository;
import com.dlap.policyengine.repository.PolicyRepository;

/**
 * SEC-04: legacy policy {@code rules[]} carried MVEL conditions and are retired. Every
 * caller, ROLE_ADMIN included, gets {@link IllegalArgumentException} (HTTP 400) and nothing
 * is mapped or stored. A policy needs a DSL document, and its {@code nameMatches} regex is
 * bounded (256 characters, RE2 syntax).
 */
class PolicyServiceLegacyRulesTest {

    private static final String DOC_PREFIX = ""
            + "apiVersion: dlap/v1\n"
            + "kind: Policy\n"
            + "metadata: {id: p, name: p}\n"
            + "spec:\n"
            + "  triggers: [{type: event, lifecycle: created}]\n"
            + "  action: {type: addLabel, parameters: {label: Prod}}\n";

    private PolicyRepository policyRepository;
    private PolicyMapper policyMapper;
    private PolicyDraftRepository draftRepository;
    private PolicyDraftMapper draftMapper;
    private PolicyService service;

    @BeforeEach
    void setUp() {
        policyRepository = mock(PolicyRepository.class);
        policyMapper = mock(PolicyMapper.class);
        draftRepository = mock(PolicyDraftRepository.class);
        draftMapper = mock(PolicyDraftMapper.class);
        service = new PolicyService(policyRepository, policyMapper, draftRepository, draftMapper,
                new PolicyDslParser(), mock(PolicyDslTranslator.class), mock(ApplicationEventPublisher.class),
                new TestPolicyHistory(policyRepository).history);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("tester", "n/a", authorities));
    }

    private static PolicyInputDTO withLegacyRule(String document) {
        PolicyRuleDTO rule = new PolicyRuleDTO();
        rule.setName("r1");
        rule.setCondition("java.lang.Runtime.getRuntime().exec('x')");
        PolicyInputDTO dto = new PolicyInputDTO();
        dto.setName("legacy");
        dto.setPolicyDocument(document);
        dto.setRules(List.of(rule));
        return dto;
    }

    private static PolicyInputDTO dsl(String document) {
        PolicyInputDTO dto = new PolicyInputDTO();
        dto.setName("dsl-policy");
        dto.setPolicyDocument(document);
        return dto;
    }

    private void stubCreate() {
        Policy entity = new Policy();
        entity.setId(UUID.randomUUID());
        entity.setName("dsl-policy");
        entity.setStatus(PolicyStatus.DISABLED);
        when(policyRepository.findByName(any())).thenReturn(Optional.empty());
        when(policyMapper.toPolicyEntity(any(PolicyInputDTO.class))).thenReturn(entity);
        when(policyRepository.save(entity)).thenReturn(entity);
        when(policyMapper.toPolicyOutputDTO(entity)).thenReturn(new PolicyOutputDTO());
    }

    // ----- retired rules ----------------------------------------------------------------------

    @Test
    void createPolicy_withLegacyRules_isRefusedForEveryRoleAndNothingIsMapped() {
        for (String role : new String[] {"ROLE_ADMIN", "ROLE_POLICY_MANAGER"}) {
            authenticateAs(role);
            assertThatThrownBy(() -> service.createPolicy(withLegacyRule(DOC_PREFIX + "  match: {cloudProvider: GCP}\n"),
                    UUID.randomUUID()))
                    .as(role).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(RetiredRules.POLICY_RULES_MESSAGE);
        }
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.createPolicy(withLegacyRule(null), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(policyMapper);
        verify(policyRepository, never()).save(any());
    }

    @Test
    void updatePolicy_withLegacyRules_isRefusedBeforeAnythingIsLoaded() {
        authenticateAs("ROLE_ADMIN");

        assertThatThrownBy(() -> service.updatePolicy(UUID.randomUUID(), withLegacyRule(null), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(policyRepository, never()).findById(any());
        verify(policyRepository, never()).save(any());
    }

    @Test
    void createPolicy_withoutDocument_isRefused() {
        assertThatThrownBy(() -> service.createPolicy(dsl(null), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyDocument");
        verify(policyRepository, never()).save(any());
    }

    @Test
    void createPolicy_dslOnly_isSaved() {
        authenticateAs("ROLE_POLICY_MANAGER");
        stubCreate();

        assertThat(service.createPolicy(dsl(DOC_PREFIX + "  match: {cloudProvider: GCP}\n"), UUID.randomUUID()))
                .isNotNull();

        verify(policyRepository).save(any(Policy.class));
    }

    /**
     * The document shape the lifecycle e2e harnesses send instead of rules[] (patch in the
     * SEC-04 integrator notes) passes the save-time checks, including the guard that a
     * destructive action needs a match.
     */
    @Test
    void createPolicy_harnessDocument_isSavedForEveryLifecycleAction() {
        stubCreate();
        for (String action : new String[] {"archive", "delete", "addLabel"}) {
            String doc = "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: e2e-aws-" + action + ", name: e2e-aws-" + action
                    + "}\nspec:\n  triggers: [{type: schedule, cron: '0 0 0 1 1 ?'}]\n  match: {cloudProvider: AWS}\n"
                    + "  action: {type: " + action + "}\n";
            assertThat(service.createPolicy(dsl(doc), UUID.randomUUID())).as(action).isNotNull();
        }
    }

    // ----- bounded nameMatches regex ------------------------------------------------------

    @Test
    void createPolicy_withRegexAtTheCap_isSaved() {
        stubCreate();
        String regex = "a".repeat(BoundedRegex.MAX_PATTERN_LENGTH);

        assertThat(service.createPolicy(dsl(DOC_PREFIX + "  match: {nameMatches: '" + regex + "'}\n"),
                UUID.randomUUID())).isNotNull();
    }

    @Test
    void createPolicy_withRegexOverTheCap_isRefused() {
        String regex = "a".repeat(BoundedRegex.MAX_PATTERN_LENGTH + 1);

        assertThatThrownBy(() -> service.createPolicy(dsl(DOC_PREFIX + "  match: {nameMatches: '" + regex + "'}\n"),
                UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256");
        verify(policyRepository, never()).save(any());
    }

    @Test
    void createPolicy_withBacktrackingOnlyRegex_isRefused() {
        assertThatThrownBy(() -> service.createPolicy(dsl(DOC_PREFIX + "  match: {nameMatches: '^(?=prod).*'}\n"),
                UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RE2");
    }

    @Test
    void createPolicy_withNonStringNameMatches_isRefused() {
        assertThatThrownBy(() -> service.createPolicy(dsl(DOC_PREFIX + "  match: {nameMatches: [a, b]}\n"),
                UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nameMatches");
    }

    @Test
    void createDraft_withUnboundedNameMatchesCondition_isRefusedWhenSaved() {
        PolicyDraftInputDTO draft = new PolicyDraftInputDTO();
        draft.setName("d1");
        draft.setRulesDefinition(List.of(Map.of(
                "field", "nameMatches", "operator", "matches", "value", "x".repeat(257))));

        assertThatThrownBy(() -> service.createDraft(draft, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256");
        verify(draftRepository, never()).save(any());
    }

    // ----- enabling ---------------------------------------------------------------------------

    @Test
    void setPolicyStatus_enable_withOnlyLegacyRules_isRefused() {
        UUID id = UUID.randomUUID();
        Policy legacy = new Policy();
        legacy.setId(id);
        legacy.setStatus(PolicyStatus.DISABLED);
        legacy.addRule(new PolicyRule());
        when(policyRepository.findById(id)).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.setPolicyStatus(id, PolicyStatus.ENABLED, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADR-0057");
        verify(policyRepository, never()).save(any());
    }

    @Test
    void setPolicyStatus_enable_withValidDocument_isSaved() {
        UUID id = UUID.randomUUID();
        Policy p = new Policy();
        p.setId(id);
        p.setName("dsl-policy");
        p.setStatus(PolicyStatus.DISABLED);
        p.setPolicyDocument(DOC_PREFIX + "  match: {cloudProvider: GCP}\n");
        when(policyRepository.findById(id)).thenReturn(Optional.of(p));
        when(policyRepository.save(p)).thenReturn(p);
        when(policyMapper.toPolicyOutputDTO(p)).thenReturn(new PolicyOutputDTO());

        service.setPolicyStatus(id, PolicyStatus.ENABLED, UUID.randomUUID());

        assertThat(p.getStatus()).isEqualTo(PolicyStatus.ENABLED);
    }
}
