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

package com.dlap.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.dlap.common.model.entity.Asset;

import jakarta.persistence.Column;

/** ADR-0046: each basis names the business-time column it counts from. */
class RetentionBasisTest {

    @Test
    void valuesAreFrozenForTheCheckConstraint() {
        assertThat(Arrays.stream(RetentionBasis.values()).map(Enum::name))
                .containsExactly("CREATED", "LAST_MODIFIED", "LAST_ACCESSED", "FIXED_DATE");
    }

    @Test
    void basisColumnsAreTheAdr0046TimeColumns() {
        assertThat(RetentionBasis.CREATED.basisColumn()).contains("cloud_created_at");
        assertThat(RetentionBasis.LAST_MODIFIED.basisColumn()).contains("last_modified_at");
        assertThat(RetentionBasis.LAST_ACCESSED.basisColumn()).contains("last_accessed_at");
        assertThat(RetentionBasis.FIXED_DATE.basisColumn()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(RetentionBasis.class)
    void onlyFixedDateNeedsNoDays(RetentionBasis basis) {
        assertThat(basis.requiresDays()).isEqualTo(basis != RetentionBasis.FIXED_DATE);
    }

    @Test
    void everyBasisColumnIsMappedOnAsset() {
        Set<String> assetColumns = Arrays.stream(Asset.class.getDeclaredFields())
                .map(f -> f.getAnnotation(Column.class))
                .filter(c -> c != null)
                .map(Column::name)
                .collect(Collectors.toSet());
        for (RetentionBasis basis : RetentionBasis.values()) {
            basis.basisColumn().ifPresent(column -> assertThat(assetColumns).contains(column));
        }
    }
}
