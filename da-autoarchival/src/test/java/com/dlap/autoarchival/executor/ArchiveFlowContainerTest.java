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

package com.dlap.autoarchival.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.autoarchival.approval.ApprovalVerifier;
import com.dlap.autoarchival.approval.ArchiveApproval;
import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.proof.ArchiveProofService;
import com.dlap.autoarchival.rule.ArchiveRuleProperties;
import com.dlap.autoarchival.rule.FakeRuleProvider;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.autoarchival.service.IArchivalConfigService;
import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditStreams;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;

/**
 * RET-03 against the real database, booted from the real init scripts exactly like the dev compose
 * (PostgreSQL 15.1). It proves two things the mocked tests cannot:
 * <ol>
 *   <li>Flyway V2 applies on top of V1, repairs the invalid default tier, and its constraints hold
 *       (one decision per approval, an {@code EXECUTED} row must name its evidence);</li>
 *   <li>the executor's audit records land in the hash chain of stream {@code retire.archive} through
 *       AUD-01's own writer, as the service's own database role, and the chain verifies.</li>
 * </ol>
 * Requires Docker.
 */
@Testcontainers
class ArchiveFlowContainerTest {

    private static final String SUPERUSER = "dlap";
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,"
            + "da_reporting,da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";
    private static final Set<String> EXECUTABLE_INIT_SCRIPTS = Set.of("01-create-single-database.sh");
    private static final String APPROVAL = "0d4f9c40-1a2b-3c4d-8e5f-000000000002";
    private static final String ASSET = "11111111-2222-3333-4444-555555555555";
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant EVENT_TIME = Instant.parse("2026-11-02T00:00:07Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = withInitScripts(
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                    .withDatabaseName("dlap")
                    .withUsername(SUPERUSER)
                    .withPassword(SUPERUSER)
                    .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES));

    // ---------------------------------------------------------------------------------------------
    // Flyway V2
    // ---------------------------------------------------------------------------------------------

    @Test
    void v2AppliesOnV1RepairsTheDefaultTierAndItsConstraintsHold() throws Exception {
        Flyway v1 = flyway("1");
        v1.clean();
        v1.migrate();
        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            s.execute("INSERT INTO dlap_autoarchival.dlap_archival_config (id, enabled, require_approval_for_archival,"
                    + " require_approval_for_restore, default_archival_tier) VALUES (1, false, true, true, 'STANDARD_ARCHIVE')");
            s.execute("INSERT INTO dlap_autoarchival.dlap_archival_config (id, enabled, require_approval_for_archival,"
                    + " require_approval_for_restore, default_archival_tier) VALUES (2, false, true, true, 'GLACIER')");
        }

        assertThat(flyway("2").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            assertThat(scalar(s, "SELECT default_archival_tier FROM dlap_autoarchival.dlap_archival_config WHERE id = 1"))
                    .isEqualTo("COLD");
            assertThat(scalar(s, "SELECT default_archival_tier FROM dlap_autoarchival.dlap_archival_config WHERE id = 2"))
                    .isEqualTo("GLACIER");
            s.execute("INSERT INTO dlap_autoarchival.dlap_archival_tasks (assets_archived_successfully,"
                    + " assets_failed_to_archive, assets_pending_archival, total_assets_in_scope, status, task_id)"
                    + " VALUES (0,0,1,1,'RULE_APPLIED','t-new')");
            for (String st : List.of("RULE_RENDERED", "EXECUTED", "REFUSED", "EXPIRED")) {
                s.execute("UPDATE dlap_autoarchival.dlap_archival_tasks SET status = '" + st + "' WHERE task_id = 't-new'");
            }
            assertThatThrownBy(() -> s.execute("UPDATE dlap_autoarchival.dlap_archival_tasks SET status = 'BOGUS'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("dlap_archival_tasks_status_check");
        }
    }

    @Test
    void v3AppliesOnV2KeepsExistingRowsAndAddsTheClaimStateTheProvenanceColumnAndTheLockTable() throws Exception {
        Flyway v2 = flyway("2");
        v2.clean();
        v2.migrate();
        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            s.execute(insert("old-1", "appr-old-1", "NULL", "APPLIED", "", ""));
            assertThatThrownBy(() -> s.execute(insert("old-2", "appr-old-2", "NULL", "EXECUTING", "", "")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_archive_rules_status");
        }

        assertThat(flyway("3").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            assertThat(scalar(s, "SELECT already_present FROM dlap_autoarchival.dlap_archive_rules"
                    + " WHERE rule_id = 'old-1'")).isEqualTo("f");
            s.execute(insert("new-1", "appr-new-1", "NULL", "EXECUTING", ", already_present", ", true"));
            assertThat(scalar(s, "SELECT already_present FROM dlap_autoarchival.dlap_archive_rules"
                    + " WHERE rule_id = 'new-1'")).isEqualTo("t");
            assertThat(scalar(s, "SELECT count(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'dlap_autoarchival' AND table_name = 'shedlock'")).isEqualTo("1");
            assertThatThrownBy(() -> s.execute(insert("bad", "appr-bad", "NULL", "BOGUS", "", "")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_archive_rules_status");
        }
        // idempotent: a second run has nothing to do
        assertThat(flyway("3").migrate().migrationsExecuted).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Flyway V4 (RET-09: Q-708, Q-710, Q-711)
    // ---------------------------------------------------------------------------------------------

    @Test
    void q708_v4TaskStatusCheckAcceptsRuleRemovedAndAlreadyHeldAndStillRejectsOthers() throws Exception {
        Flyway v3 = flyway("3");
        v3.clean();
        v3.migrate();
        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            s.execute(task("t-1", "RESTORED"));
            assertThatThrownBy(() -> s.execute("UPDATE dlap_autoarchival.dlap_archival_tasks SET status = 'RULE_REMOVED'"))
                    .as("before V4 the task status RULE_REMOVED does not exist")
                    .isInstanceOf(SQLException.class).hasMessageContaining("dlap_archival_tasks_status_check");
        }

        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            for (String st : List.of("RULE_REMOVED", "ALREADY_HELD", "RESTORED", "RULE_APPLIED", "REFUSED", "EXPIRED")) {
                s.execute("UPDATE dlap_autoarchival.dlap_archival_tasks SET status = '" + st + "' WHERE task_id = 't-1'");
            }
            assertThatThrownBy(() -> s.execute("UPDATE dlap_autoarchival.dlap_archival_tasks SET status = 'BOGUS'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("dlap_archival_tasks_status_check");
        }
    }

    @Test
    void q710_v4CreatesTheLiveScopeIndexOnCleanDataAndTheIndexOnlyCoversLiveRealGcsRules() throws Exception {
        Flyway v3 = flyway("3");
        v3.clean();
        v3.migrate();
        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            // the same scope, but not a second live real GCS rule: removed, failed, mocked, a direct object, other clouds
            s.execute(scoped("a-live", "appr-a", "GCP", "NATIVE_RULE", "APPLIED", "false"));
            s.execute(scoped("a-removed", "appr-b", "GCP", "NATIVE_RULE", "REMOVED", "false"));
            s.execute(scoped("a-failed", "appr-c", "GCP", "NATIVE_RULE", "FAILED", "false"));
            s.execute(scoped("a-mock", "appr-d", "GCP", "NATIVE_RULE", "APPLIED", "true"));
            s.execute(scoped("a-object", "appr-e", "GCP", "DIRECT_OBJECT", "APPLIED", "false"));
            s.execute(scoped("a-aws-1", "appr-f", "AWS", "NATIVE_RULE", "APPLIED", "false"));
            s.execute(scoped("a-aws-2", "appr-g", "AWS", "NATIVE_RULE", "APPLIED", "false"));
        }

        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            assertThat(scalar(s, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'dlap_autoarchival'"
                    + " AND indexname = 'ux_archive_rules_live_gcs_scope'")).isEqualTo("1");
            for (String live : List.of("PLANNED", "EXECUTING", "APPLIED")) {
                assertThatThrownBy(() -> s.execute(scoped("dup-" + live, "appr-dup-" + live, "GCP", "NATIVE_RULE", live,
                        "false"))).as("a second live real GCS rule on the scope, status " + live)
                        .isInstanceOf(SQLException.class).hasMessageContaining("ux_archive_rules_live_gcs_scope");
            }
            // once the holder is out of the live set, the scope is free again
            s.execute("UPDATE dlap_autoarchival.dlap_archive_rules SET status = 'REMOVED' WHERE rule_id = 'a-live'");
            s.execute(scoped("next-live", "appr-next", "GCP", "NATIVE_RULE", "PLANNED", "false"));
        }
        assertThat(flyway(null).migrate().migrationsExecuted).as("idempotent").isZero();
    }

    @Test
    void q710_v4StopsWithAMessageThatNamesTheDuplicateRowsAndChangesNothing() throws Exception {
        Flyway v3 = flyway("3");
        v3.clean();
        v3.migrate();
        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            s.execute(task("t-dup", "RESTORED"));
            s.execute(scoped("dup-first", "appr-1", "GCP", "NATIVE_RULE", "APPLIED", "false"));
            s.execute(scoped("dup-second", "appr-2", "GCP", "NATIVE_RULE", "EXECUTING", "false"));
        }

        assertThatThrownBy(() -> flyway(null).migrate()).hasMessageContaining("V4")
                .hasMessageContaining("dup-first (APPLIED)").hasMessageContaining("dup-second (EXECUTING)")
                .hasMessageContaining("bucket b").hasMessageContaining("COLDLINE");

        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            assertThat(scalar(s, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'dlap_autoarchival'"
                    + " AND indexname = 'ux_archive_rules_live_gcs_scope'")).as("no index was created").isEqualTo("0");
            assertThatThrownBy(() -> s.execute("UPDATE dlap_autoarchival.dlap_archival_tasks SET status = 'RULE_REMOVED'"))
                    .as("the task status check was not changed either")
                    .isInstanceOf(SQLException.class).hasMessageContaining("dlap_archival_tasks_status_check");
            assertThat(scalar(s, "SELECT count(*) FROM dlap_autoarchival.dlap_archive_rules")).isEqualTo("2");
        }
    }

    private static String task(String id, String status) {
        return "INSERT INTO dlap_autoarchival.dlap_archival_tasks (assets_archived_successfully,"
                + " assets_failed_to_archive, assets_pending_archival, total_assets_in_scope, status, task_id)"
                + " VALUES (0,0,1,1,'" + status + "','" + id + "')";
    }

    /** A rule row on the one scope bucket b, prefix p/, COLDLINE, 0 days. */
    private static String scoped(String id, String approval, String provider, String mode, String status,
            String mock) {
        return "INSERT INTO dlap_autoarchival.dlap_archive_rules (rule_id, approval_id, fire_id, task_id, asset_id,"
                + " asset_uri, provider, mode, scope_bucket, scope_prefix, native_class, after_days, status, mock,"
                + " created_at, updated_at) VALUES ('" + id + "', '" + approval + "', NULL, 't', 'a', 'gs://b/p/', '"
                + provider + "', '" + mode + "', 'b', 'p/', 'COLDLINE', 0, '" + status + "', " + mock
                + ", now(), now())";
    }

    @Test
    void theRuleTableHoldsOneDecisionPerApprovalAndAnExecutedRowMustNameItsEvidence() throws Exception {
        Flyway f = flyway(null);
        f.clean();
        f.migrate();
        try (Connection c = autoarchival(SUPERUSER); Statement s = c.createStatement()) {
            s.execute(insert("r1", "appr-1", "fire-1", "PLANNED", "", ""));
            assertThatThrownBy(() -> s.execute(insert("r2", "appr-1", "fire-2", "PLANNED", "", "")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ux_archive_rules_approval");
            assertThatThrownBy(() -> s.execute(insert("r3", "appr-3", "fire-1", "PLANNED", "", "")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ux_archive_rules_fire");
            // an API request has no fire id, and two of them are fine
            s.execute(insert("r4", "appr-4", "NULL", "PLANNED", "", ""));
            s.execute(insert("r5", "appr-5", "NULL", "PLANNED", "", ""));
            assertThatThrownBy(() -> s.execute(insert("r6", "appr-6", "NULL", "DONE", "", "")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_archive_rules_status");
            assertThatThrownBy(() -> s.execute(insert("r7", "appr-7", "NULL", "EXECUTED", "", "")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_archive_rules_executed_has_evidence");
            s.execute(insert("r8", "appr-8", "NULL", "EXECUTED", ", evidence_event_id, evidence_event_time, executed_at",
                    ", 'ev-1', now(), now()"));
            assertThatThrownBy(() -> s.execute("UPDATE dlap_autoarchival.dlap_archive_rules SET mode = 'MAGIC'"
                    + " WHERE rule_id = 'r1'")).isInstanceOf(SQLException.class).hasMessageContaining("ck_archive_rules_mode");
            assertThatThrownBy(() -> s.execute("UPDATE dlap_autoarchival.dlap_archive_rules SET after_days = -1"
                    + " WHERE rule_id = 'r1'")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_archive_rules_after_days");
        }
    }

    private static String insert(String id, String approval, String fire, String status, String extraCols,
            String extraVals) {
        String fireSql = "NULL".equals(fire) ? "NULL" : "'" + fire + "'";
        return "INSERT INTO dlap_autoarchival.dlap_archive_rules (rule_id, approval_id, fire_id, task_id, asset_id,"
                + " asset_uri, provider, mode, scope_bucket, scope_prefix, native_class, after_days, status,"
                + " created_at, updated_at" + extraCols + ") VALUES ('" + id + "', '" + approval + "', " + fireSql
                + ", 't', 'a', 'gs://b/p/', 'GCP', 'NATIVE_RULE', 'b', 'p/" + id + "/', 'COLDLINE', 0, '" + status
                + "', now(), now()" + extraVals + ")";
    }

    // ---------------------------------------------------------------------------------------------
    // The audit chain
    // ---------------------------------------------------------------------------------------------

    @Test
    void anApprovedArchiveLeavesAVerifiedChainOfRequestedAppliedAndExecutedRecords() throws Exception {
        Map<String, ArchiveRuleEntity> store = new HashMap<>();
        ArchiveRuleRepository rules = InMemoryRules.over(store);
        FakeRuleProvider gcp = new FakeRuleProvider("GCP", true);
        ArchiveAudit audit = auditAs("da_autoarchival_user", "da_autoarchival_pass");
        ApprovalVerifier verifier = mock(ApprovalVerifier.class);
        when(verifier.verifyById(APPROVAL, ASSET)).thenReturn(
                new ArchiveApproval(APPROVAL, "APPROVED", "ARCHIVE", 1, List.of("alice-sub", "bob-sub"), false,
                        "gs://pilot/finance/", "GCP", Map.of("tier", "COLD")));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ArchiveExecutor executor = new ArchiveExecutor(new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules),
                rules, verifier, audit, clock, List.of(), defaultColdConfig());

        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of("tier", "COLD"), null, APPROVAL, "fire-1"));
        ArchiveRuleEntity applied = executor.execute(APPROVAL, AuditActor.service("da-autoarchival"));
        assertThat(applied.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);

        // the provider's own event arrives later and is the only thing that executes the row
        ArchiveProofService proof = new ArchiveProofService(rules, mock(ArchiveTaskSync.class), audit, clock,
                new ArchiveRuleProperties());
        assertThat(proof.onEvent(DlapLogEvent.newBuilder().setEventId("ev-9").setOperation("storage.objects.update")
                .setSourceProvider(SourceProvider.GCP).setResultStatus(ResultStatus.SUCCESS)
                .setOrigin(Origin.ORIGIN_CLOUD_LOG).setEventTime(Timestamp.newBuilder()
                        .setSeconds(EVENT_TIME.getEpochSecond()))
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri("gs://pilot/finance/q1.csv")).build()))
                .isZero(); // an audit-log update is not how GCS reports a lifecycle rule firing
        assertThat(applied.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        ArchiveRuleEntity direct = new ArchiveRuleEntity("dlap-archive-direct0000", "appr-direct", null, "task-2",
                "asset-2", "gs://pilot/finance/q2.csv", "GCP", ArchiveRuleEntity.MODE_DIRECT_OBJECT, "pilot",
                "finance/q2.csv", "COLDLINE", 0, NOW);
        direct.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, NOW);
        rules.save(direct);
        assertThat(proof.onEvent(DlapLogEvent.newBuilder().setEventId("ev-10").setOperation("storage.objects.update")
                .setSourceProvider(SourceProvider.GCP).setResultStatus(ResultStatus.SUCCESS)
                .setOrigin(Origin.ORIGIN_CLOUD_LOG).setEventTime(Timestamp.newBuilder()
                        .setSeconds(EVENT_TIME.getEpochSecond()))
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri("gs://pilot/finance/q2.csv")).build()))
                .isEqualTo(1);

        List<AuditEntry> entries = new AuditLogReader(as(SUPERUSER, SUPERUSER)).page(AuditStreams.RETIRE_ARCHIVE, 0, 100);
        List<JsonNode> records = entries.stream().map(e -> parse(e.record())).toList();
        List<String> types = records.stream().map(r -> r.path("eventType").asText()).toList();
        assertThat(types).containsSubsequence("retire.archive.requested", "retire.archive.rule_applied");
        assertThat(types).contains("retire.archive.executed");
        assertThat(types.indexOf("retire.archive.requested")).isLessThan(types.indexOf("retire.archive.rule_applied"));

        JsonNode requested = records.get(types.indexOf("retire.archive.requested"));
        assertThat(requested.path("outcome").asText()).isEqualTo("REQUESTED");
        assertThat(requested.path("correlationId").asText()).isEqualTo(APPROVAL);
        assertThat(requested.path("actor").path("kind").asText()).isEqualTo("SERVICE");
        assertThat(requested.path("target").path("kind").asText()).isEqualTo("ASSET");
        assertThat(requested.path("target").path("id").asText()).isEqualTo("gs://pilot/finance/");
        assertThat(requested.path("occurredAt").asText()).isEqualTo("2026-11-01T10:00:00.000000Z");
        JsonNode details = requested.path("details");
        assertThat(details.path("approvalId").asText()).isEqualTo(APPROVAL);
        assertThat(details.path("approvers").get(0).asText()).isEqualTo("alice-sub");
        assertThat(details.path("approvers")).hasSize(2);
        assertThat(details.path("nativeClass").asText()).isEqualTo("COLDLINE");
        assertThat(details.path("fireId").asText()).isEqualTo("fire-1");
        assertThat(requested.toString()).doesNotContain("password").doesNotContain("token");

        JsonNode executed = records.get(types.indexOf("retire.archive.executed"));
        assertThat(executed.path("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(executed.path("occurredAt").asText()).isEqualTo("2026-11-02T00:00:07.000000Z");
        assertThat(executed.path("details").path("evidenceEventId").asText()).isEqualTo("ev-10");

        assertThat(new AuditChainVerifier(8).verify(AuditStreams.RETIRE_ARCHIVE,
                new AuditLogReader(as(SUPERUSER, SUPERUSER)), List.of()).isValid()).isTrue();
    }

    @Test
    void ifTheAuditDatabaseRefusesTheWriterTheArchiveDoesNotRun() throws Exception {
        Map<String, ArchiveRuleEntity> store = new HashMap<>();
        ArchiveRuleRepository rules = InMemoryRules.over(store);
        FakeRuleProvider gcp = new FakeRuleProvider("GCP", true);
        // a database role that is not the stream's writer: the insert trigger refuses it
        ArchiveAudit audit = auditAs("da_reporting_user", "da_reporting_pass");
        ApprovalVerifier verifier = mock(ApprovalVerifier.class);
        when(verifier.verifyById(APPROVAL, ASSET)).thenReturn(
                new ArchiveApproval(APPROVAL, "APPROVED", "ARCHIVE", 1, List.of("alice-sub"), false,
                        "gs://pilot/finance/", "GCP", Map.of()));
        ArchiveExecutor executor = new ArchiveExecutor(new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules),
                rules, verifier, audit, Clock.fixed(NOW, ZoneOffset.UTC), List.of(), defaultColdConfig());
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP", Map.of(), null,
                APPROVAL, null));
        gcp.calls.clear();

        ArchiveRuleEntity row = executor.execute(APPROVAL, AuditActor.service("da-autoarchival"));

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_FAILED);
        assertThat(row.getLastError()).contains("nothing was changed");
        assertThat(gcp.calls).noneMatch(c -> c.startsWith("apply:"));
        assertThat(gcp.rules).isEmpty();
    }

    @Test
    void aRefusalIsAChainedDeniedRecordAndARepeatedRefusalIsTheSameRecord() throws Exception {
        ArchiveAudit audit = auditAs("da_autoarchival_user", "da_autoarchival_pass");
        AuditLogReader reader = new AuditLogReader(as(SUPERUSER, SUPERUSER));
        int before = reader.page(AuditStreams.RETIRE_ARCHIVE, 0, 1000).size();

        AuditEntry first = audit.refused("fire-denied-1", "s3://pilot/finance/", "NO_APPROVAL",
                "the archive action carries no approval", null, null);
        AuditEntry again = audit.refused("fire-denied-1", "s3://pilot/finance/", "NO_APPROVAL",
                "the archive action carries no approval", null, null);

        assertThat(again).isEqualTo(first);
        assertThat(reader.page(AuditStreams.RETIRE_ARCHIVE, 0, 1000)).hasSize(before + 1);
        JsonNode rec = parse(first.record());
        assertThat(rec.path("outcome").asText()).isEqualTo("DENIED");
        assertThat(rec.path("eventType").asText()).isEqualTo("retire.archive.refused");
        assertThat(rec.path("details").path("reason").asText()).isEqualTo("NO_APPROVAL");
    }

    @Test
    void q711_aScopeAlreadyHeldIsOneSucceededRecordAndEveryLaterFireAddsNothing() throws Exception {
        ArchiveAudit audit = auditAs("da_autoarchival_user", "da_autoarchival_pass");
        AuditLogReader reader = new AuditLogReader(as(SUPERUSER, SUPERUSER));
        Instant created = Instant.parse("2026-10-01T09:00:00.123456789Z");
        ArchiveRuleEntity holder = new ArchiveRuleEntity("dlap-archive-held00000001", "appr-held-1", "fire-h1", "task-h1",
                "asset-held", "gs://pilot/held/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "held/",
                "COLDLINE", 0, created);
        holder.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, created);
        int before = reader.page(AuditStreams.RETIRE_ARCHIVE, 0, 1000).size();

        AuditEntry first = audit.alreadyHeld("gs://pilot/held/", "asset-held", holder);
        // later fires for the same scope: another clock, and the holding row is now EXECUTED
        ArchiveAudit later = new ArchiveAudit(new StaticListableBeanFactory(Map.of("writer",
                new AuditLogWriter(as("da_autoarchival_user", "da_autoarchival_pass"),
                        Clock.fixed(NOW.plusSeconds(86_400), ZoneOffset.UTC)))).getBeanProvider(AuditLogWriter.class),
                Clock.fixed(NOW.plusSeconds(86_400), ZoneOffset.UTC));
        holder.markExecuted("ev-held", "storage.objects.update", EVENT_TIME, "PROVIDER_LOG", EVENT_TIME);
        AuditEntry second = later.alreadyHeld("gs://pilot/held/", "asset-held", holder);
        AuditEntry third = later.alreadyHeld("gs://pilot/held/", "asset-held", holder);

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(reader.page(AuditStreams.RETIRE_ARCHIVE, 0, 1000)).as("one record, not three")
                .hasSize(before + 1);
        JsonNode rec = parse(first.record());
        assertThat(rec.path("eventType").asText()).isEqualTo("retire.archive.already_held");
        assertThat(rec.path("outcome").asText()).as("not a refusal").isEqualTo("SUCCEEDED");
        assertThat(rec.path("correlationId").asText()).isEqualTo("appr-held-1");
        assertThat(rec.path("target").path("id").asText()).isEqualTo("gs://pilot/held/");
        assertThat(rec.path("details").path("holdingRuleId").asText()).isEqualTo("dlap-archive-held00000001");
        assertThat(rec.path("details").path("nativeClass").asText()).isEqualTo("COLDLINE");
        assertThat(rec.path("occurredAt").asText()).as("the time the scope began to be held")
                .startsWith("2026-10-01T09:00:00.123");

        // another holding row, or another asset on the same row, is its own record
        ArchiveRuleEntity other = new ArchiveRuleEntity("dlap-archive-held00000002", "appr-held-2", null, "task-h2",
                "asset-held", "gs://pilot/held2/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "held2/",
                "COLDLINE", 0, created);
        audit.alreadyHeld("gs://pilot/held2/", "asset-held", other);
        audit.alreadyHeld("gs://pilot/held/", "asset-other", holder);
        assertThat(reader.page(AuditStreams.RETIRE_ARCHIVE, 0, 1000)).hasSize(before + 3);
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private static ArchiveAudit auditAs(String role, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/dlap_common", role, password);
        AuditLogWriter writer = new AuditLogWriter(ds, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("writer", writer));
        return new ArchiveAudit(beans.getBeanProvider(AuditLogWriter.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DataSource as(String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/dlap_common");
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static Flyway flyway(String target) {
        var cfg = Flyway.configure().dataSource(autoarchivalUrl(), SUPERUSER, SUPERUSER)
                .schemas("dlap_autoarchival").locations("filesystem:" + migrations().toString().replace('\\', '/'))
                .cleanDisabled(false);
        if (target != null) {
            cfg.target(target);
        }
        Flyway f = cfg.load();
        return f;
    }

    private static String autoarchivalUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/da_autoarchival";
    }

    private static Connection autoarchival(String user) throws SQLException {
        String password = SUPERUSER.equals(user) ? SUPERUSER : user.replaceAll("_user$", "_pass");
        return DriverManager.getConnection(autoarchivalUrl(), user, password);
    }

    private static String scalar(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static JsonNode parse(String record) {
        try {
            return JSON.readTree(record);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path migrations() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("da-autoarchival/src/main/resources/db/migration");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            Path inModule = dir.resolve("src/main/resources/db/migration");
            if (Files.isRegularFile(inModule.resolve("V1__init_autoarchival_schema.sql"))) {
                return inModule;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("da-autoarchival migrations not found");
    }

    private static Path scripts() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deployment/platform/database");
            if (Files.isRegularFile(candidate.resolve("init-scripts/02-create-common-schema.sql"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("deployment/platform/database not found");
    }

    private static PostgreSQLContainer<?> withInitScripts(PostgreSQLContainer<?> container) {
        try (Stream<Path> files = Files.list(scripts().resolve("init-scripts"))) {
            for (Path script : files.sorted().toList()) {
                String name = script.getFileName().toString();
                int mode = EXECUTABLE_INIT_SCRIPTS.contains(name) ? 0755 : 0644;
                container.withCopyFileToContainer(MountableFile.forHostPath(script, mode),
                        "/docker-entrypoint-initdb.d/" + name);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot list init scripts", e);
        }
        return container;
    }

    private static IArchivalConfigService defaultColdConfig() {
        IArchivalConfigService c = org.mockito.Mockito.mock(IArchivalConfigService.class);
        org.mockito.Mockito.when(c.getArchivalConfig())
                .thenReturn(ArchivalConfigDTO.builder().defaultArchivalTier("COLD").build());
        return c;
    }
}
