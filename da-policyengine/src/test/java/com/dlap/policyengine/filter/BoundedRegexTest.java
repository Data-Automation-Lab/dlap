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

package com.dlap.policyengine.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * SEC-04: user-supplied regular expressions are capped at 256 characters and run on RE2/J,
 * which matches in linear time.
 */
class BoundedRegexTest {

    @Test
    void compilesAndFindsAnywhereUnlessAnchored() {
        BoundedRegex r = BoundedRegex.compile("^prod-[a-z]+$");
        assertThat(r.find("prod-db")).isTrue();
        assertThat(r.find("dev-db")).isFalse();
        assertThat(r.find(null)).isFalse();
        assertThat(BoundedRegex.compile("pii").find("customer-pii-2024")).isTrue();
        assertThat(r.source()).isEqualTo("^prod-[a-z]+$");
    }

    @Test
    void patternOfExactlyTheCapIsAccepted() {
        String atCap = "a".repeat(BoundedRegex.MAX_PATTERN_LENGTH);
        assertThat(BoundedRegex.compile(atCap).find("a".repeat(300))).isTrue();
    }

    @Test
    void patternLongerThanTheCapIsRejected() {
        String tooLong = "a".repeat(BoundedRegex.MAX_PATTERN_LENGTH + 1);
        assertThatThrownBy(() -> BoundedRegex.compile(tooLong))
                .isInstanceOf(BoundedRegex.InvalidRegexException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256");
    }

    @Test
    void blankOrNullPatternIsRejected() {
        assertThatThrownBy(() -> BoundedRegex.compile(null)).isInstanceOf(BoundedRegex.InvalidRegexException.class);
        assertThatThrownBy(() -> BoundedRegex.compile("  ")).isInstanceOf(BoundedRegex.InvalidRegexException.class);
    }

    @Test
    void backtrackingOnlyConstructsAreRejectedNotApproximated() {
        // Lookahead and backreferences need a backtracking engine; RE2 refuses them.
        assertThatThrownBy(() -> BoundedRegex.validate("^(?=prod).*"))
                .isInstanceOf(BoundedRegex.InvalidRegexException.class)
                .hasMessageContaining("RE2");
        assertThatThrownBy(() -> BoundedRegex.validate("(a)\\1"))
                .isInstanceOf(BoundedRegex.InvalidRegexException.class);
        assertThatThrownBy(() -> BoundedRegex.validate("([a-z]+"))
                .isInstanceOf(BoundedRegex.InvalidRegexException.class);
    }

    @Test
    void catastrophicPatternRunsInLinearTime() {
        // (a+)+$ against "aaaa...b" takes exponential time on java.util.regex.
        BoundedRegex r = BoundedRegex.compile("(a+)+$");
        String hostile = "a".repeat(50_000) + "b";
        long start = System.nanoTime();
        assertThat(r.find(hostile)).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void equalityFollowsTheSource() {
        assertThat(BoundedRegex.compile("x+")).isEqualTo(BoundedRegex.compile("x+"));
        assertThat(BoundedRegex.compile("x+")).hasSameHashCodeAs(BoundedRegex.compile("x+"));
        assertThat(BoundedRegex.compile("x+")).isNotEqualTo(BoundedRegex.compile("y+"));
    }
}
