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

package com.dlap.logingest.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Q-661: the one place U+0000 is handled for text excerpts and for stored JSON documents. */
class NulSafeTest {

    private static final char NUL = 0;
    private static final String ESCAPE = "\\u0000";

    @Test
    void stripDropsTheCharacterAndReturnsTheSameInstanceWhenThereIsNone() {
        String clean = "plain";
        assertThat(NulSafe.strip("a" + NUL + "b" + NUL)).isEqualTo("ab");
        assertThat(NulSafe.strip(clean)).isSameAs(clean);
        assertThat(NulSafe.strip(null)).isNull();
    }

    @Test
    void escapeKeepsTheCharacterVisibleAsItsSixCharacterText() {
        assertThat(NulSafe.escape("a" + NUL + "b")).isEqualTo("a" + ESCAPE + "b").hasSize(8);
        assertThat(NulSafe.escape(null)).isNull();
    }

    @Test
    void escapeDeepReachesValuesKeysAndListElementsAtAnyDepth() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("k" + NUL, List.of("x" + NUL, 7, true));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("principal", "ali" + NUL + "ce");
        doc.put("nested", inner);
        doc.put("count", 3);
        doc.put("none", null);

        Map<String, Object> out = NulSafe.escapeDeep(doc);

        assertThat(out).containsEntry("principal", "ali" + ESCAPE + "ce").containsEntry("count", 3)
                .containsEntry("none", null);
        assertThat(out.get("nested")).isEqualTo(Map.of("k" + ESCAPE, List.of("x" + ESCAPE, 7, true)));
        assertThat(doc.get("principal")).as("the input is not changed").isEqualTo("ali" + NUL + "ce");
    }

    @Test
    void aDocumentWithoutNulIsReturnedAsIs() {
        Map<String, Object> doc = Map.of("a", "b", "n", List.of(1, 2));
        assertThat(NulSafe.escapeDeep(doc)).isSameAs(doc);
        assertThat(NulSafe.escapeDeep(null)).isNull();
    }
}
