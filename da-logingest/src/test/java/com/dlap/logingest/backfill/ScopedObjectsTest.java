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

package com.dlap.logingest.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.backfill.BackfillTestKit.RecordingObjects;
import com.dlap.logingest.transport.objects.JpaProcessedObjectStore;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Q-706: {@code ProcessedObjectStore.attempts} has no default, so a store cannot forget it and silently
 * never reach the read-failure cap. The backfill's scoped stores answer it too: the job's own count.
 */
class ScopedObjectsTest {

    @Test
    void attemptsHasNoDefaultSoEveryStoreMustImplementIt() throws NoSuchMethodException {
        Method attempts = ProcessedObjectStore.class.getMethod("attempts", String.class, String.class, String.class,
                String.class);

        assertThat(Modifier.isAbstract(attempts.getModifiers())).isTrue();
        assertThat(attempts.isDefault()).isFalse();
    }

    @Test
    void theProductionStoreOverridesAttempts() throws NoSuchMethodException {
        Method attempts = JpaProcessedObjectStore.class.getMethod("attempts", String.class, String.class,
                String.class, String.class);

        assertThat(attempts.getDeclaringClass()).isEqualTo(JpaProcessedObjectStore.class);
    }

    @Test
    void aJobsStoreCountsFailuresUnderTheJobsOwnScopeAndNotTheStreams() {
        RecordingObjects real = new RecordingObjects();
        UUID job = UUID.randomUUID();
        ProcessedObjectStore scoped = ScopedObjects.forJob(real, job);
        real.markFailed("b", "s", "k", "e");

        scoped.markFailed("b", "s", "k", "e");
        scoped.markFailed("b", "s", "k", "e");

        assertThat(scoped.attempts("b", "s", "k", "e")).as("the job's two failures, not the stream's one").isEqualTo(2);
        assertThat(real.attempts("b", "s", "k", "e")).as("the stream's count is untouched").isEqualTo(1);
        assertThat(real.attempts("b", ScopedObjects.scopeOf(job), "k", "e")).isEqualTo(2);
    }

    @Test
    void aStoreThatRemembersNothingCountsNothing() {
        ProcessedObjectStore none = ScopedObjects.none();

        none.markFailed("b", "s", "k", "e");

        assertThat(none.attempts("b", "s", "k", "e")).isZero();
    }
}
