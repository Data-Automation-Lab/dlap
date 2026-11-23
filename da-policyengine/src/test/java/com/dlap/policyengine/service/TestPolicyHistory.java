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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.policyengine.approval.TestAudits;
import com.dlap.policyengine.model.PolicyVersion;
import com.dlap.policyengine.repository.PolicyRepository;
import com.dlap.policyengine.repository.PolicyVersionRepository;

import jakarta.persistence.EntityManager;

/**
 * A {@link PolicyHistoryService} for unit tests of {@link PolicyService}: an in-memory version store,
 * an audit writer that accepts every entry (the events land in {@link #events}) and a fixed clock.
 */
public final class TestPolicyHistory {

    /** The clock's instant. */
    public static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    /** The events appended to the audit log. */
    public final List<AuditEvent> events = Collections.synchronizedList(new ArrayList<>());

    /** The version rows saved. */
    public final List<PolicyVersion> rows = Collections.synchronizedList(new ArrayList<>());

    /** The version store. */
    public final PolicyVersionRepository versions = mock(PolicyVersionRepository.class);

    /** The audit writer. */
    public final AuditLogWriter writer = TestAudits.acceptingWriter(events);

    /** The history. */
    public final PolicyHistoryService history;

    /**
     * Wires the history over a policy repository.
     *
     * @param policies the policy repository the service under test uses
     */
    public TestPolicyHistory(PolicyRepository policies) {
        when(versions.saveAndFlush(any(PolicyVersion.class))).thenAnswer(i -> {
            PolicyVersion v = i.getArgument(0);
            rows.add(v);
            return v;
        });
        when(versions.findByPolicyIdAndVersion(any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i ->
                rows.stream().filter(v -> v.getPolicyId().equals(i.getArgument(0))
                        && v.getVersion() == (int) i.getArgument(1)).findFirst());
        history = new PolicyHistoryService(versions, policies, TestAudits.governance(writer),
                mock(EntityManager.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * The newest version saved, if any.
     *
     * @return the version
     */
    public Optional<PolicyVersion> last() {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(rows.size() - 1));
    }
}
