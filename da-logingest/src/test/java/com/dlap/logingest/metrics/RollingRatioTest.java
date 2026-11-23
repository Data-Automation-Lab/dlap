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

package com.dlap.logingest.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The rolling window behind the fill ratio and the heuristic ratio. */
class RollingRatioTest {

    @Test
    void anEmptyWindowHasNoRatioInsteadOfZero() {
        RollingRatio r = new RollingRatio(new MutableClock(LogHealthFixtures.T0), Duration.ofHours(1));

        assertThat(r.read().total()).isZero();
        assertThat(r.read().value()).isNaN();
    }

    @Test
    void countsHitsOverTotal() {
        RollingRatio r = new RollingRatio(new MutableClock(LogHealthFixtures.T0), Duration.ofHours(1));
        for (int i = 0; i < 10; i++) {
            r.add(i < 7);
        }

        assertThat(r.read().hits()).isEqualTo(7);
        assertThat(r.read().total()).isEqualTo(10);
        assertThat(r.read().value()).isEqualTo(0.7);
    }

    @Test
    void eventsLeaveTheWindowAfterAnHour() {
        MutableClock clock = new MutableClock(LogHealthFixtures.T0);
        RollingRatio r = new RollingRatio(clock, Duration.ofHours(1));
        r.add(true);
        clock.advance(Duration.ofMinutes(30));
        r.add(false);

        assertThat(r.read().total()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(31));
        assertThat(r.read().total()).as("the first event is older than an hour").isEqualTo(1);
        assertThat(r.read().value()).isEqualTo(0.0);

        clock.advance(Duration.ofHours(2));
        assertThat(r.read().total()).isZero();
    }

    @Test
    void aBucketIsReusedAfterTheWindowWrapsWithoutKeepingTheOldCounts() {
        MutableClock clock = new MutableClock(LogHealthFixtures.T0);
        RollingRatio r = new RollingRatio(clock, Duration.ofHours(1));
        r.add(true);
        clock.advance(Duration.ofHours(1));
        r.add(false);

        assertThat(r.read().total()).isEqualTo(1);
        assertThat(r.read().hits()).isZero();
    }

    @Test
    void aWindowUnderAMinuteIsRefused() {
        assertThatThrownBy(() -> new RollingRatio(new MutableClock(LogHealthFixtures.T0), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
