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
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dsl.PolicyDslTranslator;
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.mapper.PolicyDraftMapper;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyKind;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyDraftRepository;
import com.dlap.policyengine.repository.PolicyRepository;

/**
 * POL-04 (ADR-0136, Q-871): the API paths that write a policy row set {@code policy_kind}. A created
 * policy is a DSL row; a legacy rule row that is updated with a document becomes a DSL row; a request
 * without a document is refused, so a blank DSL row cannot be created through the API.
 */
class PolicyKindOnSaveTest {

    private static final String DOC = ""
            + "apiVersion: dlap/v1\n"
            + "kind: Policy\n"
            + "metadata: {id: p, name: p}\n"
            + "spec:\n"
            + "  triggers: [{type: event, lifecycle: created}]\n"
            + "  action: {type: addLabel, parameters: {label: Prod}}\n";

    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private final PolicyRepository repository = mock(PolicyRepository.class);
    private PolicyService service;

    @BeforeEach
    void wire() {
        service = new PolicyService(repository, new PolicyMapper(), mock(PolicyDraftRepository.class),
                mock(PolicyDraftMapper.class), new PolicyDslParser(), mock(PolicyDslTranslator.class),
                mock(ApplicationEventPublisher.class), new TestPolicyHistory(repository).history);
        when(repository.findByName(any())).thenReturn(Optional.empty());
        when(repository.save(any(Policy.class))).thenAnswer(inv -> {
            Policy p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });
    }

    private static PolicyInputDTO input(String name, String document) {
        PolicyInputDTO dto = new PolicyInputDTO();
        dto.setName(name);
        dto.setPolicyDocument(document);
        dto.setStatus(PolicyStatus.DISABLED);
        return dto;
    }

    @Test
    void aPolicyCreatedThroughTheApiIsADslRow() {
        service.createPolicy(input("retire-stale", DOC), ALICE);

        ArgumentCaptor<Policy> saved = ArgumentCaptor.forClass(Policy.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPolicyKind()).isEqualTo(PolicyKind.DSL);
    }

    @Test
    void aLegacyRuleRowUpdatedWithADocumentBecomesADslRow() {
        UUID id = UUID.randomUUID();
        Policy legacy = new Policy();
        legacy.setId(id);
        legacy.setName("retire-stale");
        legacy.setStatus(PolicyStatus.DISABLED);
        legacy.setPolicyKind(PolicyKind.LEGACY_RULE);
        when(repository.findById(id)).thenReturn(Optional.of(legacy));

        service.updatePolicy(id, input("retire-stale", DOC), ALICE);

        assertThat(legacy.getPolicyKind()).isEqualTo(PolicyKind.DSL);
        assertThat(legacy.getPolicyDocument()).isEqualTo(DOC);
    }

    @Test
    void aCreateWithoutADocumentIsRefusedAndNothingIsSaved() {
        assertThatThrownBy(() -> service.createPolicy(input("blank", "  "), ALICE))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any(Policy.class));
    }

    @Test
    void anUpdateThatBlanksTheDocumentIsRefusedAndTheKindStaysLegacy() {
        UUID id = UUID.randomUUID();
        Policy legacy = new Policy();
        legacy.setId(id);
        legacy.setName("retire-stale");
        legacy.setStatus(PolicyStatus.DISABLED);
        legacy.setPolicyKind(PolicyKind.LEGACY_RULE);
        when(repository.findById(id)).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.updatePolicy(id, input("retire-stale", ""), ALICE))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(legacy.getPolicyKind()).isEqualTo(PolicyKind.LEGACY_RULE);
    }

    @Test
    void aNewEntityIsADslRowAndANullKindFallsBackToDsl() {
        Policy fresh = new Policy();
        assertThat(fresh.getPolicyKind()).isEqualTo(PolicyKind.DSL);

        fresh.setPolicyKind(PolicyKind.LEGACY_RULE);
        fresh.setPolicyKind(null);

        assertThat(fresh.getPolicyKind()).isEqualTo(PolicyKind.DSL);
    }
}
