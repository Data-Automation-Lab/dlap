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

package com.dlap.logingest.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.quarantine.QuarantineRetentionSweeper;
import java.lang.reflect.Method;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Plan rule R7: a singleton scheduled job holds a ShedLock lock whose most-for is at least twice the
 * expected run, under a name of its own. The two singleton jobs of LOG-03 are the quarantine retention
 * sweep and the binding-status publisher; the snapshot refresh is deliberately per instance (each
 * replica exports its own gauges) and holds no lock.
 */
class SingletonJobsLockTest {

    private static SchedulerLock lock(Class<?> type, String method) throws NoSuchMethodException {
        Method m = type.getMethod(method);
        assertThat(m.getAnnotation(Scheduled.class)).as("%s#%s is scheduled", type.getSimpleName(), method).isNotNull();
        return m.getAnnotation(SchedulerLock.class);
    }

    @Test
    void theQuarantineRetentionSweepIsASingleton() throws Exception {
        SchedulerLock l = lock(QuarantineRetentionSweeper.class, "scheduledPrune");

        assertThat(l).isNotNull();
        assertThat(l.name()).isEqualTo(QuarantineRetentionSweeper.LOCK_NAME).isEqualTo("logingest-quarantine-retention");
        assertThat(Duration.parse(l.lockAtMostFor())).isGreaterThanOrEqualTo(Duration.ofHours(2));
        assertThat(Duration.parse(l.lockAtLeastFor())).isPositive();
    }

    @Test
    void theStatusPublisherIsASingletonWhoseLockOutlastsTwiceItsInterval() throws Exception {
        SchedulerLock l = lock(BindingStatusPublisher.class, "scheduledPublish");
        Scheduled s = BindingStatusPublisher.class.getMethod("scheduledPublish").getAnnotation(Scheduled.class);

        assertThat(l).isNotNull();
        assertThat(l.name()).isEqualTo(BindingStatusPublisher.LOCK_NAME).isEqualTo("logingest-binding-status");
        Duration interval = Duration.parse("PT1M");
        assertThat(s.fixedDelayString()).contains("dlap.logingest.health.status-interval:PT1M");
        assertThat(Duration.parse(l.lockAtMostFor())).isGreaterThanOrEqualTo(interval.multipliedBy(2));
    }

    @Test
    void theLockNamesAreDistinctFromTheOtherJobs() {
        assertThat(java.util.List.of(QuarantineRetentionSweeper.LOCK_NAME, BindingStatusPublisher.LOCK_NAME,
                com.dlap.logingest.retention.LogEventRetentionSweeper.LOCK_NAME)).doesNotHaveDuplicates();
        assertThat(QuarantineRetentionSweeper.LOCK_NAME.length()).as("the shedlock name column is 64").isLessThan(64);
        assertThat(BindingStatusPublisher.LOCK_NAME.length()).isLessThan(64);
    }

    @Test
    void theSnapshotRefreshIsPerInstanceAndTakesNoLock() throws Exception {
        assertThat(lock(HealthSnapshotService.class, "scheduledRefresh")).isNull();
    }
}
