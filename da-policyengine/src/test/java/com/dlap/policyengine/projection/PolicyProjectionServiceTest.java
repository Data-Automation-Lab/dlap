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

package com.dlap.policyengine.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

/**
 * {@link PolicyProjectionService} drives the sink from ENABLED AccessPolicy
 * documents (ADR-0040 push-down mode): an AccessPolicy row is compiled and
 * handed to the sink; lifecycle-kind and unparseable rows are skipped; sink
 * failures never escape.
 */
@ExtendWith(MockitoExtension.class)
class PolicyProjectionServiceTest {

    @Mock
    private PolicyRepository policyRepository;

    private PolicyProjectionService service;
    private RecordingSink sink;

    private static final String ACCESS_YAML = ""
            + "apiVersion: dlap/v1\n"
            + "kind: AccessPolicy\n"
            + "metadata: {id: access-eu, name: eu}\n"
            + "spec:\n"
            + "  subjects: {roles: [DATA_ANALYST]}\n"
            + "  actions: [read]\n"
            + "  match: {labels: {anyOf: [EU_RESIDENT]}}\n"
            + "  effect: allow\n";

    private static final String LIFECYCLE_YAML = ""
            + "apiVersion: dlap/v1\n"
            + "kind: Policy\n"
            + "metadata: {id: archive, name: archive}\n"
            + "spec:\n"
            + "  triggers: [{type: event, lifecycle: created}]\n"
            + "  match: {labels: {anyOf: [PII]}}\n"
            + "  action: {type: archive}\n";

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        service = new PolicyProjectionService(
                policyRepository,
                new PolicyDslParser(),
                new AccessPolicyToGrantTranslator(),
                new ProjectionSecurableResolver(),
                sink);
    }

    private Policy policy(String doc) {
        Policy p = new Policy();
        p.setName("n-" + doc.hashCode());
        p.setStatus(PolicyStatus.ENABLED);
        p.setPolicyDocument(doc);
        return p;
    }

    @Test
    void accessPolicy_isCompiledAndProjectedToSink() {
        when(policyRepository.findByStatus(PolicyStatus.ENABLED))
                .thenReturn(List.of(policy(ACCESS_YAML)));

        int projected = service.projectAll();

        assertThat(projected).isEqualTo(1);
        assertThat(sink.projected).hasSize(1);
        CompiledAccessPolicy compiled = sink.projected.get(0);
        assertThat(compiled.policyId()).isEqualTo("access-eu");
        // The label selector resolved to a label-securable and produced a grant.
        assertThat(compiled.intents()).isNotEmpty();
        assertThat(compiled.intents().get(0).securable())
                .isEqualTo(ProjectionSecurableResolver.LABEL_SECURABLE_PREFIX + "EU_RESIDENT");
        assertThat(compiled.intents().get(0).grant()).isTrue();
    }

    @Test
    void aCommittedPolicyChange_reprojectsTheEnabledAccessPolicies() {
        when(policyRepository.findByStatus(PolicyStatus.ENABLED))
                .thenReturn(List.of(policy(ACCESS_YAML)));

        service.onPolicyChanged(new com.dlap.policyengine.access.PolicyChangedEvent("update"));

        assertThat(sink.projected).hasSize(1);
        assertThat(sink.projected.get(0).policyId()).isEqualTo("access-eu");
    }

    @Test
    void lifecyclePolicy_isSkipped() {
        when(policyRepository.findByStatus(PolicyStatus.ENABLED))
                .thenReturn(List.of(policy(LIFECYCLE_YAML)));

        int projected = service.projectAll();

        assertThat(projected).isZero();
        assertThat(sink.projected).isEmpty();
    }

    @Test
    void unparseablePolicy_isSkippedNotFatal() {
        Policy bad = policy("this: is: not: valid: yaml: kind:");
        when(policyRepository.findByStatus(PolicyStatus.ENABLED)).thenReturn(List.of(bad));

        int projected = service.projectAll();

        assertThat(projected).isZero();
        assertThat(sink.projected).isEmpty();
    }

    @Test
    void mixedSet_projectsOnlyAccessPolicies() {
        when(policyRepository.findByStatus(PolicyStatus.ENABLED))
                .thenReturn(List.of(policy(ACCESS_YAML), policy(LIFECYCLE_YAML)));

        int projected = service.projectAll();

        assertThat(projected).isEqualTo(1);
        assertThat(sink.projected).hasSize(1);
    }

    @Test
    void sinkFailure_doesNotEscape() {
        service = new PolicyProjectionService(
                policyRepository, new PolicyDslParser(),
                new AccessPolicyToGrantTranslator(), new ProjectionSecurableResolver(),
                new ThrowingSink());
        when(policyRepository.findByStatus(PolicyStatus.ENABLED))
                .thenReturn(List.of(policy(ACCESS_YAML)));

        // projectOne swallows the sink exception -> projected count 0, no throw.
        int projected = service.projectAll();

        assertThat(projected).isZero();
    }

    /** Test double capturing what was projected. */
    private static final class RecordingSink implements PolicyProjectionSink {
        final List<CompiledAccessPolicy> projected = new ArrayList<>();

        @Override
        public void project(CompiledAccessPolicy compiled) {
            projected.add(compiled);
        }

        @Override
        public void revoke(CompiledAccessPolicy compiled) {
            // not exercised here
        }
    }

    private static final class ThrowingSink implements PolicyProjectionSink {
        @Override
        public void project(CompiledAccessPolicy compiled) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void revoke(CompiledAccessPolicy compiled) {
            throw new IllegalStateException("boom");
        }
    }
}
