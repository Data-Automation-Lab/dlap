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

import static com.dlap.catalog.schema.UpgradeRunnerSupport.run;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.schemaSnapshot;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.sql;
import static com.dlap.catalog.schema.UpgradeRunnerSupport.sqlAs;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;

import com.dlap.catalog.schema.UpgradeRunnerSupport.Run;

/**
 * COV-01: {@code upgrades/2026-10-20_asset_read_coverage.sql} brings the previous release's
 * {@code dlap_common} (the 7984d1d init scripts, as in {@link CommonSchemaUpgradeTest}) to the read
 * coverage columns, leaves every existing row unknown, and enforces the value checks. The full
 * schema equality with fresh init scripts is {@link CommonSchemaUpgradeTest}'s job. Requires Docker.
 */
class ReadCoverageSchemaUpgradeTest {

    private static final String DB = "dlap_common";
    private static final String DIR = "/upgrades/real";
    private static PostgreSQLContainer<?> postgres;
    private static Run firstRun;

    @BeforeAll
    static void upgrade() {
        postgres = UpgradeRunnerSupport.baselinePostgres();
        postgres.start();
        UpgradeRunnerSupport.installRunner(postgres);
        UpgradeRunnerSupport.installShippedDeltas(postgres, DIR);
        assertThat(schemaSnapshot(postgres, DB)).noneMatch(l -> l.startsWith("col dlap_assets.read_coverage "));
        firstRun = run(postgres, DB, DIR);
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Test
    void theRunnerAppliesTheDeltaAndRecordsIt() {
        assertThat(firstRun.exitCode()).as(firstRun.transcript()).isZero();
        assertThat(sql(postgres, DB, "SELECT count(*) FROM dlap_common.schema_upgrades "
                + "WHERE file_name = '2026-10-20_asset_read_coverage.sql'")).isEqualTo("1");
        assertThat(sql(postgres, DB, "SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY column_name) "
                + "FROM information_schema.columns WHERE table_schema = 'dlap_common' AND table_name = 'dlap_assets' "
                + "AND column_name LIKE 'read\\_coverage%'")).isEqualTo("read_coverage:character varying,"
                + "read_coverage_observed_at:timestamp with time zone,read_coverage_reasons:jsonb,"
                + "read_coverage_since:timestamp with time zone");
    }

    @Test
    void everyExistingRowStartsUnknown() {
        assertThat(sql(postgres, DB, "SELECT count(*) FROM dlap_common.dlap_assets")).isNotEqualTo("0");
        assertThat(sql(postgres, DB, "SELECT count(*) FROM dlap_common.dlap_assets WHERE read_coverage IS NOT NULL "
                + "OR read_coverage_reasons IS NOT NULL OR read_coverage_since IS NOT NULL")).isEqualTo("0");
    }

    @Test
    void theValueChecksHold() {
        String id = sql(postgres, DB, "SELECT asset_id FROM dlap_common.dlap_assets LIMIT 1");
        for (String bad : List.of("read_coverage = 'MOSTLY'", "read_coverage_reasons = '{\"a\":1}'::jsonb",
                "read_coverage = 'PARTIAL', read_coverage_since = now()")) {
            ExecResult r = sqlAs(postgres, "dlap", DB, "UPDATE dlap_common.dlap_assets SET " + bad
                    + " WHERE asset_id = '" + id + "'");
            assertThat(r.getExitCode()).as(bad).isNotZero();
            assertThat(r.getStderr()).contains("violates check constraint");
        }
        // Inside a rolled-back transaction, so the other tests still see every row unknown.
        ExecResult ok = sqlAs(postgres, "dlap", DB, "BEGIN; UPDATE dlap_common.dlap_assets SET read_coverage = 'FULL', "
                + "read_coverage_reasons = '[\"evidence\"]'::jsonb, read_coverage_since = now() WHERE asset_id = '" + id
                + "'; ROLLBACK;");
        assertThat(ok.getExitCode()).as(ok.getStderr()).isZero();
    }

    @Test
    void aSecondRunAppliesNothing() {
        Run again = run(postgres, DB, DIR);
        assertThat(again.exitCode()).isZero();
        assertThat(again.applied()).isZero();
    }
}
