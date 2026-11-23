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
 * POL-05 (decision D45, Q-881): Flyway V11 adds the trigger {@code trg_policies_kind_guard}. It refuses a
 * non-blank {@code policy_document} on a {@code LEGACY_RULE} row and a change of {@code policy_kind} from
 * {@code DSL} to {@code LEGACY_RULE}, so a direct edit can no longer hide a deny by setting the kind and
 * blanking the document together. Runs on a real PostgreSQL (Testcontainers) with the full migration chain.
 */
@Testcontainers
class PolicyKindGuardContainerTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final String DOC = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: p}\nspec: {effect: deny}\n";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private final UUID dsl = UUID.randomUUID();
    private final UUID legacy = UUID.randomUUID();
    private final UUID deletedDsl = UUID.randomUUID();

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
    void migrateToV10AndSeed() throws SQLException {
        flyway("latest").clean();
        flyway("10").migrate();
        try (Connection c = connect()) {
            insert(c, dsl, "dsl-row", "ENABLED", "DSL", DOC, false);
            insert(c, legacy, "legacy-row", "DISABLED", "LEGACY_RULE", null, false);
            insert(c, deletedDsl, "deleted-dsl-row", "DISABLED", "DSL", DOC, true);
        }
    }

    @Test
    void v11InstallsTheTriggerAndKeepsEveryStoredRowAsItWas() throws SQLException {
        String before;
        try (Connection c = connect()) {
            before = fingerprint(c);
        }

        flyway("latest").migrate();

        try (Connection c = connect()) {
            assertThat(count(c, "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_policies_kind_guard' "
                    + "AND tgenabled = 'O'")).isEqualTo(1);
            assertThat(fingerprint(c)).isEqualTo(before);
        }
    }

    @Test
    void aLegacyRuleRowCannotBeInsertedWithADocument() throws SQLException {
        flyway("latest").migrate();

        try (Connection c = connect()) {
            assertThatThrownBy(() -> insert(c, UUID.randomUUID(), "legacy-with-doc", "DISABLED", "LEGACY_RULE", DOC, false))
                    .hasMessageContaining("cannot hold a policy_document");
        }
    }

    @Test
    void aLegacyRuleRowCannotGetADocumentWhileItStaysLegacy() throws SQLException {
        flyway("latest").migrate();

        try (Connection c = connect()) {
            assertThatThrownBy(() -> setDocument(c, legacy, DOC)).hasMessageContaining("cannot hold a policy_document");
            assertThat(document(c, legacy)).isNull();
        }
    }

    @Test
    void aLegacyRuleRowThatGetsADocumentAndBecomesDslInOneEditIsAllowed() throws SQLException {
        flyway("latest").migrate();

        try (Connection c = connect()) {
            update(c, "UPDATE policies SET policy_kind = 'DSL', policy_document = '" + DOC.replace("'", "''")
                    + "' WHERE id = '" + legacy + "'");

            assertThat(kind(c, legacy)).isEqualTo("DSL");
            assertThat(document(c, legacy)).isEqualTo(DOC);
        }
    }

    @Test
    void aBlankDocumentOnALegacyRuleRowIsAllowed() throws SQLException {
        flyway("latest").migrate();

        try (Connection c = connect()) {
            insert(c, UUID.randomUUID(), "legacy-empty", "DISABLED", "LEGACY_RULE", "", false);
            insert(c, UUID.randomUUID(), "legacy-blanks", "DISABLED", "LEGACY_RULE", "  \n\t ", false);
            setDocument(c, legacy, "   ");

            assertThat(count(c, "SELECT count(*) FROM policies WHERE policy_kind = 'LEGACY_RULE'")).isEqualTo(3);
        }
    }

    @Test
    void aDslRowCannotBecomeALegacyRuleRowAndTheBlankingBypassIsRefusedInOneEdit() throws SQLException {
        flyway("latest").migrate();

        try (Connection c = connect()) {
            assertThatThrownBy(() -> update(c, "UPDATE policies SET policy_kind = 'LEGACY_RULE', policy_document = NULL "
                    + "WHERE id = '" + dsl + "'")).hasMessageContaining("cannot become a LEGACY_RULE row");
            assertThatThrownBy(() -> update(c, "UPDATE policies SET policy_kind = 'LEGACY_RULE', policy_document = '' "
                    + "WHERE id = '" + dsl + "'")).hasMessageContaining("cannot become a LEGACY_RULE row");
            assertThatThrownBy(() -> update(c, "UPDATE policies SET policy_kind = 'LEGACY_RULE' WHERE id = '" + dsl + "'"))
                    .hasMessageContaining("policy_document");

            assertThat(kind(c, dsl)).isEqualTo("DSL");
            assertThat(document(c, dsl)).isEqualTo(DOC);
        }
    }

    @Test
    void aDslRowCanStillBeBlankedWhichLeavesAnInvalidDenyForThePdp() throws SQLException {
        flyway("latest").migrate();

        try (Connection c = connect()) {
            setDocument(c, dsl, null);

            assertThat(kind(c, dsl)).isEqualTo("DSL");
            assertThat(document(c, dsl)).isNull();
        }
    }

    @Test
    void anOrdinaryEditOfAnyRowStillWorks() throws SQLException {
        flyway("latest").migrate();

        try (Connection c = connect()) {
            update(c, "UPDATE policies SET description = 'edited' WHERE id = '" + dsl + "'");
            update(c, "UPDATE policies SET description = 'edited' WHERE id = '" + legacy + "'");
            update(c, "UPDATE policies SET status = 'DISABLED' WHERE id = '" + dsl + "'");

            assertThat(count(c, "SELECT count(*) FROM policies WHERE description = 'edited'")).isEqualTo(2);
        }
    }

    @Test
    void aSoftDeletedRowStaysFrozen() throws SQLException {
        flyway("latest").migrate();

        try (Connection c = connect()) {
            assertThatThrownBy(() -> update(c, "UPDATE policies SET description = 'x' WHERE id = '" + deletedDsl + "'"))
                    .hasMessageContaining("is deleted and cannot change");
        }
    }

    @Test
    void theMigrationScriptCanRunAgainWithoutChangingAnything() throws SQLException, IOException {
        flyway("latest").migrate();
        String script;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V11__policy_kind_guard.sql")) {
            assertThat(in).isNotNull();
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Connection c = connect(); Statement st = c.createStatement()) {
            String before = fingerprint(c);
            st.execute(script);

            assertThat(count(c, "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_policies_kind_guard'")).isEqualTo(1);
            assertThat(fingerprint(c)).isEqualTo(before);
            assertThatThrownBy(() -> setDocument(c, legacy, DOC)).hasMessageContaining("cannot hold a policy_document");
        }
    }

    // ----- helpers --------------------------------------------------------------------------

    private static void insert(Connection c, UUID id, String name, String status, String kind, String document,
            boolean deleted) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO policies (id, name, status, created_at, "
                + "policy_kind, policy_document, deleted_at) VALUES (?, ?, ?, now(), ?, ?, "
                + (deleted ? "now()" : "NULL") + ")")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, status);
            ps.setString(4, kind);
            ps.setString(5, document);
            ps.executeUpdate();
        }
    }

    private static void setDocument(Connection c, UUID id, String document) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE policies SET policy_document = ? WHERE id = ?")) {
            ps.setString(1, document);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    private static String kind(Connection c, UUID id) throws SQLException {
        return text(c, "SELECT policy_kind FROM policies WHERE id = ?", id);
    }

    private static String document(Connection c, UUID id) throws SQLException {
        return text(c, "SELECT policy_document FROM policies WHERE id = ?", id);
    }

    private static String text(Connection c, String sql, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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
                        + "|| policy_kind || '|' || coalesce(policy_document, '<null>') || '|' "
                        + "|| (deleted_at IS NULL)::text, ';' ORDER BY id)) FROM policies")) {
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
