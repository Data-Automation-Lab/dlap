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

package com.dlap.policyengine.approval;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditLogWriter;

/**
 * Audit fixtures for tests that exercise approvals or policy changes without the audit database. Since
 * AUD-02a every decision and every policy change is appended to the audit log and fails closed without
 * a writer, so those tests need one that accepts.
 */
public final class TestAudits {

    private TestAudits() {
    }

    /**
     * A writer that accepts every append and remembers the events.
     *
     * @param events where the appended events are added
     * @return the writer
     */
    public static AuditLogWriter acceptingWriter(List<AuditEvent> events) {
        AuditLogWriter writer = mock(AuditLogWriter.class);
        when(writer.append(any(), any())).thenAnswer(i -> {
            events.add(i.getArgument(1));
            return new AuditEntry(i.getArgument(0), events.size(), "0".repeat(64), "1".repeat(64), "{}");
        });
        return writer;
    }

    /**
     * The policy engine's audit access over a writer, without a replay lookup.
     *
     * @param writer the writer
     * @return the audit access
     */
    public static GovernanceAudit governance(AuditLogWriter writer) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("auditLogWriter", writer);
        return new GovernanceAudit(beans.getBeanProvider(AuditLogWriter.class));
    }

    /**
     * An approval audit whose writer accepts every entry.
     *
     * @return the audit
     */
    public static ApprovalAudit accepting() {
        return new ApprovalAudit(governance(acceptingWriter(Collections.synchronizedList(new ArrayList<>()))));
    }

    /**
     * An in-memory audit chain with the writer's idempotency rule and a lookup by key, for replay tests.
     * {@link #loseNextCommit()} makes the next append store its entry and then fail with a non-audit
     * error, as if the entry committed and the caller's own transaction did not.
     */
    public static final class FakeChain {

        /** Entries by idempotency key, in append order. */
        public final java.util.Map<String, AuditEntry> byKey =
                Collections.synchronizedMap(new java.util.LinkedHashMap<>());

        /** Every append call, replays included. */
        public final List<AuditEvent> appends = Collections.synchronizedList(new ArrayList<>());

        /** The writer. */
        public final AuditLogWriter writer = mock(AuditLogWriter.class);

        /** The lookup. */
        public final AuditKeyLookup lookup = mock(AuditKeyLookup.class);

        private final java.time.Instant recordedAt;
        private volatile boolean loseNext;

        /**
         * Creates the chain.
         *
         * @param recordedAt the writer clock's instant
         */
        public FakeChain(java.time.Instant recordedAt) {
            this.recordedAt = recordedAt;
            when(writer.append(any(), any())).thenAnswer(i -> {
                String stream = i.getArgument(0);
                AuditEvent event = i.getArgument(1);
                appends.add(event);
                String record = com.dlap.common.audit.AuditChain.record(event, this.recordedAt);
                AuditEntry existing = event.idempotencyKey() == null ? null : byKey.get(event.idempotencyKey());
                if (existing != null) {
                    if (!existing.record().equals(record)) {
                        throw new IllegalArgumentException("idempotency key " + event.idempotencyKey()
                                + " already names another event");
                    }
                    return existing;
                }
                AuditEntry entry = new AuditEntry(stream, byKey.size() + 1L, "0".repeat(64),
                        com.dlap.common.audit.AuditChain.sha256Hex(record), record);
                byKey.put(event.idempotencyKey() == null ? "seq-" + entry.seq() : event.idempotencyKey(), entry);
                if (loseNext) {
                    loseNext = false;
                    throw new IllegalStateException("simulated: the audit entry committed, the change did not");
                }
                return entry;
            });
            when(lookup.find(any(), any())).thenAnswer(i -> java.util.Optional.ofNullable(byKey.get(i.getArgument(1))));
        }

        /** Makes the next append store its entry and then fail as a lost commit. */
        public void loseNextCommit() {
            this.loseNext = true;
        }

        /**
         * The policy engine's audit access over this chain, with replay.
         *
         * @return the audit access
         */
        public GovernanceAudit governance() {
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("auditLogWriter", writer);
            beans.addBean("auditKeyLookup", lookup);
            return new GovernanceAudit(beans.getBeanProvider(AuditLogWriter.class),
                    beans.getBeanProvider(AuditKeyLookup.class));
        }
    }
}
