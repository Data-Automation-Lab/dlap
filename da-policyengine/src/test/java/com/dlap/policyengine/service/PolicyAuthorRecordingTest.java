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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dsl.PolicyDslTranslator;
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.dto.PolicyOutputDTO;
import com.dlap.policyengine.mapper.PolicyDraftMapper;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyDraftRepository;
import com.dlap.policyengine.repository.PolicyRepository;

/**
 * Q-571 (FU-4B): the policy authors the approval gate excludes are really recorded. The service
 * stores the creator on create and the last editor on every update and status change, from the
 * user id the controller takes from the JWT {@code sub}; the mapper never overwrites them. The
 * gate reads both ({@code ApprovalService.policyAuthors}), so neither can approve a fire of
 * their own policy (see ApprovalServiceTest and OnDemandEvaluationDualControlTest).
 */
class PolicyAuthorRecordingTest {

    private static final String DOC = ""
            + "apiVersion: dlap/v1\n"
            + "kind: Policy\n"
            + "metadata: {id: p, name: p}\n"
            + "spec:\n"
            + "  triggers: [{type: event, lifecycle: created}]\n"
            + "  action: {type: addLabel, parameters: {label: Prod}}\n";

    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

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

    private static PolicyInputDTO input(String name) {
        PolicyInputDTO dto = new PolicyInputDTO();
        dto.setName(name);
        dto.setPolicyDocument(DOC);
        dto.setStatus(PolicyStatus.DISABLED);
        return dto;
    }

    @Test
    void theCreatorIsRecordedOnCreate() {
        PolicyOutputDTO out = service.createPolicy(input("retire-stale"), ALICE);

        assertThat(out.getCreatedByUserId()).isEqualTo(ALICE);
    }

    @Test
    void anUpdateRecordsTheEditorAndKeepsTheCreator() {
        PolicyOutputDTO created = service.createPolicy(input("retire-stale"), ALICE);
        Policy stored = new Policy();
        stored.setId(created.getId());
        stored.setName("retire-stale");
        stored.setStatus(PolicyStatus.DISABLED);
        stored.setCreatedByUserId(ALICE);
        when(repository.findById(created.getId())).thenReturn(Optional.of(stored));

        PolicyOutputDTO updated = service.updatePolicy(created.getId(), input("retire-stale"), BOB);

        assertThat(updated.getCreatedByUserId()).isEqualTo(ALICE);
        assertThat(updated.getUpdatedByUserId()).isEqualTo(BOB);
    }

    @Test
    void aStatusChangeRecordsTheEditor() {
        Policy stored = new Policy();
        UUID id = UUID.randomUUID();
        stored.setId(id);
        stored.setName("retire-stale");
        stored.setStatus(PolicyStatus.DISABLED);
        stored.setPolicyDocument(DOC);
        stored.setCreatedByUserId(ALICE);
        when(repository.findById(id)).thenReturn(Optional.of(stored));

        PolicyOutputDTO out = service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB);

        assertThat(out.getUpdatedByUserId()).isEqualTo(BOB);
        assertThat(out.getCreatedByUserId()).isEqualTo(ALICE);
    }
}
