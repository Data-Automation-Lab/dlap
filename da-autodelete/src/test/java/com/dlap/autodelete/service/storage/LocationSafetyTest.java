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

package com.dlap.autodelete.service.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The pre-parse checks the retire path applies to a location. */
class LocationSafetyTest {

    @Test
    void redactDropsTheQueryAndFragmentOfAUrlSoASignatureNeverReachesALog() {
        assertThat(LocationSafety.redact("https://acct.blob.core.windows.net/c/b.csv?sv=2022&sig=SECRET"))
                .isEqualTo("https://acct.blob.core.windows.net/c/b.csv");
        assertThat(LocationSafety.redact("https://h/p#frag?x=1")).isEqualTo("https://h/p");
        assertThat(LocationSafety.redact("\"https://h/p?sig=SECRET\"")).doesNotContain("SECRET");
        assertThat(LocationSafety.redact("s3://b/k?not-a-query")).isEqualTo("s3://b/k?not-a-query");
        assertThat(LocationSafety.redact(null)).isNull();
    }

    @Test
    void carriesUrlSelectorOnlyFlagsHttpUrlsWithAQueryOrFragment() {
        assertThat(LocationSafety.carriesUrlSelector("https://h/p?a=b")).isTrue();
        assertThat(LocationSafety.carriesUrlSelector("HTTP://h/p#f")).isTrue();
        assertThat(LocationSafety.carriesUrlSelector("  https://h/p?a=b")).isTrue();
        assertThat(LocationSafety.carriesUrlSelector("\"https://h/p?a=b\"")).isTrue();
        assertThat(LocationSafety.carriesUrlSelector("https://h/p")).isFalse();
        // A '?' or '#' inside an s3:// or gs:// key is part of the key, not a selector.
        assertThat(LocationSafety.carriesUrlSelector("s3://b/what?.csv")).isFalse();
        assertThat(LocationSafety.carriesUrlSelector("gs://b/tag#1")).isFalse();
        assertThat(LocationSafety.carriesUrlSelector(null)).isFalse();
    }

    @Test
    void hasEdgeWhitespaceFlagsKeysThatDifferFromTheirStrippedForm() {
        assertThat(LocationSafety.hasEdgeWhitespace("dir/file.csv ")).isTrue();
        assertThat(LocationSafety.hasEdgeWhitespace(" dir/file.csv")).isTrue();
        assertThat(LocationSafety.hasEdgeWhitespace("dir/in ner.csv")).isFalse();
        assertThat(LocationSafety.hasEdgeWhitespace("dir/file.csv")).isFalse();
        assertThat(LocationSafety.hasEdgeWhitespace(null)).isFalse();
    }
}
