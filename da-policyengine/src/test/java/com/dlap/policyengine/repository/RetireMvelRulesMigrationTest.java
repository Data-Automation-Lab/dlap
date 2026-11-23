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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SEC-04: Flyway V5 archives every legacy MVEL condition, disables the policies and
 * subscriptions that used one, empties the rule tables, makes them refuse new rows and adds
 * the typed subscription filter tables. Runs on a real PostgreSQL (Testcontainers): the
 * schema is migrated to V4, seeded with legacy rows, then migrated to V5.
 */
@Testcontainers
class RetireMvelRulesMigrationTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final String MVEL_PAYLOAD = "java.lang.Runtime.getRuntime().exec('touch /tmp/x') != null";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private final UUID ruleBasedPolicy = UUID.randomUUID();
    private final UUID dslPolicy = UUID.randomUUID();
    private final UUID disabledRulePolicy = UUID.randomUUID();
    private final UUID activeRuleSub = UUID.randomUUID();
    private final UUID activePlainSub = UUID.randomUUID();
    private final UUID pausedRuleSub = UUID.randomUUID();
    private final UUID archivedRuleSub = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + SCHEMA);
        }
        return c;
    }

    @BeforeEach
    void migrateToV4AndSeedLegacyRows() throws SQLException {
        flyway("latest").clean();
        flyway("4").migrate();
        try (Connection c = connect()) {
            policy(c, ruleBasedPolicy, "rule-based", "ENABLED", null);
            policy(c, dslPolicy, "dsl-only", "ENABLED", "apiVersion: dlap/v1");
            policy(c, disabledRulePolicy, "already-disabled", "DISABLED", null);
            policyRule(c, ruleBasedPolicy, "r1", MVEL_PAYLOAD);
            policyRule(c, ruleBasedPolicy, "r2", "asset.assetType == 'S3_BUCKET'");
            policyRule(c, disabledRulePolicy, "r3", "true");

            subscription(c, activeRuleSub, "active-with-rule", "ACTIVE");
            subscription(c, activePlainSub, "active-plain", "ACTIVE");
            subscription(c, pausedRuleSub, "paused-with-rule", "PAUSED");
            subscription(c, archivedRuleSub, "archived-with-rule", "ARCHIVED");
            eventRule(c, activeRuleSub, "e1", MVEL_PAYLOAD);
            eventRule(c, pausedRuleSub, "e2", "severity == 'HIGH'");
            eventRule(c, archivedRuleSub, "e3", "true");
        }
    }

    @Test
    void v5_archivesEveryConditionVerbatimWithItsOwner() throws SQLException {
        flyway("5").migrate();

        try (Connection c = connect()) {
            assertThat(count(c, "SELECT count(*) FROM retired_mvel_rules WHERE source_table = 'policy_rules'")).isEqualTo(3);
            assertThat(count(c, "SELECT count(*) FROM retired_mvel_rules WHERE source_table = 'event_rules'")).isEqualTo(3);
            assertThat(strings(c, "SELECT condition FROM retired_mvel_rules WHERE rule_name IN ('r1', 'e1')"))
                    .containsOnly(MVEL_PAYLOAD);
            assertThat(strings(c, "SELECT owner_status_before FROM retired_mvel_rules WHERE rule_name = 'r1'"))
                    .containsExactly("ENABLED");
            assertThat(strings(c, "SELECT owner_name FROM retired_mvel_rules WHERE rule_name = 'e2'"))
                    .containsExactly("paused-with-rule");
            assertThat(strings(c, "SELECT owner_created_by_user_id::text FROM retired_mvel_rules WHERE rule_name = 'r1'"))
                    .containsExactly(creator.toString());
            assertThat(count(c, "SELECT count(*) FROM retired_mvel_rules WHERE retired_at IS NULL")).isZero();
        }
    }

    @Test
    void v5_disablesOnlyTheOwnersThatHadARule() throws SQLException {
        flyway("5").migrate();

        try (Connection c = connect()) {
            assertThat(status(c, "policies", ruleBasedPolicy)).isEqualTo("DISABLED");
            assertThat(status(c, "policies", disabledRulePolicy)).isEqualTo("DISABLED");
            assertThat(status(c, "policies", dslPolicy)).as("a DSL policy is untouched").isEqualTo("ENABLED");
            assertThat(status(c, "event_subscriptions", activeRuleSub)).isEqualTo("DISABLED");
            assertThat(status(c, "event_subscriptions", pausedRuleSub)).isEqualTo("DISABLED");
            assertThat(status(c, "event_subscriptions", archivedRuleSub)).isEqualTo("ARCHIVED");
            assertThat(status(c, "event_subscriptions", activePlainSub)).isEqualTo("ACTIVE");
        }
    }

    @Test
    void v5_emptiesTheRuleTablesAndRefusesNewRows() throws SQLException {
        flyway("5").migrate();

        try (Connection c = connect()) {
            assertThat(count(c, "SELECT count(*) FROM policy_rules")).isZero();
            assertThat(count(c, "SELECT count(*) FROM event_rules")).isZero();
            assertThatThrownBy(() -> policyRule(c, dslPolicy, "new", "true"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
            assertThatThrownBy(() -> eventRule(c, activePlainSub, "new", "true"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
        }
    }

    @Test
    void v5_addsTheTypedFilterTables() throws SQLException {
        flyway("5").migrate();

        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO event_subscription_asset_prefixes (subscription_id, asset_prefix) VALUES (?, ?)")) {
                ps.setObject(1, activePlainSub);
                ps.setString(2, "s3://finance/" + "k".repeat(2000));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO event_subscription_labels (subscription_id, label) VALUES (?, ?)")) {
                ps.setObject(1, activePlainSub);
                ps.setString(2, "PII");
                ps.executeUpdate();
            }
            assertThat(count(c, "SELECT count(*) FROM event_subscription_labels")).isEqualTo(1);
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO event_subscription_labels (subscription_id, label) VALUES (?, ?)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, "orphan");
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void v5_isIdempotentWhenItsStatementsRunAgain() throws SQLException, IOException {
        flyway("5").migrate();
        String script;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V5__retire_mvel_rules.sql")) {
            assertThat(in).isNotNull();
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(script);
            assertThat(count(c, "SELECT count(*) FROM retired_mvel_rules")).isEqualTo(6);
            assertThat(status(c, "policies", dslPolicy)).isEqualTo("ENABLED");
        }
    }

    // ----- seeding helpers ------------------------------------------------------------------

    private void policy(Connection c, UUID id, String name, String status, String document) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO policies (id, name, status, created_at, "
                + "created_by_user_id, condition_logic, policy_document) VALUES (?, ?, ?, now(), ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, status);
            ps.setObject(4, creator);
            ps.setString(5, "r1 && r2");
            ps.setString(6, document);
            ps.executeUpdate();
        }
    }

    private static void policyRule(Connection c, UUID policyId, String name, String condition) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO policy_rules (id, policy_id, name, condition, "
                + "priority, created_at) VALUES (?, ?, ?, ?, 1, now())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, policyId);
            ps.setString(3, name);
            ps.setString(4, condition);
            ps.executeUpdate();
        }
    }

    private void subscription(Connection c, UUID id, String name, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO event_subscriptions (id, name, user_id, status, "
                + "created_at, created_by_user_id) VALUES (?, ?, ?, ?, now(), ?)")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setObject(3, creator);
            ps.setString(4, status);
            ps.setObject(5, creator);
            ps.executeUpdate();
        }
    }

    private void eventRule(Connection c, UUID subscriptionId, String name, String condition) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO event_rules (id, subscription_id, name, condition, "
                + "priority, enabled, created_at, created_by_user_id) VALUES (?, ?, ?, ?, 1, true, now(), ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, subscriptionId);
            ps.setString(3, name);
            ps.setString(4, condition);
            ps.setObject(5, creator);
            ps.executeUpdate();
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static String status(Connection c, String table, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM " + table + " WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
