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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.mapper.PolicyDraftMapper;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyDraftRepository;
import com.dlap.policyengine.repository.PolicyRepository;
import com.dlap.policyengine.service.PolicyService;
import com.dlap.policyengine.service.TestPolicyHistory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-05 (decision D45, Q-889): a {@code kind: Policy} document that states {@code spec.effect} is
 * refused at write time (HTTP 400 through {@code IllegalArgumentException}), and the start-up check reports
 * a stored one under the existing {@code access-policy} check. Before, such a document parsed, looked like a
 * deny and was silently ignored by the PDP.
 */
class LifecyclePolicyEffectRefusedTest {

    private static final String LIFECYCLE_WITH_EFFECT = "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: p, name: p}\n"
            + "spec:\n  triggers: [{type: event, lifecycle: created}]\n  effect: deny\n"
            + "  action: {type: addLabel, parameters: {label: Prod}}\n";
    private static final String LIFECYCLE_PLAIN = LIFECYCLE_WITH_EFFECT.replace("  effect: deny\n", "");
    private static final String LIFECYCLE_NULL_EFFECT = LIFECYCLE_WITH_EFFECT.replace("effect: deny", "effect: ~");
    private static final String ACCESS_DENY = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: a}\nspec:\n"
            + "  subjects: {roles: [X]}\n  actions: [read]\n  effect: deny\n";
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private static PolicyDsl read(String yaml) {
        return new PolicyDslParser().readUnvalidated(yaml);
    }

    @Nested
    class ParserRule {

        @Test
        void aLifecyclePolicyThatStatesAnEffectHasADefectNamingTheEffect() {
            String defect = PolicyDslParser.lifecycleEffectDefect(read(LIFECYCLE_WITH_EFFECT));

            assertThat(defect).contains("spec.effect is not allowed on kind Policy").contains("'deny'")
                    .contains("AccessPolicy");
        }

        @Test
        void anyStatedEffectCountsNotOnlyDeny() {
            assertThat(PolicyDslParser.lifecycleEffectDefect(read(LIFECYCLE_WITH_EFFECT.replace("deny", "allow"))))
                    .isNotNull();
            assertThat(PolicyDslParser.lifecycleEffectDefect(read(LIFECYCLE_WITH_EFFECT.replace("deny", "''"))))
                    .isNotNull();
        }

        @Test
        void aLifecyclePolicyWithoutAnEffectAndAnExplicitNullEffectHaveNoDefect() {
            assertThat(PolicyDslParser.lifecycleEffectDefect(read(LIFECYCLE_PLAIN))).isNull();
            assertThat(PolicyDslParser.lifecycleEffectDefect(read(LIFECYCLE_NULL_EFFECT))).isNull();
        }

        @Test
        void anAccessPolicyAndNullInputHaveNoDefect() {
            assertThat(PolicyDslParser.lifecycleEffectDefect(read(ACCESS_DENY))).isNull();
            assertThat(PolicyDslParser.lifecycleEffectDefect(null)).isNull();
        }

        @Test
        void theKindIsReadWithoutCaseOrSurroundingBlanks() {
            assertThat(PolicyDslParser.lifecycleEffectDefect(read(LIFECYCLE_WITH_EFFECT.replace("kind: Policy",
                    "kind: ' policy '")))).isNotNull();
        }
    }

    @Nested
    class WritePath {

        private final PolicyRepository repository = mock(PolicyRepository.class);
        private PolicyService service;

        @BeforeEach
        void wire() {
            service = new PolicyService(repository, new PolicyMapper(), mock(PolicyDraftRepository.class),
                    mock(PolicyDraftMapper.class), new PolicyDslParser(), mock(PolicyDslTranslator.class),
                    mock(ApplicationEventPublisher.class), new TestPolicyHistory(repository).history);
            when(repository.findByName(any())).thenReturn(Optional.empty());
        }

        private PolicyInputDTO input(String document) {
            PolicyInputDTO dto = new PolicyInputDTO();
            dto.setName("lifecycle");
            dto.setPolicyDocument(document);
            dto.setStatus(PolicyStatus.DISABLED);
            return dto;
        }

        @Test
        void createRefusesALifecyclePolicyWithAnEffectAndSavesNothing() {
            assertThatThrownBy(() -> service.createPolicy(input(LIFECYCLE_WITH_EFFECT), ALICE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid policy DSL")
                    .hasMessageContaining("spec.effect is not allowed on kind Policy");

            verify(repository, never()).save(any(Policy.class));
        }

        @Test
        void updateRefusesItToo() {
            UUID id = UUID.randomUUID();
            Policy existing = new Policy();
            existing.setId(id);
            existing.setName("lifecycle");
            existing.setStatus(PolicyStatus.DISABLED);
            existing.setPolicyDocument(LIFECYCLE_PLAIN);
            when(repository.findById(id)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.updatePolicy(id, input(LIFECYCLE_WITH_EFFECT), ALICE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("spec.effect is not allowed on kind Policy");

            assertThat(existing.getPolicyDocument()).isEqualTo(LIFECYCLE_PLAIN);
        }

        @Test
        void aLifecyclePolicyWithoutAnEffectAndAnAccessPolicyWithOneAreStillAccepted() {
            when(repository.save(any(Policy.class))).thenAnswer(inv -> {
                Policy p = inv.getArgument(0);
                p.setId(UUID.randomUUID());
                return p;
            });

            service.createPolicy(input(LIFECYCLE_PLAIN), ALICE);
            PolicyInputDTO access = input(ACCESS_DENY);
            access.setName("access");
            service.createPolicy(access, ALICE);

            verify(repository, times(2)).save(any(Policy.class));
        }
    }

    @Nested
    class StartUpReport {

        private final PolicyRepository repository = mock(PolicyRepository.class);
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final List<Policy> stored = new ArrayList<>();
        private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
        private final Logger logger = (Logger) LoggerFactory.getLogger(StoredPolicyValidator.class);
        private StoredPolicyValidator validator;

        @BeforeEach
        void setUp() {
            logs.start();
            logger.addAppender(logs);
            when(repository.findAll()).thenReturn(stored);
            validator = new StoredPolicyValidator(repository, new PolicyDslParser(), registry);
        }

        @AfterEach
        void tearDown() {
            logger.detachAppender(logs);
        }

        private Policy store(String yaml, PolicyStatus status) {
            Policy p = new Policy();
            p.setId(UUID.randomUUID());
            p.setName("p-" + stored.size());
            p.setStatus(status);
            p.setPolicyDocument(yaml);
            stored.add(p);
            return p;
        }

        private List<String> warnings() {
            return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Test
        void aStoredLifecyclePolicyWithAnEffectIsReportedAndCountedUnderTheAccessPolicyCheck() {
            Policy enabled = store(LIFECYCLE_WITH_EFFECT, PolicyStatus.ENABLED);
            Policy disabled = store(LIFECYCLE_WITH_EFFECT.replace("deny", "allow"), PolicyStatus.DISABLED);
            store(LIFECYCLE_PLAIN, PolicyStatus.ENABLED);
            store(LIFECYCLE_NULL_EFFECT, PolicyStatus.ENABLED);
            store(ACCESS_DENY, PolicyStatus.ENABLED);

            StoredPolicyValidator.Report report = validator.validate();

            assertThat(report.invalidAccessPolicies()).containsExactly(enabled.getId(), disabled.getId());
            assertThat(registry.get(StoredPolicyValidator.GAUGE).tag("check", StoredPolicyValidator.CHECK_ACCESS_POLICY)
                    .gauge().value()).isEqualTo(2.0);
            assertThat(report.unreadable()).isEmpty();
            assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(enabled.getId().toString())
                    .contains("lifecycle Policy that states an effect").contains("the PDP ignores it"));
        }

        @Test
        void aBrokenLifecycleDocumentWithAnEffectIsReportedWithWhatThePdpDoesWithIt() {
            Policy broken = store("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: b}\nspec:\n  effect: deny\n",
                    PolicyStatus.ENABLED);

            StoredPolicyValidator.Report report = validator.validate();

            assertThat(report.invalidAccessPolicies()).containsExactly(broken.getId());
            assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(broken.getId().toString())
                    .contains("does not parse").contains("policy-store-invalid"));
        }
    }
}
