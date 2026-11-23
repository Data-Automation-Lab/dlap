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

package com.dlap.logingest.quarantine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The table identifier of {@link QuarantineRecordRepository} is validated once in the constructor
 * (rule {@code dlap-java-sql-concatenation}). The statements built from it run against PostgreSQL in
 * {@link QuarantineRecordRepositoryTest}. These cases need no database: the constructor rejects a
 * bad name before it touches the template.
 */
class QuarantineRecordRepositoryIdentifierTest {

    private static final String[] HOSTILE = {
            "x; drop table y",
            "a.b.c",
            "a.b",
            "dlap_logingest\"",
            "dlap_logingest'",
            "dlap-logingest",
            "dlap logingest",
            "Dlap_Logingest",
            "1dlap",
            "dlap_logingest--",
            "dlap_logingest/*",
    };

    @ParameterizedTest
    @ValueSource(strings = {"dlap_logingest", "a", "_x", "schema_1"})
    void aPlainSchemaIsAccepted(String schema) {
        assertThatCode(() -> new QuarantineRecordRepository(new JdbcTemplate(), new ObjectMapper(), schema)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a123456789012345678901234567890123456789012345678901234567890123"})
    void nullEmptyAndTooLongSchemasAreRejected(String schema) {
        assertThatThrownBy(() -> new QuarantineRecordRepository(new JdbcTemplate(), new ObjectMapper(), schema))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyInjectionShapedSchemaIsRejectedWithAClearMessage() {
        for (String schema : HOSTILE) {
            assertThatThrownBy(() -> new QuarantineRecordRepository(new JdbcTemplate(), new ObjectMapper(), schema))
                    .as("must reject %s", schema)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid schema name");
        }
    }

    @Test
    void theQualifiedTablePatternAcceptsOneOptionalSchemaOnly() {
        for (String ok : new String[] {"quarantine_record", "dlap_logingest.quarantine_record", "_a._b1"}) {
            assertThat(QuarantineRecordRepository.QUALIFIED_IDENTIFIER.matcher(ok).matches()).as(ok).isTrue();
        }
        for (String bad : new String[] {"x; drop table y", "a.b.c", "a.", ".a", "A.b", "a-b", "", "a b"}) {
            assertThat(QuarantineRecordRepository.QUALIFIED_IDENTIFIER.matcher(bad).matches()).as(bad).isFalse();
        }
    }
}
