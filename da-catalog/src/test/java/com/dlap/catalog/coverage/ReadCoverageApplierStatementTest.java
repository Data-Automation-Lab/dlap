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

package com.dlap.catalog.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The statements of {@link ReadCoverageApplier} are built once at class load for each count of
 * service types (rule {@code dlap-java-sql-concatenation}); nothing is formatted when an
 * observation is applied. They run against the real schema in {@link ReadCoverageContainerTest}.
 */
class ReadCoverageApplierStatementTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, ReadCoverageApplier.MAX_KINDS})
    void theInListHoldsOnePlaceholderPerServiceType(int kinds) {
        String in = "service_type IN (" + String.join(", ", java.util.Collections.nCopies(kinds, "?")) + ")";
        assertThat(ReadCoverageApplier.updateStatement(kinds)).contains(in).doesNotContain("%s");
        assertThat(ReadCoverageApplier.countStatement(kinds)).contains(in).doesNotContain("%s");
    }

    @Test
    void theStatementsCarryOnlyPlaceholdersAndConstantText() {
        for (int kinds = 1; kinds <= ReadCoverageApplier.MAX_KINDS; kinds++) {
            assertThat(ReadCoverageApplier.updateStatement(kinds))
                    .startsWith("UPDATE dlap_common.dlap_assets SET ")
                    .doesNotContain(";", "--");
            assertThat(ReadCoverageApplier.countStatement(kinds))
                    .startsWith("SELECT count(*) FROM dlap_common.dlap_assets WHERE ")
                    .doesNotContain(";", "--");
        }
    }

    @Test
    void theBindCountMatchesTheStatement() {
        // 5 SET placeholders, provider + kinds + uri + length + prefix, and the staleness guard
        int kinds = 3;
        assertThat(placeholders(ReadCoverageApplier.updateStatement(kinds))).isEqualTo(5 + 1 + kinds + 3 + 1);
        assertThat(placeholders(ReadCoverageApplier.countStatement(kinds))).isEqualTo(1 + kinds + 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, ReadCoverageApplier.MAX_KINDS + 1, 1000})
    void aCountOutsideTheBuiltRangeIsRejected(int kinds) {
        assertThatThrownBy(() -> ReadCoverageApplier.updateStatement(kinds))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReadCoverageApplier.countStatement(kinds))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static long placeholders(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }
}
