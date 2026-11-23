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

package com.dlap.catalog.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The settings of the emitter reject values that would break the rules they serve. */
class LifecycleStatePropertiesTest {

    @Test
    void defaultsAreSafe() {
        LifecycleStateProperties p = new LifecycleStateProperties();
        assertThat(p.getEnginePrincipals()).as("no event is the engine's until the operator says so").isEmpty();
        assertThat(p.getMaxEventIdsPerField()).isEqualTo(100);
        assertThat(p.getDefaultPageSize()).isEqualTo(100);
    }

    @Test
    void outOfRangeValuesAreRejected() {
        LifecycleStateProperties p = new LifecycleStateProperties();
        assertThatThrownBy(() -> p.setMaxEventIdsPerField(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.setDefaultPageSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.setDefaultPageSize(LifecycleStateProperties.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validValuesAreKeptAndANullListIsEmpty() {
        LifecycleStateProperties p = new LifecycleStateProperties();
        p.setMaxEventIdsPerField(5);
        p.setDefaultPageSize(LifecycleStateProperties.MAX_PAGE_SIZE);
        p.setEnginePrincipals(null);
        assertThat(p.getMaxEventIdsPerField()).isEqualTo(5);
        assertThat(p.getDefaultPageSize()).isEqualTo(500);
        assertThat(p.getEnginePrincipals()).isEmpty();
        p.setEnginePrincipals(List.of("svc"));
        assertThat(p.getEnginePrincipals()).containsExactly("svc");
    }
}
