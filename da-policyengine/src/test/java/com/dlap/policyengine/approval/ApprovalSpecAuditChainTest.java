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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.common.audit.AuditStreams;

/**
 * PE-01 (Q-716): docs/specs/approvals.md v1.2 states the audit chain of decisions, break-glass and
 * withdrawals as the code does: the event types, the idempotency keys, the stream, the 503 on decisions
 * and break-glass, the replay rule, the orphan entry and the closed gaps. Needs no Docker.
 */
class ApprovalSpecAuditChainTest {

    private static final UUID ID = UUID.fromString("0d4f9c40-1a2b-3c4d-8e5f-000000000002");

    private static String spec() throws IOException {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/specs/approvals.md");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        }
        return fail("docs/specs/approvals.md not found in " + start + " or any parent directory");
    }

    private static String lineStarting(String spec, String prefix) {
        return spec.lines().filter(l -> l.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no line starts with " + prefix));
    }

    @Test
    void theSpecIsVersion12AndTheChangelogNamesTheAuditChain() throws IOException {
        String spec = spec();
        assertThat(spec).contains("Spec version: 1.2 |");
        String row = lineStarting(spec, "| 1.2 |");
        assertThat(row).contains("govern.approval.decided", "govern.approval.break_glass", "<approvalId>:decision:<sub>");
        assertThat(spec).contains("| 1.1 |", "| 1.0 |");
    }

    @Test
    void theEventTypesStreamAndKeysOfTheSpecAreTheCodes() throws IOException {
        String spec = spec();
        assertThat(spec).contains("`" + ApprovalAudit.DECIDED_EVENT + "`", "`" + ApprovalAudit.BREAK_GLASS_EVENT + "`",
                "`" + ApprovalAudit.WITHDRAWN_EVENT + "`", "`" + AuditStreams.GOVERN_APPROVAL + "`");
        assertThat(ApprovalAudit.decisionKey(ID, "sub-alice")).isEqualTo(ID + ":decision:sub-alice");
        assertThat(spec).contains("`<approvalId>:decision:<sub>`");
        assertThat(ApprovalAudit.withdrawalKey(ID)).isEqualTo(ID + ":withdrawn");
        assertThat(spec).contains("`<approvalId>:withdrawn`");
        String hashed = ApprovalAudit.decisionKey(ID, "x".repeat(65));
        assertThat(hashed).startsWith(ID + ":decision:sha256-");
        assertThat(hashed.substring((ID + ":decision:sha256-").length())).hasSize(32);
        assertThat(spec).contains("`sha256-`", "first 32 hex characters");
    }

    @Test
    void decisionsAndBreakGlassAnswer503AuditUnavailableAsTheSpecSays() throws IOException {
        String spec = spec();
        assertThat(ApprovalException.AUDIT_UNAVAILABLE).isEqualTo("audit-unavailable");
        assertThat(lineStarting(spec, "| `POST /{id}/decisions`")).contains("503 `audit-unavailable`");
        assertThat(lineStarting(spec, "| `POST /{id}/break-glass`")).contains("503 `audit-unavailable`");
        assertThat(lineStarting(spec, "| `POST /{id}/withdraw`")).contains("503 `audit-unavailable`");
        assertThat(spec).contains("a decision, a break-glass release or a\nwithdrawal");
    }

    @Test
    void theReplayRuleAndTheOrphanEntryAreStated() throws IOException {
        String spec = spec();
        assertThat(spec).contains("### 8.1 Audit log entries", "**Replay rule.**", "**Orphan entry.**",
                "`occurredAt`", "`decisionId`", "`commentSha256`", "`reasonSha256`", "`409 not-pending`", "Q-717");
    }

    @Test
    void theGapsTheAuditChainClosedAreNoLongerListedAsOpen() throws IOException {
        String spec = spec();
        assertThat(spec).doesNotContain("Decisions are not in the audit chain yet", "Policy authors are not recorded yet",
                "Executors do not verify yet");
        assertThat(spec).contains("Decisions are in the audit chain (closed, AUD-02a, ADR-0112)",
                "Policy authors are recorded (closed, AUD-02a)");
    }

    @Test
    void theSpecHoldsNoEmDashOrEnDash() throws IOException {
        assertThat(spec()).doesNotContain("\u2014").doesNotContain("\u2013");
    }
}
