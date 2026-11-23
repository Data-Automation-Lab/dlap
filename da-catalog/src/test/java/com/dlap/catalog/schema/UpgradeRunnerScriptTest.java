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
import static com.dlap.catalog.schema.UpgradeRunnerSupport.createDatabase;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.ledger;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.ledgerWithTimes;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.putFile;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.run;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.runWithEnv;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.sql;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.tableExists;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.catalog.schema.UpgradeRunnerSupport.Run;

/**
 * DB-02 acceptance for {@code deployment/platform/database/upgrades/run-upgrades.sh} (ADR-0056),
 * using small synthetic deltas so each rule of the runner is proven on its own: a second run
 * applies nothing, a modified applied file fails loudly, order is enforced, a failing delta is
 * not recorded and stops the run, and a dry run changes nothing.
 *
 * <p>Every test gets its own database and delta folder inside one shared PostgreSQL 15.1
 * container, and runs the real script there. {@link CommonSchemaUpgradeTest} covers the shipped
 * deltas against the previous release's schema.
 *
 * <p>Requires Docker.
 */
@Testcontainers
class UpgradeRunnerScriptTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = UpgradeRunnerSupport.barePostgres();

    private static final AtomicInteger CASES = new AtomicInteger();

    private static final String A = "2026-01-01_create_a.sql";
    private static final String B = "2026-01-02_create_b.sql";
    private static final String C = "2026-01-03_create_c.sql";

    private String db;
    private String dir;

    @BeforeAll
    static void installRunner() {
        UpgradeRunnerSupport.installRunner(POSTGRES);
    }

    @BeforeEach
    void freshDatabaseAndFolder() throws Exception {
        int n = CASES.incrementAndGet();
        db = "case_" + n;
        dir = "/cases/" + db;
        createDatabase(POSTGRES, db);
        POSTGRES.execInContainer("mkdir", "-p", dir);
    }

    private static String createTable(String table) {
        return "CREATE SCHEMA IF NOT EXISTS dlap_common;\n"
                + "CREATE TABLE IF NOT EXISTS dlap_common." + table + " (id int);\n";
    }

    private void put(String name, String content) {
        putFile(POSTGRES, dir, name, content);
    }

    private Run runner(String... args) {
        return run(POSTGRES, db, dir, args);
    }

    @Test
    void appliesPendingFilesInNameOrderAndRecordsEachChecksum() {
        String a = createTable("t_a");
        // B only works if A ran first: it inserts into A's table.
        String b = "INSERT INTO dlap_common.t_a VALUES (1);\n" + createTable("t_b");
        put(B, b);
        put(A, a);

        Run result = runner();

        assertThat(result.exitCode()).as(result.transcript()).isZero();
        assertThat(result.applied()).as(result.transcript()).isEqualTo(2);
        assertThat(ledger(POSTGRES, db)).containsExactly(A + "|" + checksum(a), B + "|" + checksum(b));
        assertThat(sql(POSTGRES, db, "SELECT count(*) FROM dlap_common.t_a")).isEqualTo("1");
        assertThat(sql(POSTGRES, db, "SELECT count(*) FROM dlap_common.schema_upgrades "
                + "WHERE applied_at IS NOT NULL AND applied_by = 'dlap'")).isEqualTo("2");
    }

    @Test
    void secondRunAppliesNothing() {
        put(A, createTable("t_a"));
        put(B, createTable("t_b"));
        assertThat(runner().applied()).isEqualTo(2);
        String before = ledgerWithTimes(POSTGRES, db);

        Run second = runner();

        assertThat(second.exitCode()).as(second.transcript()).isZero();
        assertThat(second.applied()).as(second.transcript()).isZero();
        assertThat(second.skipped()).as(second.transcript()).isEqualTo(2);
        assertThat(second.out()).doesNotContain("applying");
        assertThat(ledgerWithTimes(POSTGRES, db)).as("ledger rows and times are untouched").isEqualTo(before);
    }

    @Test
    void aFileAddedLaterIsTheOnlyOneApplied() {
        put(A, createTable("t_a"));
        assertThat(runner().applied()).isEqualTo(1);
        put(B, createTable("t_b"));

        Run next = runner();

        assertThat(next.exitCode()).as(next.transcript()).isZero();
        assertThat(next.applied()).isEqualTo(1);
        assertThat(next.skipped()).isEqualTo(1);
        assertThat(next.out()).contains("applying " + B).doesNotContain("applying " + A);
        assertThat(ledger(POSTGRES, db)).hasSize(2);
    }

    @Test
    void aModifiedAppliedFileFailsLoudlyAndNothingIsApplied() {
        put(A, createTable("t_a"));
        put(B, createTable("t_b"));
        assertThat(runner().applied()).isEqualTo(2);
        List<String> before = ledger(POSTGRES, db);

        put(A, createTable("t_a") + "-- edited after it was applied\n");
        put(C, createTable("t_c"));
        Run refused = runner();

        assertThat(refused.exitCode()).as(refused.transcript()).isEqualTo(2);
        assertThat(refused.err()).contains("REFUSED").contains(A).contains("changed since it was applied");
        assertThat(refused.out()).doesNotContain("applying");
        assertThat(tableExists(POSTGRES, db, "t_c")).as("the pending file must not run past a refusal").isFalse();
        assertThat(ledger(POSTGRES, db)).as("the ledger is not rewritten").isEqualTo(before);

        // Putting the original back makes the same folder pass again and the pending file apply.
        put(A, createTable("t_a"));
        Run restored = runner();
        assertThat(restored.exitCode()).as(restored.transcript()).isZero();
        assertThat(restored.applied()).isEqualTo(1);
        assertThat(tableExists(POSTGRES, db, "t_c")).isTrue();
    }

    @Test
    void aDryRunReportsChangedFilesToo() {
        put(A, createTable("t_a"));
        assertThat(runner().applied()).isEqualTo(1);
        put(A, createTable("t_a") + "-- edited\n");

        Run dry = runner("--dry-run");

        assertThat(dry.exitCode()).as(dry.transcript()).isEqualTo(2);
        assertThat(dry.err()).contains("REFUSED").contains(A);
    }

    @Test
    void lineEndingsDoNotChangeTheChecksum() {
        String lf = createTable("t_a");
        put(A, lf);
        assertThat(runner().applied()).isEqualTo(1);

        put(A, lf.replace("\n", "\r\n"));
        Run crlf = runner();

        assertThat(crlf.exitCode()).as(crlf.transcript()).isZero();
        assertThat(crlf.applied()).isZero();
        assertThat(crlf.skipped()).isEqualTo(1);
    }

    @Test
    void aPendingFileOlderThanTheNewestAppliedIsRefusedUnlessAllowed() {
        put(B, createTable("t_b"));
        assertThat(runner().applied()).isEqualTo(1);
        put(A, createTable("t_a"));

        Run refused = runner();
        assertThat(refused.exitCode()).as(refused.transcript()).isEqualTo(2);
        assertThat(refused.err()).contains("REFUSED").contains(A).contains("sorts before");
        assertThat(tableExists(POSTGRES, db, "t_a")).isFalse();

        Run allowed = runner("--allow-out-of-order");
        assertThat(allowed.exitCode()).as(allowed.transcript()).isZero();
        assertThat(allowed.applied()).isEqualTo(1);
        assertThat(tableExists(POSTGRES, db, "t_a")).isTrue();
    }

    @Test
    void orderingFollowsFileNamesNotTheDatabaseCollation() {
        // Bytewise, "a.sql" < "a_a.sql" < "a_b.sql". Under en_US punctuation is ignored, so the
        // database would put "a_b.sql" before "a.sql" and call "a_a.sql" newer than both.
        String dot = "2026-01-01_a.sql";
        String underscoreB = "2026-01-01_a_b.sql";
        String underscoreA = "2026-01-01_a_a.sql";
        put(dot, createTable("t_dot"));
        put(underscoreB, createTable("t_b"));
        assertThat(runner().applied()).isEqualTo(2);
        assertThat(sql(POSTGRES, db, "SELECT datcollate FROM pg_database WHERE datname = current_database()"))
                .as("the premise: the database does not use bytewise collation").isNotEqualTo("C");

        put(underscoreA, createTable("t_a"));
        Run refused = runner();

        assertThat(refused.exitCode()).as(refused.transcript()).isEqualTo(2);
        assertThat(refused.err()).contains("REFUSED").contains(underscoreA).contains("sorts before");
    }

    @Test
    void aRoleThatCannotCreateTheLedgerFailsWithAClearMessage() {
        String role = "limited_" + db;
        sql(POSTGRES, "dlap", "CREATE ROLE " + role + " LOGIN");
        put(A, createTable("t_a"));

        Run result = runWithEnv(POSTGRES, List.of("PGUSER=" + role, "PGDATABASE=" + db), dir);

        assertThat(result.exitCode()).as(result.transcript()).isEqualTo(1);
        assertThat(result.err()).contains("permission denied").contains("could not create dlap_common.schema_upgrades");
        assertThat(ledger(POSTGRES, db)).isEmpty();
    }

    @Test
    void aFailingDeltaIsNotRecordedAndStopsTheRun() {
        put(A, createTable("t_a"));
        put(B, createTable("t_b") + "SELECT * FROM dlap_common.no_such_table;\n");
        put(C, createTable("t_c"));

        Run failed = runner();

        assertThat(failed.exitCode()).as(failed.transcript()).isEqualTo(1);
        assertThat(failed.err()).contains("no_such_table").contains(B + " failed and was not recorded");
        assertThat(ledger(POSTGRES, db)).extracting(row -> row.split("\\|")[0]).containsExactly(A);
        assertThat(tableExists(POSTGRES, db, "t_c")).as("later files are not attempted").isFalse();

        // B was never recorded, so correcting it is allowed; the next run finishes the job.
        put(B, createTable("t_b"));
        Run fixed = runner();
        assertThat(fixed.exitCode()).as(fixed.transcript()).isZero();
        assertThat(fixed.applied()).isEqualTo(2);
        assertThat(fixed.skipped()).isEqualTo(1);
        assertThat(ledger(POSTGRES, db)).hasSize(3);
    }

    @Test
    void aDryRunChangesNothingAndDoesNotCreateTheLedger() {
        put(A, createTable("t_a"));
        put(B, createTable("t_b"));

        Run dry = runner("--dry-run");

        assertThat(dry.exitCode()).as(dry.transcript()).isZero();
        assertThat(dry.out()).contains("would apply " + A).contains("would apply " + B).contains("pending=2");
        assertThat(tableExists(POSTGRES, db, "schema_upgrades")).isFalse();
        assertThat(tableExists(POSTGRES, db, "t_a")).isFalse();

        assertThat(runner().applied()).isEqualTo(2);
        Run after = runner("--dry-run");
        assertThat(after.exitCode()).isZero();
        assertThat(after.out()).contains("pending=0").doesNotContain("would apply");
    }

    @Test
    void aFileNotNamedWithADateIsRefused() {
        put(A, createTable("t_a"));
        put("notes.sql", "SELECT 1;\n");

        Run refused = runner();

        assertThat(refused.exitCode()).as(refused.transcript()).isEqualTo(2);
        assertThat(refused.err()).contains("REFUSED").contains("notes.sql");
        assertThat(tableExists(POSTGRES, db, "t_a")).isFalse();
    }

    @Test
    void aLedgerRowWhoseFileWasRetiredOnlyWarns() throws Exception {
        put(A, createTable("t_a"));
        put(B, createTable("t_b"));
        assertThat(runner().applied()).isEqualTo(2);
        POSTGRES.execInContainer("rm", dir + "/" + A);

        Run result = runner();

        assertThat(result.exitCode()).as(result.transcript()).isZero();
        assertThat(result.err()).contains("WARNING").contains(A);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void anEmptyFolderIsANoOp() {
        Run result = runner();

        assertThat(result.exitCode()).as(result.transcript()).isZero();
        assertThat(result.applied()).isZero();
        assertThat(tableExists(POSTGRES, db, "schema_upgrades")).as("no ledger without work").isFalse();
    }

    @Test
    void aJdbcStyleUrlIsAccepted() {
        put(A, createTable("t_a"));

        Run result = runWithEnv(POSTGRES,
                List.of("DLAP_COMMON_DB_URL=jdbc:postgresql://localhost/" + db + "?user=dlap"), dir);

        assertThat(result.exitCode()).as(result.transcript()).isZero();
        assertThat(result.applied()).isEqualTo(1);
        assertThat(tableExists(POSTGRES, db, "t_a")).isTrue();
    }

    @Test
    void anUnreachableDatabaseFailsWithAClearMessage() {
        put(A, createTable("t_a"));

        Run result = run(POSTGRES, "no_such_database", dir);

        assertThat(result.exitCode()).as(result.transcript()).isEqualTo(1);
        assertThat(result.err()).contains("cannot connect");
    }

    @Test
    void helpAndUnknownArgumentsBehave() {
        Run help = runner("--help");
        assertThat(help.exitCode()).as(help.transcript()).isZero();
        assertThat(help.out()).contains("Usage:").contains("--dry-run");

        Run unknown = runner("--frobnicate");
        assertThat(unknown.exitCode()).isEqualTo(1);
        assertThat(unknown.err()).contains("unknown argument");
    }
}
