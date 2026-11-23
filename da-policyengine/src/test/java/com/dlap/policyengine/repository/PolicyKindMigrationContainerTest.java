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

package com.dlap.policyengine.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * POL-04 (ADR-0136, Q-871): Flyway V10 adds {@code policies.policy_kind} and backfills it from the data.
 * Runs on a real PostgreSQL (Testcontainers): the schema is migrated to V9, seeded with live and
 * soft-deleted rows of every shape, then migrated to V10.
 */
@Testcontainers
class PolicyKindMigrationContainerTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final String DOC = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: p}\nspec: {effect: deny}\n";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private final UUID enabledDsl = UUID.randomUUID();
    private final UUID disabledDsl = UUID.randomUUID();
    private final UUID enabledNull = UUID.randomUUID();
    private final UUID enabledEmpty = UUID.randomUUID();
    private final UUID enabledWhitespace = UUID.randomUUID();
    private final UUID disabledNull = UUID.randomUUID();
    private final UUID deletedDsl = UUID.randomUUID();
    private final UUID deletedNull = UUID.randomUUID();

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + SCHEMA);
        }
        return c;
    }

    @BeforeEach
    void migrateToV9AndSeed() throws SQLException {
        flyway("latest").clean();
        flyway("9").migrate();
        try (Connection c = connect()) {
            policy(c, enabledDsl, "enabled-dsl", "ENABLED", DOC, false);
            policy(c, disabledDsl, "disabled-dsl", "DISABLED", DOC, false);
            policy(c, enabledNull, "enabled-null", "ENABLED", null, false);
            policy(c, enabledEmpty, "enabled-empty", "ENABLED", "", false);
            policy(c, enabledWhitespace, "enabled-whitespace", "ENABLED", "  \n\t \r\n", false);
            policy(c, disabledNull, "disabled-null", "DISABLED", null, false);
            policy(c, deletedDsl, "deleted-dsl", "DISABLED", DOC, true);
            policy(c, deletedNull, "deleted-null", "DISABLED", null, true);
        }
    }

    @Test
    void theBackfillMakesEveryRowWithANonBlankDocumentDslAndEveryOtherRowLegacyRule() throws SQLException {
        flyway("10").migrate();

        try (Connection c = connect()) {
            assertThat(kind(c, enabledDsl)).isEqualTo("DSL");
            assertThat(kind(c, disabledDsl)).isEqualTo("DSL");
            assertThat(kind(c, enabledNull)).isEqualTo("LEGACY_RULE");
            assertThat(kind(c, enabledEmpty)).isEqualTo("LEGACY_RULE");
            assertThat(kind(c, enabledWhitespace)).isEqualTo("LEGACY_RULE");
            assertThat(kind(c, disabledNull)).isEqualTo("LEGACY_RULE");
            assertThat(count(c, "SELECT count(*) FROM policies WHERE policy_kind IS NULL")).isZero();
            assertThat(count(c, "SELECT count(*) FROM policies")).isEqualTo(8);
        }
    }

    @Test
    void softDeletedRowsAreBackfilledToo() throws SQLException {
        flyway("10").migrate();

        try (Connection c = connect()) {
            assertThat(kind(c, deletedDsl)).isEqualTo("DSL");
            assertThat(kind(c, deletedNull)).isEqualTo("LEGACY_RULE");
        }
    }

    @Test
    void theBackfillChangesNothingButThePolicyKind() throws SQLException {
        String before;
        try (Connection c = connect()) {
            before = fingerprint(c);
        }

        flyway("10").migrate();

        try (Connection c = connect()) {
            assertThat(fingerprint(c)).isEqualTo(before);
        }
    }

    @Test
    void theSoftDeleteGuardIsBackInForceAfterTheBackfill() throws SQLException {
        flyway("10").migrate();

        try (Connection c = connect()) {
            assertThat(count(c, "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_policies_soft_delete_guard' "
                    + "AND tgenabled = 'O'")).isEqualTo(1);
            assertThatThrownBy(() -> update(c, "UPDATE policies SET description = 'x' WHERE id = '" + deletedDsl + "'"))
                    .hasMessageContaining("is deleted and cannot change");
        }
    }

    @Test
    void theColumnIsNotNullAndOnlyTakesTheTwoKinds() throws SQLException {
        flyway("10").migrate();

        try (Connection c = connect()) {
            assertThat(count(c, "SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                    + "' AND table_name = 'policies' AND column_name = 'policy_kind' AND is_nullable = 'NO' "
                    + "AND column_default IS NULL")).isEqualTo(1);
            assertThatThrownBy(() -> update(c, "UPDATE policies SET policy_kind = 'OTHER' WHERE id = '" + enabledDsl + "'"))
                    .hasMessageContaining("ck_policies_policy_kind");
            assertThatThrownBy(() -> update(c, "UPDATE policies SET policy_kind = NULL WHERE id = '" + enabledDsl + "'"))
                    .hasMessageContaining("policy_kind");
            assertThatThrownBy(() -> update(c, "INSERT INTO policies (id, name, status, created_at) VALUES ('"
                    + UUID.randomUUID() + "', 'no-kind', 'DISABLED', now())")).hasMessageContaining("policy_kind");
        }
    }

    @Test
    void theMigrationScriptCanRunAgainWithoutChangingAnything() throws SQLException, IOException {
        flyway("10").migrate();
        String script;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V10__policy_kind.sql")) {
            assertThat(in).isNotNull();
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Connection c = connect(); Statement st = c.createStatement()) {
            update(c, "UPDATE policies SET policy_kind = 'DSL' WHERE id = '" + enabledNull + "'");
            st.execute(script);

            assertThat(kind(c, enabledNull)).as("a rerun keeps a kind that is already set").isEqualTo("DSL");
            assertThat(kind(c, enabledDsl)).isEqualTo("DSL");
            assertThat(kind(c, disabledNull)).isEqualTo("LEGACY_RULE");
        }
    }

    // ----- helpers --------------------------------------------------------------------------

    private static void policy(Connection c, UUID id, String name, String status, String document, boolean deleted)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO policies (id, name, status, created_at, "
                + "policy_document, deleted_at) VALUES (?, ?, ?, now(), ?, " + (deleted ? "now()" : "NULL") + ")")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, status);
            ps.setString(4, document);
            ps.executeUpdate();
        }
    }

    private static String kind(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT policy_kind FROM policies WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static String fingerprint(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT md5(string_agg(id::text || '|' || name || '|' || status || '|' "
                        + "|| coalesce(policy_document, '<null>') || '|' || (deleted_at IS NULL)::text, ';' ORDER BY id)) "
                        + "FROM policies")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void update(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
