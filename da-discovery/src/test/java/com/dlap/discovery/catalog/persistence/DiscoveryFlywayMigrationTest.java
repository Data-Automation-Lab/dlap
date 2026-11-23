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

package com.dlap.discovery.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * N3 / R-P0-4 acceptance: the consolidated Flyway series (V1 baseline +
 * V2 seeds) clean-applies on an empty PostgreSQL and the resulting schema
 * satisfies {@code hibernate.ddl-auto=validate} for every entity under
 * {@code com.dlap.discovery}. Context startup IS the assertion: Flyway
 * runs the migrations, then Hibernate validates the schema; either
 * failing aborts the test.
 *
 * <p>Uses a local {@code @SpringBootConfiguration} so the JPA slice
 * doesn't bootstrap the full application (GCP clients, Feign, caches).
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.schemas=dlap_discovery",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=dlap_discovery"
})
@ContextConfiguration(classes = DiscoveryFlywayMigrationTest.JpaOnlyConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DiscoveryFlywayMigrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.dlap.discovery")
    static class JpaOnlyConfig {
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCleanApply_createsSchemaAndSeeds() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'dlap_discovery'",
                Integer.class);
        // 35 entity/collection tables + flyway_schema_history + shedlock (V10)
        assertThat(tables).isGreaterThanOrEqualTo(37);

        Integer authorities = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dlap_discovery.jhi_authority", Integer.class);
        assertThat(authorities).isEqualTo(2);

        Integer logSources = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dlap_discovery.gcp_log_source_details WHERE account_id = 'default'",
                Integer.class);
        assertThat(logSources).isEqualTo(3);
    }

    /**
     * V9 regression: the four processing-job entities (ADR-0041 / C7) shipped without
     * their JOINED-inheritance child tables, so {@code ddl-auto=validate} aborted boot
     * on a fresh database with {@code missing table [aws_processing_job_resource]}.
     *
     * <p>Context startup already re-proves that via Hibernate validation, but these
     * assertions pin the shape explicitly: every mapped column is present, the id is
     * both primary key and foreign key, and each subtype points at the parent its
     * entity actually extends. A future entity-only change fails here with a readable
     * message instead of an opaque validation abort.
     */
    @Test
    void v9_createsProcessingJobChildTables_withColumnsAndParentForeignKeys() {
        assertJobChildTable("aws_processing_job_resource", "aws_job", "aws_resource");
        assertJobChildTable("azure_processing_job_resource", "azure_job", "azure_resource");
        assertJobChildTable("databricks_processing_job_resource", "databricks_job", "cloud_resource");
        // GcpBigQueryJobResource names its columns bq_job_* and carries an engine
        // discriminator where the others carry a source discriminator.
        assertJobChildTable("gcp_bigquery_job_resource", "bq_job", "gcp_resource");
    }

    /**
     * Asserts that a processing-job child table exists with its mapped columns, an id
     * primary key, and a foreign key onto the expected parent table.
     *
     * @param table        the child table name
     * @param columnPrefix prefix shared by the subtype columns
     * @param parentTable  the JOINED-inheritance parent this subtype extends
     */
    private void assertJobChildTable(String table, String columnPrefix, String parentTable) {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'dlap_discovery' AND table_name = ? ORDER BY column_name",
                String.class, table);
        // aws/azure/databricks carry a *_source discriminator; gcp carries *_engine.
        String discriminator = "bq_job".equals(columnPrefix) ? "_engine" : "_source";
        assertThat(columns)
                .as("mapped columns of %s", table)
                .containsExactlyInAnyOrder("id",
                        columnPrefix + discriminator,
                        columnPrefix + "_type",
                        columnPrefix + "_state",
                        columnPrefix + "_query",
                        columnPrefix + "_output");

        // inputTables is @Transient on all four entities, so it must not be persisted.
        assertThat(columns).as("%s must not persist the transient inputTables", table)
                .noneMatch(c -> c.contains("input"));

        List<String> primaryKey = jdbcTemplate.queryForList(
                "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON tc.constraint_name = kcu.constraint_name "
                        + " AND tc.table_schema = kcu.table_schema "
                        + "WHERE tc.constraint_type = 'PRIMARY KEY' "
                        + "  AND tc.table_schema = 'dlap_discovery' AND tc.table_name = ?",
                String.class, table);
        assertThat(primaryKey).as("primary key of %s", table).containsExactly("id");

        List<String> parents = jdbcTemplate.queryForList(
                "SELECT ccu.table_name FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.constraint_column_usage ccu "
                        + "  ON tc.constraint_name = ccu.constraint_name "
                        + " AND tc.table_schema = ccu.table_schema "
                        + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                        + "  AND tc.table_schema = 'dlap_discovery' AND tc.table_name = ?",
                String.class, table);
        assertThat(parents).as("JOINED parent of %s", table).containsExactly(parentTable);
    }

    /**
     * V10 (DISC-01): the ShedLock table exists in the service schema with exactly the columns
     * {@code JdbcTemplateLockProvider} reads and writes, keyed on the lock name. It is not an
     * entity, so {@code ddl-auto=validate} neither needs nor sees it; this pins that the
     * migration ships what the lock provider expects.
     */
    @Test
    void v10_createsTheShedLockTable_withTheColumnsAndKeyShedLockExpects() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'dlap_discovery' AND table_name = 'shedlock' ORDER BY column_name",
                String.class);
        assertThat(columns).containsExactly("lock_until", "locked_at", "locked_by", "name");

        List<String> primaryKey = jdbcTemplate.queryForList(
                "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON tc.constraint_name = kcu.constraint_name "
                        + " AND tc.table_schema = kcu.table_schema "
                        + "WHERE tc.constraint_type = 'PRIMARY KEY' "
                        + "  AND tc.table_schema = 'dlap_discovery' AND tc.table_name = 'shedlock'",
                String.class);
        assertThat(primaryKey).containsExactly("name");

        // The lock provider addresses the table as <schema>.shedlock and uses the database
        // clock: take a lock the way it does, to prove the table accepts its statements.
        jdbcTemplate.update("INSERT INTO dlap_discovery.shedlock(name, lock_until, locked_at, locked_by) "
                + "VALUES ('v10-probe', timezone('utc', now()) + interval '1 minute', timezone('utc', now()), 'test')");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dlap_discovery.shedlock WHERE name = 'v10-probe'", Integer.class)).isEqualTo(1);
        jdbcTemplate.update("DELETE FROM dlap_discovery.shedlock WHERE name = 'v10-probe'");
    }

    /**
     * DATABRICKS must already be an accepted {@code cloud_provider} value, or a
     * Databricks job row cannot be inserted and a Databricks discovery job cannot be
     * created. V1 shipped it and V7/V8 carried it forward while widening for UNITY, so
     * V9 needs no CHECK-constraint change. This pins that assumption.
     */
    @Test
    void databricksIsAnAcceptedCloudProviderOnEveryConstrainedTable() {
        for (String table : List.of("cloud_resource", "discovery_job", "resource_change")) {
            String checks = String.join(" ", jdbcTemplate.queryForList(
                    "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                            + "WHERE c.contype = 'c' AND n.nspname = 'dlap_discovery' "
                            + "  AND t.relname = ? AND pg_get_constraintdef(c.oid) LIKE '%cloud_provider%'",
                    String.class, table));
            assertThat(checks).as("cloud_provider CHECK on %s", table).contains("DATABRICKS");
        }
    }
}
