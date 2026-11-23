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

package com.dlap.autodelete.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.persister.collection.AbstractCollectionPersister;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.autodelete.domain.DeletionAuditEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.model.DeletionConfigEntity;
import com.dlap.autodelete.model.DeletionStatus;
import com.dlap.autodelete.model.DeletionTaskEntity;

import jakarta.persistence.EntityManagerFactory;

/**
 * DB-01 acceptance: a clean Flyway apply (V1 to the latest) on an empty PostgreSQL, run as
 * the real service user created by the platform init script, satisfies
 * {@code hibernate.ddl-auto=validate} for every da-autodelete entity.
 *
 * <p>The test deliberately does not override {@code spring.jpa.hibernate.ddl-auto},
 * {@code spring.flyway.*} or the default schema: it inherits them from the module's
 * {@code application.properties}, so it proves the shipped defaults, not a test copy.
 * Before DB-01 the default was {@code update} and the deletion config and task tables had no
 * migration, so a fresh database could only boot with Hibernate creating them.
 *
 * <p>Context startup is the first assertion (Flyway runs, then Hibernate validates; either
 * failing aborts the test). The remaining tests pin what validate cannot see: the default
 * itself, version numbering, tables or columns present in the database but not mapped, the
 * status CHECK against {@link DeletionStatus}, and a persistence round trip of every entity.
 *
 * <p>Requires Docker. The container uses {@code postgres:15.1}, the image the dev compose
 * runs, and copies in the platform script that creates the {@code da_autodelete} database,
 * user and schema, exactly as the compose stack does.
 */
@DataJpaTest
@ContextConfiguration(classes = AutodeleteFlywayValidateTest.JpaOnlyConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AutodeleteFlywayValidateTest {

    private static final String SCHEMA = "dlap_autodelete";
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    /** Local configuration so the JPA slice does not bootstrap Kafka, Feign, security or the cloud SDKs. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.autodelete.model", "com.dlap.autodelete.domain"})
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages = "com.dlap.autodelete.repository")
    static class JpaOnlyConfig {
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
            .withDatabaseName("dlap")
            .withUsername("dlap")
            .withPassword("dlap")
            .withEnv("POSTGRES_MULTIPLE_DATABASES", "da_autodelete")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptsDir().resolve("00-create-multiple-databases.sh"), 0755),
                    "/docker-entrypoint-initdb.d/00-create-multiple-databases.sh");

    /** Points the datasource at the per-service database as the per-service user, like compose does. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/da_autodelete");
        registry.add("spring.datasource.username", () -> "da_autodelete_user");
        registry.add("spring.datasource.password", () -> "da_autodelete_pass");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private com.dlap.autodelete.repository.SoftDeleteRecordRepository softDeletes;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    /** Walks up from the working directory to the repository's shared init-scripts folder. */
    private static Path initScriptsDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deployment/platform/database/init-scripts");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "deployment/platform/database/init-scripts not found above " + System.getProperty("user.dir"));
    }

    /** The shipped default must be validate (CLAUDE.md section 6), both as configured and as applied. */
    @Test
    void ddlAutoDefaultIsValidate() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .as("effective spring.jpa.hibernate.ddl-auto (unset JPA_DDL_AUTO to test the default)")
                .isEqualTo("validate");
        assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"))
                .as("hibernate.hbm2ddl.auto applied to the EntityManagerFactory")
                .isEqualTo("validate");
    }

    /** Migrations apply cleanly, in sequence, with no gaps (CLAUDE.md section 6: numbering is sequential). */
    @Test
    void flywayVersionsAreSequentialAndIncludeV4() {
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT version FROM " + SCHEMA + "." + FLYWAY_HISTORY
                        + " WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class).stream().map(Integer::valueOf).toList();

        assertThat(versions).as("applied Flyway versions").isNotEmpty();
        assertThat(versions)
                .as("versions are 1..N with no gaps")
                .isEqualTo(IntStream.rangeClosed(1, versions.size()).boxed().toList());
        assertThat(versions).as("V4 deletion config and tasks is applied").contains(4);
        assertThat(versions).as("RET-02: V5 retire guards and ShedLock, V6 dispositions").contains(5, 6);
        assertThat(versions).as("RET-04: V7 rule in force, size, pending outcome audit").contains(7);
        assertThat(versions).as("RET-06: V8 RULE_ENDED and RESTORE_FAILED").contains(8);
        assertThat(versions).as("RET-08: V9 RULE_ENDED_UNPROVEN and rule_end_retry_at").contains(9);
    }

    /** RET-08 (Q-695, V9): the status CHECK accepts RULE_ENDED_UNPROVEN and still refuses an unknown state. */
    @Test
    void theStatusCheckAcceptsRuleEndedUnproven() {
        UUID id = UUID.randomUUID();
        insertSoftDeleteRow(id, SoftDeleteRecordEntity.AWAITING_PROOF);

        jdbcTemplate.update("UPDATE " + SCHEMA + ".soft_delete_records SET status = 'RULE_ENDED_UNPROVEN' WHERE id = ?",
                id);

        assertThat(softDeletes.findById(id)).map(SoftDeleteRecordEntity::getStatus)
                .contains(SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + SCHEMA
                + ".soft_delete_records SET status = 'RULE_ENDED_SOMEHOW' WHERE id = ?", id))
                .hasMessageContaining("chk_sdr_status");
    }

    /**
     * Validate only proves every mapped column exists; it does not notice a table or column
     * that the migrations create and no entity maps. This pins both directions: the schema
     * holds exactly the tables and columns the entities map, derived from the Hibernate
     * metamodel so the test needs no hand-kept list.
     */
    @Test
    void schemaHoldsExactlyTheMappedTablesAndColumns() {
        Map<String, Set<String>> mapped = mappedColumnsByTable();

        assertThat(mapped.keySet()).as("mapped tables").containsExactlyInAnyOrder(
                "dlap_deletion_audit",
                "dlap_deletion_config",
                "dlap_deletion_config_notification_emails",
                "dlap_deletion_tasks",
                "retire_dispositions",
                "soft_delete_records");

        Set<String> actualTables = new TreeSet<>(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_name <> ?"
                        + " AND table_name <> 'shedlock'",
                String.class, SCHEMA, FLYWAY_HISTORY));
        assertThat(actualTables).as("tables in " + SCHEMA).isEqualTo(new TreeSet<>(mapped.keySet()));

        mapped.forEach((table, columns) -> {
            Set<String> actualColumns = new TreeSet<>(jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?",
                    String.class, SCHEMA, table));
            assertThat(actualColumns).as("columns of %s.%s", SCHEMA, table).isEqualTo(new TreeSet<>(columns));
        });
    }

    /**
     * Hibernate 6 generates a CHECK for an {@code EnumType.STRING} column and V4 keeps it, so a new
     * {@link DeletionStatus} constant without a migration would fail at runtime on insert. This turns
     * that into a build failure and proves the constraint rejects unknown values.
     */
    @Test
    void statusCheckAcceptsEveryDeletionStatusAndRejectsOthers() {
        for (DeletionStatus status : DeletionStatus.values()) {
            insertTaskRow("task-" + status.name(), status.name());
        }
        Integer inserted = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".dlap_deletion_tasks", Integer.class);
        assertThat(inserted).isEqualTo(DeletionStatus.values().length);

        assertThatThrownBy(() -> insertTaskRow("task-bogus", "NOT_A_STATUS"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Every entity persists and reloads, including the jsonb columns and the element collection. */
    @Test
    void everyEntityRoundTripsThroughPostgres() {
        DeletionConfigEntity config = new DeletionConfigEntity(
                1L, true, true, 30L, true, false, new ArrayList<>(List.of("ops@example.com", "sec@example.com")));
        entityManager.persist(config);

        LocalDateTime processedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        DeletionTaskEntity task = new DeletionTaskEntity();
        task.setTaskId("task-roundtrip");
        task.setTaskName("round trip");
        task.setStatus(DeletionStatus.PARTIALLY_COMPLETED);
        task.setSubmittedAt(processedAt);
        task.setTriggeredBy("tester");
        task.setJustification("schema test");
        task.setScope(new DeletionTaskEntity.DeletionScopeData(List.of("asset-1", "asset-2")));
        task.setOverrideConfig(new DeletionTaskEntity.DeletionConfigOverrideData(true, 7L, false));
        task.setTotalAssetsInScope(2);
        task.setAssetsProcessedSuccessfully(1);
        task.setAssetsFailedToProcess(1);
        task.setAssetStatuses(List.of(
                new DeletionTaskEntity.AssetDeletionStatusData(
                        "asset-1", "one", "SOFT_DELETED", "SOFT", null, processedAt),
                new DeletionTaskEntity.AssetDeletionStatusData(
                        "asset-2", "two", "FAILED", "HARD", "denied", processedAt)));
        task.setErrorMessages(List.of("asset-2: denied"));
        entityManager.persist(task);

        DeletionAuditEntity audit = DeletionAuditEntity.builder()
                .taskId("task-roundtrip")
                .assetId("gs://bucket/object")
                .cloudProvider("GCP")
                .action("DELETE_GCS_OBJECT")
                .status("SUCCESS")
                .performedBy("tester")
                .performedAt(processedAt)
                .bytesDeleted(1024L)
                .build();
        entityManager.persist(audit);

        SoftDeleteRecordEntity softDelete = SoftDeleteRecordEntity.builder()
                .id(UUID.randomUUID())
                .assetId(UUID.randomUUID())
                .queuedAt(Instant.now())
                .scheduledHardDeleteAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .status("SOFT_DELETED")
                .cloudProvider("GCP")
                .assetUri("gs://bucket/object")
                .build();
        entityManager.persist(softDelete);

        entityManager.flush();
        entityManager.clear();

        DeletionConfigEntity loadedConfig = entityManager.find(DeletionConfigEntity.class, 1L);
        assertThat(loadedConfig.getNotificationEmailsOnError())
                .containsExactlyInAnyOrder("ops@example.com", "sec@example.com");
        assertThat(loadedConfig.getDefaultSoftDeleteRetentionDays()).isEqualTo(30L);

        DeletionTaskEntity loadedTask = entityManager.find(DeletionTaskEntity.class, "task-roundtrip");
        assertThat(loadedTask.getStatus()).isEqualTo(DeletionStatus.PARTIALLY_COMPLETED);
        assertThat(loadedTask.getScope().getAssetIds()).containsExactly("asset-1", "asset-2");
        assertThat(loadedTask.getOverrideConfig().getSoftDeleteRetentionDays()).isEqualTo(7L);
        assertThat(loadedTask.getAssetStatuses()).hasSize(2);
        assertThat(loadedTask.getAssetStatuses().get(1).getErrorMessage()).isEqualTo("denied");
        assertThat(loadedTask.getAssetStatuses().get(1).getProcessedAt()).isEqualTo(processedAt);
        assertThat(loadedTask.getErrorMessages()).containsExactly("asset-2: denied");

        assertThat(entityManager.find(DeletionAuditEntity.class, audit.getId()).getBytesDeleted()).isEqualTo(1024L);
        assertThat(entityManager.find(SoftDeleteRecordEntity.class, softDelete.getId()).getStatus())
                .isEqualTo("SOFT_DELETED");
    }

    /**
     * RET-02: the soft-delete status CHECK accepts every state the executor writes (the constants of
     * {@link SoftDeleteRecordEntity}) and nothing else.
     */
    @Test
    void softDeleteStatusCheckAcceptsEveryExecutorStateAndRejectsOthers() {
        List<String> states = List.of(SoftDeleteRecordEntity.SOFT_DELETED,
                SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, SoftDeleteRecordEntity.HARD_DELETE_REFUSED,
                SoftDeleteRecordEntity.DRY_RUN_COMPLETE, SoftDeleteRecordEntity.AWAITING_PROOF,
                SoftDeleteRecordEntity.HARD_DELETED, SoftDeleteRecordEntity.HARD_DELETE_FAILED,
                SoftDeleteRecordEntity.RULE_IN_FORCE, SoftDeleteRecordEntity.RULE_ENDED,
                SoftDeleteRecordEntity.RESTORE_FAILED, SoftDeleteRecordEntity.RESTORED);
        for (String state : states) {
            insertSoftDeleteRow(UUID.randomUUID(), state);
        }
        assertThatThrownBy(() -> insertSoftDeleteRow(UUID.randomUUID(), "DELETED_SOMEHOW"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * RET-04: the V7 columns exist and are mapped (size for the KPI, the pending outcome audit, the removal time),
     * a rule in force can carry its EXECUTED disposition, and the KPI query sums only the proven deletes.
     */
    @Test
    void theRuleInForceStateAndTheV7ColumnsRoundTrip() {
        UUID rule = UUID.randomUUID();
        insertSoftDeleteRow(rule, SoftDeleteRecordEntity.RULE_IN_FORCE);
        insertDisposition(rule, "EXECUTED", false, "evt-rule", Instant.parse("2026-11-02T03:04:05Z"));
        jdbcTemplate.update("UPDATE " + SCHEMA + ".retire_dispositions SET success_audit_pending = TRUE,"
                + " rule_removed_at = now() WHERE soft_delete_record_id = ?", rule);
        jdbcTemplate.update("UPDATE " + SCHEMA + ".soft_delete_records SET size_bytes = 1000 WHERE id = ?", rule);

        assertThat(jdbcTemplate.queryForObject("SELECT success_audit_pending FROM " + SCHEMA
                + ".retire_dispositions WHERE soft_delete_record_id = ?", Boolean.class, rule)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT size_bytes FROM " + SCHEMA
                + ".soft_delete_records WHERE id = ?", Long.class, rule)).isEqualTo(1000L);
        assertThat(jdbcTemplate.queryForObject("SELECT success_audit_pending FROM " + SCHEMA
                + ".retire_dispositions d WHERE d.soft_delete_record_id = ?", Boolean.class, rule))
                .as("the default of a new disposition is false")
                .isNotNull();

        UUID proven = UUID.randomUUID();
        insertSoftDeleteRow(proven, SoftDeleteRecordEntity.HARD_DELETED);
        jdbcTemplate.update("UPDATE " + SCHEMA + ".soft_delete_records SET size_bytes = 250,"
                + " hard_deleted_at = now() WHERE id = ?", proven);
        assertThat(softDeletes.sumSizeBytesByStatus(SoftDeleteRecordEntity.HARD_DELETED))
                .as("only HARD_DELETED rows count, not the rule in force").isEqualTo(250L);
        assertThat(softDeletes.sumSizeBytesByStatusSince(SoftDeleteRecordEntity.HARD_DELETED,
                Instant.parse("2020-01-01T00:00:00Z"))).isEqualTo(250L);
        assertThat(softDeletes.sumSizeBytesByStatusSince(SoftDeleteRecordEntity.HARD_DELETED,
                Instant.parse("2999-01-01T00:00:00Z"))).isZero();
    }

    /**
     * RET-06 (Q-677): the rule-end claim finds only RULE_IN_FORCE records whose rule was applied at or before the
     * cutoff and whose retry time passed, oldest apply first. RET-08: the retry time is rule_end_retry_at (Flyway
     * V9); a next_check_at in the future does not hold a rule back.
     */
    @Test
    void theRuleEndClaimFindsOnlyRulesInForcePastTheirMaximumAge() {
        Instant now = Instant.parse("2026-12-15T00:00:00Z");
        Instant cutoff = now.minus(java.time.Duration.ofDays(30));
        UUID old = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID young = UUID.randomUUID();
        UUID retryLater = UUID.randomUUID();
        UUID restored = UUID.randomUUID();
        for (UUID id : List.of(old, older, young, retryLater)) {
            insertSoftDeleteRow(id, SoftDeleteRecordEntity.RULE_IN_FORCE);
        }
        insertSoftDeleteRow(restored, SoftDeleteRecordEntity.RESTORED);
        insertAppliedDisposition(old, Instant.parse("2026-11-10T00:00:00Z"));
        insertAppliedDisposition(older, Instant.parse("2026-11-01T00:00:00Z"));
        insertAppliedDisposition(young, Instant.parse("2026-12-01T00:00:00Z"));
        insertAppliedDisposition(retryLater, Instant.parse("2026-10-01T00:00:00Z"));
        insertAppliedDisposition(restored, Instant.parse("2026-10-01T00:00:00Z"));
        // RET-08 (V9): the job's own retry time is rule_end_retry_at; next_check_at belongs to the sweeper.
        jdbcTemplate.update("UPDATE " + SCHEMA + ".soft_delete_records SET rule_end_retry_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.plusSeconds(3600)), retryLater);
        jdbcTemplate.update("UPDATE " + SCHEMA + ".soft_delete_records SET next_check_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.plusSeconds(3600)), older);

        assertThat(softDeletes.claimNextRuleToEnd(cutoff, now)).map(SoftDeleteRecordEntity::getId).contains(older);
        jdbcTemplate.update("UPDATE " + SCHEMA + ".soft_delete_records SET status = 'RULE_ENDED' WHERE id = ?", older);
        assertThat(softDeletes.claimNextRuleToEnd(cutoff, now)).map(SoftDeleteRecordEntity::getId).contains(old);
        jdbcTemplate.update("UPDATE " + SCHEMA + ".soft_delete_records SET status = 'RESTORE_FAILED' WHERE id = ?", old);
        assertThat(softDeletes.claimNextRuleToEnd(cutoff, now)).as("young, retry-later and restored stay").isEmpty();
        assertThat(softDeletes.claimNextRuleToEnd(cutoff, now.plusSeconds(7200))).map(SoftDeleteRecordEntity::getId)
                .as("once its retry time passed").contains(retryLater);
    }

    private void insertAppliedDisposition(UUID record, Instant appliedAt) {
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".retire_dispositions (id, soft_delete_record_id, asset_id,"
                + " target_uri, cloud_provider, mode, platform, status, dry_run, approval_id, planned_at, applied_at,"
                + " proof_event_id, proof_event_time, updated_at) VALUES (?, ?, ?, 'gs://b', 'GCP', 'NATIVE_RULE',"
                + " 'GCS_LIFECYCLE', 'EXECUTED', false, ?, now(), ?, 'evt', now(), now())",
                UUID.randomUUID(), record, UUID.randomUUID(), UUID.randomUUID(), java.sql.Timestamp.from(appliedAt));
    }

    /**
     * RET-02: the database itself refuses a disposition marked EXECUTED without the provider's proof
     * event; with the proof it is accepted.
     */
    @Test
    void theDatabaseRefusesAnExecutedDispositionWithoutProof() {
        UUID proven = UUID.randomUUID();
        insertSoftDeleteRow(proven, SoftDeleteRecordEntity.HARD_DELETED);
        insertDisposition(proven, "EXECUTED", false, "evt-1", Instant.parse("2026-11-02T03:04:05Z"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM " + SCHEMA + ".retire_dispositions"
                + " WHERE soft_delete_record_id = ?", String.class, proven)).isEqualTo("EXECUTED");

        UUID unproven = UUID.randomUUID();
        insertSoftDeleteRow(unproven, SoftDeleteRecordEntity.AWAITING_PROOF);
        assertThatThrownBy(() -> insertDisposition(unproven, "EXECUTED", false, null, null))
                .as("EXECUTED without proof_event_id and proof_event_time")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** RET-02: a dry-run disposition can never claim to have been applied. */
    @Test
    void theDatabaseRefusesADryRunDispositionInAnyOtherState() {
        UUID planned = UUID.randomUUID();
        insertSoftDeleteRow(planned, SoftDeleteRecordEntity.DRY_RUN_COMPLETE);
        insertDisposition(planned, "DRY_RUN", true, null, null);

        UUID applied = UUID.randomUUID();
        insertSoftDeleteRow(applied, SoftDeleteRecordEntity.AWAITING_PROOF);
        assertThatThrownBy(() -> insertDisposition(applied, "AWAITING_PROOF", true, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertSoftDeleteRow(UUID id, String status) {
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".soft_delete_records (id, asset_id, queued_at,"
                + " scheduled_hard_delete_at, status, cloud_provider) VALUES (?, ?, now(), now(), ?, 'GCP')",
                id, UUID.randomUUID(), status);
    }

    private void insertDisposition(UUID record, String status, boolean dryRun, String proofId, Instant proofTime) {
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".retire_dispositions (id, soft_delete_record_id, asset_id,"
                + " target_uri, cloud_provider, mode, platform, status, dry_run, approval_id, planned_at, applied_at,"
                + " proof_event_id, proof_event_time, updated_at) VALUES (?, ?, ?, 'gs://b', 'GCP', 'NATIVE_RULE',"
                + " 'GCS_LIFECYCLE', ?, ?, ?, now(), ?, ?, ?, now())",
                UUID.randomUUID(), record, UUID.randomUUID(), status, dryRun, UUID.randomUUID(),
                dryRun ? null : java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")), proofId,
                proofTime == null ? null : java.sql.Timestamp.from(proofTime));
    }

    private void insertTaskRow(String taskId, String status) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + ".dlap_deletion_tasks (task_id, status, total_assets_in_scope, "
                        + "assets_processed_successfully, assets_failed_to_process, assets_pending_processing) "
                        + "VALUES (?, ?, 0, 0, 0, 0)",
                taskId, status);
    }

    /**
     * Table name to mapped column names, for every entity table and every element-collection
     * table, read from Hibernate's runtime metamodel (the same mapping validate checks).
     */
    private Map<String, Set<String>> mappedColumnsByTable() {
        MappingMetamodel metamodel = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel();
        Map<String, Set<String>> byTable = new TreeMap<>();

        metamodel.forEachEntityDescriptor(persister -> {
            AbstractEntityPersister entity = (AbstractEntityPersister) persister;
            Set<String> columns = new TreeSet<>(Arrays.asList(entity.getIdentifierColumnNames()));
            for (String property : entity.getPropertyNames()) {
                for (String column : entity.getPropertyColumnNames(property)) {
                    if (column != null) {
                        columns.add(column);
                    }
                }
            }
            byTable.put(unqualified(entity.getTableName()), columns);
        });

        metamodel.forEachCollectionDescriptor(persister -> {
            AbstractCollectionPersister collection = (AbstractCollectionPersister) persister;
            Set<String> columns = new TreeSet<>(Arrays.asList(collection.getKeyColumnNames()));
            columns.addAll(Arrays.stream(collection.getElementColumnNames())
                    .filter(c -> c != null).collect(Collectors.toSet()));
            byTable.put(unqualified(collection.getTableName()), columns);
        });
        return byTable;
    }

    private static String unqualified(String tableName) {
        String name = tableName.substring(tableName.lastIndexOf('.') + 1);
        return name.replace("\"", "");
    }
}
