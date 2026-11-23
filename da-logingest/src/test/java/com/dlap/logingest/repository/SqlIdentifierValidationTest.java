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

package com.dlap.logingest.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proof behind the narrow exclusion of the two repositories in {@code .semgrep.yml} (rule
 * {@code dlap-java-sql-concatenation}, work package CI-02b). Both repositories build DDL and
 * partition statements by joining a schema name to a table name, because an identifier cannot be a
 * bind parameter. That is safe only while the schema name is a plain lower-case identifier and the
 * partition name follows the monthly convention. This test pins the first half; the second half is
 * {@code LogEventPartitionRepositoryTest#onlyMonthlyPartitionsCanBeDropped}. It needs no database:
 * both constructors reject a bad name before they touch the template.
 */
class SqlIdentifierValidationTest {

    private static final String[] HOSTILE = {
            "dlap_logingest; DROP TABLE dlap_logingest.log_events",
            "dlap_logingest\"",
            "dlap_logingest'",
            "dlap_logingest.log_events",
            "dlap-logingest",
            "dlap logingest",
            "Dlap_Logingest",
            "1dlap",
            "dlap_logingest--",
            "dlap_logingest/*",
    };

    @ParameterizedTest
    @ValueSource(strings = {"dlap_logingest", "a", "_x", "schema_1", "abcdefghijklmnopqrstuvwxyz_0123456789_abcdefghijklmnopqrstuvwx"})
    void bothRepositoriesAcceptAPlainIdentifier(String schema) {
        assertThat(schema.length()).isLessThanOrEqualTo(63);
        assertThatCode(() -> new LogEventPartitionRepository(new JdbcTemplate(), schema)).doesNotThrowAnyException();
        assertThatCode(() -> new FormatObservationRepository(new JdbcTemplate(), schema)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a123456789012345678901234567890123456789012345678901234567890123"})
    void bothRepositoriesRejectNullEmptyAndTooLongNames(String schema) {
        assertThatThrownBy(() -> new LogEventPartitionRepository(new JdbcTemplate(), schema))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormatObservationRepository(new JdbcTemplate(), schema))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bothRepositoriesRejectEverySqlInjectionShapedSchema() {
        for (String schema : HOSTILE) {
            assertThatThrownBy(() -> new LogEventPartitionRepository(new JdbcTemplate(), schema))
                    .as("LogEventPartitionRepository must reject %s", schema)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new FormatObservationRepository(new JdbcTemplate(), schema))
                    .as("FormatObservationRepository must reject %s", schema)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void partitionNamesFollowTheMonthlyConventionOnly() {
        assertThat(LogEventPartitionRepository.partitionName(YearMonth.of(2030, 2))).isEqualTo("log_events_p2030_02");
        for (String hostile : new String[] {"log_events_p2030_02; DROP TABLE x", "log_events_default",
                "log_events_p2030_13", "x log_events_p2030_02", "log_events_p2030_02 --"}) {
            assertThat(LogEventPartitionRepository.monthOf(hostile)).as("monthOf(%s)", hostile).isEmpty();
        }
    }
}
