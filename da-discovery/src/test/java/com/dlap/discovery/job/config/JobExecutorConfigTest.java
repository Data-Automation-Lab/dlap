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

package com.dlap.discovery.job.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.executor.impl.DefaultJobExecutor;

/**
 * DEP-01: executor selection fails closed and says how to fix it.
 *
 * <p>The prod profile used to select {@code SPARK} while the Spark executor is only created when
 * {@code discovery.job.executor.spark.enabled=true}, so the application never started under prod.
 * These cases pin the selection rules and the wording of the startup failure that names the
 * missing flag.
 */
class JobExecutorConfigTest {

    private static JobExecutorConfig configFor(String mode) {
        JobExecutorConfig config = new JobExecutorConfig();
        ReflectionTestUtils.setField(config, "executorMode", mode);
        return config;
    }

    /** DEFAULT returns the in-process executor. */
    @Test
    void defaultModeSelectsTheDefaultExecutor() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            DefaultJobExecutor executor = new DefaultJobExecutor(pool, null);

            IJobExecutor selected = configFor("DEFAULT").jobExecutor(executor, null, null);

            assertThat(selected).isSameAs(executor);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The mode is matched case-insensitively, as before. */
    @Test
    void modeIsCaseInsensitive() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            DefaultJobExecutor executor = new DefaultJobExecutor(pool, null);

            assertThat(configFor("default").jobExecutor(executor, null, null)).isSameAs(executor);
        } finally {
            pool.shutdownNow();
        }
    }

    /** SPARK without the executor bean fails and names the flag that creates it. */
    @Test
    void sparkWithoutTheExecutorNamesTheEnableFlag() {
        assertThatThrownBy(() -> configFor("SPARK").jobExecutor(null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("discovery.job.executor.spark.enabled=true")
                .hasMessageContaining("discovery.job.executor.mode=DEFAULT");
    }

    /** NIFI without the executor bean fails and names the flag that creates it. */
    @Test
    void nifiWithoutTheExecutorNamesTheEnableFlag() {
        assertThatThrownBy(() -> configFor("NIFI").jobExecutor(null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("discovery.job.executor.nifi.enabled=true");
    }

    /** DEFAULT without its bean names the missing class instead of a generic message. */
    @Test
    void defaultWithoutTheBeanNamesTheClass() {
        assertThatThrownBy(() -> configFor("DEFAULT").jobExecutor(null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DefaultJobExecutor");
    }

    /** An unknown mode lists the valid ones. */
    @Test
    void unknownModeListsTheValidOnes() {
        assertThatThrownBy(() -> configFor("MAINFRAME").jobExecutor(null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAINFRAME")
                .hasMessageContaining("DEFAULT, SPARK or NIFI");
    }
}
