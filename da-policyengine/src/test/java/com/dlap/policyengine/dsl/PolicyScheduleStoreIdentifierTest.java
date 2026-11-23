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

package com.dlap.policyengine.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The table identifier of {@link PolicyScheduleStore} is validated once in the constructor (rule
 * {@code dlap-java-sql-concatenation}). The cases need no database: the constructor rejects a bad
 * schema before any statement runs. The statements themselves run on a migrated PostgreSQL in
 * {@link PolicyScheduleStoreTest}.
 */
class PolicyScheduleStoreIdentifierTest {

    private static final String[] HOSTILE = {
            "x; drop table y",
            "a.b.c",
            "a.b",
            "dlap_policyengine\"",
            "dlap_policyengine'",
            "dlap-policyengine",
            "dlap policyengine",
            "Dlap_Policyengine",
            "1dlap",
            "dlap_policyengine--",
            "dlap_policyengine/*",
    };

    private final DataSource dataSource = mock(DataSource.class);

    @ParameterizedTest
    @ValueSource(strings = {"dlap_policyengine", "a", "_x", "schema_1"})
    void aPlainSchemaIsAccepted(String schema) {
        assertThatCode(() -> new PolicyScheduleStore(dataSource, schema)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a123456789012345678901234567890123456789012345678901234567890123"})
    void nullEmptyAndTooLongSchemasAreRejected(String schema) {
        assertThatThrownBy(() -> new PolicyScheduleStore(dataSource, schema))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyInjectionShapedSchemaIsRejected() {
        for (String schema : HOSTILE) {
            assertThatThrownBy(() -> new PolicyScheduleStore(dataSource, schema))
                    .as("must reject %s", schema)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid");
        }
    }

    @Test
    void theQualifiedTablePatternAcceptsOneOptionalSchemaOnly() {
        for (String ok : new String[] {"policy_schedule_state", "dlap_policyengine.policy_schedule_state", "_a._b1"}) {
            assertThat(PolicyScheduleStore.QUALIFIED_IDENTIFIER.matcher(ok).matches()).as(ok).isTrue();
        }
        for (String bad : new String[] {"x; drop table y", "a.b.c", "a.", ".a", "A.b", "a-b", "", "a b"}) {
            assertThat(PolicyScheduleStore.QUALIFIED_IDENTIFIER.matcher(bad).matches()).as(bad).isFalse();
        }
    }
}
