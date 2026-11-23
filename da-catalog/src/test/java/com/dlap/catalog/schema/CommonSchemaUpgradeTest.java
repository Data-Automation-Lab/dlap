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

package com.dlap.catalog.schema;

import static com.dlap.catalog.schema.UpgradeRunnerSupport.checksum;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.ledger;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.ledgerWithTimes;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.run;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.schemaSnapshot;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.shippedDeltas;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.sql;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.sqlAs;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.tableExists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;

import com.dlap.catalog.schema.UpgradeRunnerSupport.Run;
import com.dlap.common.model.entity.Asset;

import jakarta.persistence.EntityManagerFactory;

/**
 * DB-02 acceptance (ADR-0056): a {@code dlap_common} database built from the previous release's
 * init scripts is brought to the current schema by {@code run-upgrades.sh} alone, and then
 * passes {@code hibernate.ddl-auto=validate}.
 *
 * <p>The container is created from the six init scripts of sdg 7984d1d (test resources
 * {@code db/init-scripts-7984d1d}, copied verbatim from {@code git show 7984d1d:...} except that
 * em-dash and en-dash characters in comments became hyphens). That is the schema a deployed
 * database has today, before ADR-0046. The real runner and the real deltas from
 * {@code deployment/platform/database/upgrades} then run inside the container, and the Spring
 * context starts afterwards, so Hibernate validates the upgraded database and not a fresh one.
 *
 * <p>A second container built from the current init scripts is the reference: the upgraded
 * schema must equal it, the runner must leave it unchanged, and re-running script 02 must not
 * open the ledger to a service role. Move the baseline to the then-previous release when a
 * release is cut.
 *
 * <p>Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = CommonSchemaUpgradeTest.JpaOnlyConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CommonSchemaUpgradeTest {

    private static final String DB = "dlap_common";
    private static final String DELTA_DIR = "/upgrades/real";

    /** Local configuration so the JPA slice does not bootstrap Kafka, Feign, Redis or security. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.catalog.model", "com.dlap.common.model.entity"})
    static class JpaOnlyConfig {
    }

    /** The previous release's database, upgraded by the runner before the Spring context starts. */
    static final PostgreSQLContainer<?> POSTGRES = UpgradeRunnerSupport.baselinePostgres();

    /** Schema fingerprint of the previous release, taken before the runner touched it. */
    static final List<String> SCHEMA_BEFORE;

    /** Rows of dlap_assets in the previous release (the 03 seed), before the upgrade. */
    static final String ASSETS_BEFORE;

    /** The first runner pass over the previous release's database. */
    static final Run FIRST_RUN;

    /** The ledger after that first pass. */
    static final List<String> LEDGER_AFTER_FIRST_RUN;

    static {
        POSTGRES.start();
        UpgradeRunnerSupport.installRunner(POSTGRES);
        UpgradeRunnerSupport.installShippedDeltas(POSTGRES, DELTA_DIR);
        SCHEMA_BEFORE = schemaSnapshot(POSTGRES, DB);
        ASSETS_BEFORE = sql(POSTGRES, DB, "SELECT count(*) FROM dlap_common.dlap_assets");
        FIRST_RUN = run(POSTGRES, DB, DELTA_DIR);
        if (FIRST_RUN.exitCode() != 0) {
            throw new IllegalStateException("the runner failed on the previous release's database:\n"
                    + FIRST_RUN.transcript());
        }
        LEDGER_AFTER_FIRST_RUN = ledger(POSTGRES, DB);
    }

    /** The reference database, started on first use: the current init scripts, as compose builds it. */
    private static final class Fresh {
        static final PostgreSQLContainer<?> POSTGRES = UpgradeRunnerSupport.currentPostgres();

        /** Fingerprint straight after the init scripts, before any test ran the runner against it. */
        static final List<String> SCHEMA_AT_START;

        static {
            POSTGRES.start();
            UpgradeRunnerSupport.installRunner(POSTGRES);
            UpgradeRunnerSupport.installShippedDeltas(POSTGRES, DELTA_DIR);
            SCHEMA_AT_START = schemaSnapshot(POSTGRES, DB);
        }
    }

    /** Same connection compose gives da-catalog: the shared dlap_common database, as the catalog user. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + DB);
        registry.add("spring.datasource.username", () -> "da_catalog_user");
        registry.add("spring.datasource.password", () -> "da_catalog_pass");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    // ---- the previous release really is the previous release --------------------------------

    /** Guards the test against passing vacuously: the baseline must lack what the deltas add. */
    @Test
    void thePreviousReleaseLacksWhatTheDeltasAdd() {
        assertThat(SCHEMA_BEFORE).anyMatch(l -> l.startsWith("col dlap_assets.asset_uri "));
        assertThat(SCHEMA_BEFORE).noneMatch(l -> l.startsWith("col dlap_assets.size_bytes "));
        assertThat(SCHEMA_BEFORE).noneMatch(l -> l.startsWith("col dlap_assets.retention_status "));
        assertThat(SCHEMA_BEFORE).noneMatch(l -> l.contains("schema_upgrades"));
        assertThat(SCHEMA_BEFORE)
                .filteredOn(l -> l.contains("ck_asset_cloud_provider_valid"))
                .hasSize(1)
                .allMatch(l -> !l.contains("DATABRICKS"));
    }

    // ---- the runner brings it forward -------------------------------------------------------

    @Test
    void firstRunAppliesEveryShippedDeltaInOrderAndRecordsItsChecksum() {
        List<Path> deltas = shippedDeltas();
        assertThat(deltas).as("shipped deltas").hasSizeGreaterThanOrEqualTo(7);

        assertThat(FIRST_RUN.applied()).as(FIRST_RUN.transcript()).isEqualTo(deltas.size());
        assertThat(FIRST_RUN.skipped()).isZero();
        assertThat(LEDGER_AFTER_FIRST_RUN).containsExactlyElementsOf(
                deltas.stream().map(p -> p.getFileName() + "|" + checksum(p)).collect(Collectors.toList()));
        assertThat(sql(POSTGRES, DB, "SELECT count(*) FROM dlap_common.schema_upgrades "
                + "WHERE applied_at IS NOT NULL AND applied_by = 'dlap'")).isEqualTo(String.valueOf(deltas.size()));
    }

    @Test
    void theUpgradeAddsTheColumnsAndKeepsTheExistingRows() {
        assertThat(sql(POSTGRES, DB, "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'dlap_common' AND table_name = 'dlap_assets' "
                + "AND column_name IN ('size_bytes', 'cloud_created_at', 'last_accessed_at', 'retention_status')"))
                .isEqualTo("4");

        assertThat(ASSETS_BEFORE).as("the 03 seed loaded into the old schema").isNotEqualTo("0");
        assertThat(sql(POSTGRES, DB, "SELECT count(*) FROM dlap_common.dlap_assets")).isEqualTo(ASSETS_BEFORE);
        assertThat(sql(POSTGRES, DB, "SELECT count(*) FROM dlap_common.dlap_assets "
                + "WHERE retention_status = 'UNKNOWN' AND legal_hold = false AND size_bytes IS NULL"))
                .as("existing rows read as unknown and not held").isEqualTo(ASSETS_BEFORE);
    }

    @Test
    void secondRunAppliesNothingAndChangesNothing() {
        String ledgerBefore = ledgerWithTimes(POSTGRES, DB);
        List<String> schemaBefore = schemaSnapshot(POSTGRES, DB);

        Run second = run(POSTGRES, DB, DELTA_DIR);

        assertThat(second.exitCode()).as(second.transcript()).isZero();
        assertThat(second.applied()).as(second.transcript()).isZero();
        assertThat(second.skipped()).isEqualTo(shippedDeltas().size());
        assertThat(second.out()).doesNotContain("applying");
        assertThat(ledgerWithTimes(POSTGRES, DB)).isEqualTo(ledgerBefore);
        assertThat(schemaSnapshot(POSTGRES, DB)).isEqualTo(schemaBefore);
    }

    @Test
    void aModifiedAppliedDeltaFailsLoudlyAndNothingNewIsApplied() throws Exception {
        String tampered = "/upgrades/tampered";
        String victim = shippedDeltas().get(1).getFileName().toString();
        POSTGRES.execInContainer("sh", "-c", "rm -rf " + tampered + " && cp -r " + DELTA_DIR + " " + tampered
                + " && echo '-- edited after it was applied' >> " + tampered + "/" + victim
                + " && echo 'CREATE TABLE dlap_common.must_not_exist (id int);' > " + tampered
                + "/2099-01-01_pending.sql");
        List<String> ledgerBefore = ledger(POSTGRES, DB);

        Run refused = run(POSTGRES, DB, tampered);

        assertThat(refused.exitCode()).as(refused.transcript()).isEqualTo(2);
        assertThat(refused.err()).contains("REFUSED").contains(victim).contains("changed since it was applied");
        assertThat(tableExists(POSTGRES, DB, "must_not_exist")).isFalse();
        assertThat(ledger(POSTGRES, DB)).isEqualTo(ledgerBefore);
    }

    // ---- the result is what Hibernate expects -----------------------------------------------

    @Test
    void hibernateValidateAcceptsTheUpgradedDatabase() {
        // Context startup already ran validate against the upgraded database; make that explicit.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
        assertThat(entityManagerFactory.getMetamodel().getEntities()).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    void theUpgradedDatabaseAcceptsWhatOnlyTheNewSchemaAllows() {
        Asset asset = new Asset("dbx://upgrade-test/" + UUID.randomUUID() + "/raw", "DATABRICKS", "UNITY_VOLUME",
                "raw");
        asset.setSizeBytes(42L);
        entityManager.persist(asset);
        entityManager.flush();
        entityManager.clear();

        Asset loaded = entityManager.find(Asset.class, asset.getAssetId());
        assertThat(loaded.getCloudProvider()).isEqualTo("DATABRICKS");
        assertThat(loaded.getServiceType()).isEqualTo("UNITY_VOLUME");
        assertThat(loaded.getSizeBytes()).isEqualTo(42L);
    }

    // ---- the upgraded schema is the schema a new install gets -------------------------------

    @Test
    void theUpgradedSchemaEqualsAFreshInstallOfTheCurrentScripts() {
        // Fresh.SCHEMA_AT_START predates any runner pass, so the ledger in it comes from script 02.
        assertThat(schemaSnapshot(POSTGRES, DB)).containsExactlyElementsOf(Fresh.SCHEMA_AT_START);
        assertThat(Fresh.SCHEMA_AT_START).anyMatch(l -> l.startsWith("col schema_upgrades.checksum_sha256 "));
    }

    @Test
    void theRunnerLeavesAFreshInstallUnchangedAndRecordsTheDeltas() {
        PostgreSQLContainer<?> fresh = Fresh.POSTGRES;
        List<String> before = schemaSnapshot(fresh, DB);
        List<Path> deltas = shippedDeltas();

        Run first = run(fresh, DB, DELTA_DIR);
        assertThat(first.exitCode()).as(first.transcript()).isZero();
        assertThat(ledger(fresh, DB)).extracting(row -> row.split("\\|")[0])
                .containsExactlyElementsOf(deltas.stream().map(p -> p.getFileName().toString()).toList());
        assertThat(schemaSnapshot(fresh, DB)).as("deltas are no-ops on a current schema").isEqualTo(before);

        Run second = run(fresh, DB, DELTA_DIR);
        assertThat(second.applied()).as(second.transcript()).isZero();
    }

    // ---- the ledger belongs to the database owner -------------------------------------------

    @Test
    void serviceRolesCannotReadTheLedgerCreatedByTheRunner() {
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dlap_common.schema_upgrades", Integer.class))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void rerunningScript02DoesNotOpenTheLedgerToServiceRoles() throws Exception {
        PostgreSQLContainer<?> fresh = Fresh.POSTGRES;
        String read = "SELECT count(*) FROM dlap_common.schema_upgrades";
        assertThat(sqlAs(fresh, "da_catalog_user", DB, read).getStderr()).contains("permission denied");

        // 02 grants DML on every table in the schema, the ledger included, before it revokes.
        ExecResult rerun = fresh.execInContainer("psql", "-U", "dlap", "-d", "dlap", "-X", "-q",
                "-v", "ON_ERROR_STOP=1", "-f", "/docker-entrypoint-initdb.d/02-create-common-schema.sql");
        assertThat(rerun.getExitCode()).as(rerun.getStderr()).isZero();

        for (String role : List.of("da_catalog_user", "da_discovery_user", "da_policyengine_user",
                "da_logingest_user", "da_admin_user")) {
            ExecResult denied = sqlAs(fresh, role, DB, read);
            assertThat(denied.getExitCode()).as("%s must not read the ledger", role).isNotZero();
            assertThat(denied.getStderr()).contains("permission denied");
        }
    }
}
