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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** ADR-0046: unknown is never stale or fresh, and only DUE lets a retire action proceed. */
class RetentionStatusTest {

    @Test
    void valuesAreFrozenForTheCheckConstraint() {
        // ck_asset_retention_status_valid lists these names; renaming one breaks stored rows.
        assertThat(Arrays.stream(RetentionStatus.values()).map(Enum::name))
                .containsExactly("UNKNOWN", "NO_POLICY", "RETAINED", "DUE", "ON_HOLD");
    }

    @ParameterizedTest
    @EnumSource(RetentionStatus.class)
    void onlyDuePermitsRetire(RetentionStatus status) {
        assertThat(status.permitsRetire()).isEqualTo(status == RetentionStatus.DUE);
    }

    @ParameterizedTest
    @EnumSource(RetentionStatus.class)
    void onlyUnknownIsNotKnown(RetentionStatus status) {
        assertThat(status.isKnown()).isEqualTo(status != RetentionStatus.UNKNOWN);
    }

    @Test
    void onHoldNeverPermitsRetire() {
        assertThat(RetentionStatus.ON_HOLD.permitsRetire()).isFalse();
        assertThat(RetentionStatus.ON_HOLD.isKnown()).isTrue();
    }
}
