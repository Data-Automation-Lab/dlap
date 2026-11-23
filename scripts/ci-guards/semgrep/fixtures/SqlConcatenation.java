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

// Fixture for the Semgrep rules in /.semgrep.yml. Never compiled, never shipped.
package fixtures;

import jakarta.persistence.EntityManager;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class SqlConcatenation {
    private static final String ALL = "SELECT id FROM dlap_assets";

    void bad(JdbcTemplate jdbc, String name) {
        // ruleid: dlap-java-sql-concatenation
        jdbc.queryForList("SELECT id FROM dlap_assets WHERE name = '" + name + "'");
    }

    void badUpdate(JdbcTemplate jdbc, String table) {
        // ruleid: dlap-java-sql-concatenation
        jdbc.update("DELETE FROM " + table);
    }

    void badFormat(JdbcTemplate jdbc, String table) {
        // ruleid: dlap-java-sql-concatenation
        jdbc.execute(String.format("TRUNCATE %s", table));
    }

    void badFormatted(JdbcTemplate jdbc, String table) {
        // ruleid: dlap-java-sql-concatenation
        jdbc.execute("TRUNCATE %s".formatted(table));
    }

    void badNamed(NamedParameterJdbcTemplate named, String column) {
        // ruleid: dlap-java-sql-concatenation
        named.queryForList("SELECT " + column + " FROM dlap_assets", java.util.Map.of());
    }

    void badJpql(EntityManager em, String owner) {
        // ruleid: dlap-java-sql-concatenation
        em.createQuery("select a from Asset a where a.owner = '" + owner + "'");
    }

    void badNative(EntityManager em, String owner) {
        // ruleid: dlap-java-sql-concatenation
        em.createNativeQuery("select * from dlap_assets where owner = '" + owner + "'");
    }

    void badStatement(Statement statement, String id) throws Exception {
        // ruleid: dlap-java-sql-concatenation
        statement.execute("DROP TABLE t_" + id);
    }

    void goodBound(JdbcTemplate jdbc, String name) {
        // ok: dlap-java-sql-concatenation
        jdbc.queryForList("SELECT id FROM dlap_assets WHERE name = ?", name);
    }

    void goodConstant(JdbcTemplate jdbc) {
        // ok: dlap-java-sql-concatenation
        jdbc.queryForList(ALL);
    }

    void goodLiteralJoin(JdbcTemplate jdbc, String name) {
        // ok: dlap-java-sql-concatenation
        jdbc.queryForList("SELECT id " + "FROM dlap_assets WHERE name = ?", name);
    }

    void goodNamedBound(NamedParameterJdbcTemplate named) {
        // ok: dlap-java-sql-concatenation
        named.queryForList("SELECT id FROM dlap_assets WHERE name = :name", java.util.Map.of("name", "x"));
    }

    void goodJpqlBound(EntityManager em, String owner) {
        // ok: dlap-java-sql-concatenation
        em.createQuery("select a from Asset a where a.owner = :owner").setParameter("owner", owner);
    }
}
