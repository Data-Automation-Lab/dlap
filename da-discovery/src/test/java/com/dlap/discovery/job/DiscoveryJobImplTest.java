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

package com.dlap.discovery.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.job.executable.Executable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the DiscoveryJob entity.
 */
class DiscoveryJobImplTest {

    private DiscoveryJob job;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        jobId = UUID.randomUUID();
        job = new DiscoveryJob(jobId);
    }

    @Test
    void testJobInitialization() {
        assertNotNull(job.getJobId());
        assertEquals(jobId, job.getId());
        assertNotNull(job.getCreatedAt());
        assertNotNull(job.getUpdatedAt());
        assertEquals(job.getCreatedAt(), job.getUpdatedAt());
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(ExecutionMode.DEFAULT, job.getExecutionMode());
        assertNotNull(job.getParameters());
        assertTrue(job.getParameters().isEmpty());
        assertNotNull(job.getContext());
        assertTrue(job.getContext().isEmpty());
        assertNull(job.getExecutable());
        assertFalse(job.getScheduleInfo().isPresent());
        assertFalse(job.isPeriodicJob());
    }

    @Test
    void testSettersUpdateStateAndTimestamp() throws InterruptedException {
        Instant initialUpdate = job.getUpdatedAt();
        Thread.sleep(1);

        job.setJobName("New Name");
        assertEquals("New Name", job.getJobName());
        assertTrue(job.getUpdatedAt().isAfter(initialUpdate));
        initialUpdate = job.getUpdatedAt();
        Thread.sleep(1);

        job.setJobType(JobType.LOG_ANALYZER);
        assertEquals(JobType.LOG_ANALYZER, job.getJobType());
        assertTrue(job.getUpdatedAt().isAfter(initialUpdate));
        initialUpdate = job.getUpdatedAt();
        Thread.sleep(1);

        job.setCloudProvider(CloudProvider.AWS);
        assertEquals(CloudProvider.AWS, job.getCloudProvider());
        assertTrue(job.getUpdatedAt().isAfter(initialUpdate));
        initialUpdate = job.getUpdatedAt();
        Thread.sleep(1);

        job.setAccountId("new-acc");
        assertEquals("new-acc", job.getAccountId());
        assertTrue(job.getUpdatedAt().isAfter(initialUpdate));
        initialUpdate = job.getUpdatedAt();
        Thread.sleep(1);

        job.setStatus(JobStatus.RUNNING);
        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertTrue(job.getStartedAt().isPresent());
        assertTrue(job.getUpdatedAt().isAfter(initialUpdate));
        initialUpdate = job.getUpdatedAt();
        Thread.sleep(1);

        Map<String, Object> params = new HashMap<>();
        params.put("key", "value");
        job.setParameters(params);
        assertEquals(params, job.getParameters());
        assertNotSame(params, job.getParameters());
        assertTrue(job.getUpdatedAt().isAfter(initialUpdate));
        initialUpdate = job.getUpdatedAt();
        Thread.sleep(1);

        job.setExecutionMode(ExecutionMode.SPARK);
        assertEquals(ExecutionMode.SPARK, job.getExecutionMode());
        assertTrue(job.getUpdatedAt().isAfter(initialUpdate));
        initialUpdate = job.getUpdatedAt();
        Thread.sleep(1);

        Executable exec = Mockito.mock(Executable.class);
        when(exec.getMode()).thenReturn(ExecutionMode.SPARK);
        job.setExecutable(exec);
        assertEquals(exec, job.getExecutable());
        assertTrue(job.getUpdatedAt().isAfter(initialUpdate));
    }

    @Test
    void testSetScheduleInfoUpdatesFlags() {
        assertFalse(job.isPeriodicJob());
        assertTrue(job.isOneTimeJob());

        job.setScheduleInfo("0 * * * * ?");
        assertTrue(job.getScheduleInfo().isPresent());
        assertEquals("0 * * * * ?", job.getScheduleInfo().get());
        assertTrue(job.isPeriodicJob());
        assertFalse(job.isOneTimeJob());

        job.setScheduleInfo(null);
        assertFalse(job.getScheduleInfo().isPresent());
        assertFalse(job.isPeriodicJob());
        assertTrue(job.isOneTimeJob());

        job.setScheduleInfo("   ");
        assertFalse(job.getScheduleInfo().isPresent());
        assertFalse(job.isPeriodicJob());
        assertTrue(job.isOneTimeJob());
    }

    @Test
    void testStatusUpdatesTiming() {
        assertFalse(job.getStartedAt().isPresent());
        assertFalse(job.getCompletedAt().isPresent());

        job.setStatus(JobStatus.PENDING);
        assertFalse(job.getStartedAt().isPresent());
        assertFalse(job.getCompletedAt().isPresent());

        job.setStatus(JobStatus.RUNNING);
        assertTrue(job.getStartedAt().isPresent());
        assertFalse(job.getCompletedAt().isPresent());
        Instant started = job.getStartedAt().get();

        job.setStatus(JobStatus.RUNNING);
        assertEquals(started, job.getStartedAt().get());

        job.setStatus(JobStatus.COMPLETED);
        assertTrue(job.getCompletedAt().isPresent());
        // Due to timing precision, completedAt should be >= startedAt (not strictly after)
        assertTrue(job.getCompletedAt().get().isAfter(started) || job.getCompletedAt().get().equals(started));
        Instant completed = job.getCompletedAt().get();

        // Test that we cannot change status of a completed job
        assertThrows(IllegalStateException.class, () -> job.setStatus(JobStatus.COMPLETED));
        assertEquals(completed, job.getCompletedAt().get());

        // Test that we cannot change to other statuses either
        assertThrows(IllegalStateException.class, () -> job.setStatus(JobStatus.RUNNING));
        assertThrows(IllegalStateException.class, () -> job.setStatus(JobStatus.FAILED));
        assertThrows(IllegalStateException.class, () -> job.setStatus(JobStatus.CANCELLED));
    }

    @Test
    void testSetExecutableValidatesMode() {
        job.setExecutionMode(ExecutionMode.DEFAULT);
        Executable defaultExec = Mockito.mock(Executable.class);
        when(defaultExec.getMode()).thenReturn(ExecutionMode.DEFAULT);

        Executable sparkExec = Mockito.mock(Executable.class);
        when(sparkExec.getMode()).thenReturn(ExecutionMode.SPARK);

        assertDoesNotThrow(() -> job.setExecutable(defaultExec));
        assertEquals(defaultExec, job.getExecutable());

        assertThrows(IllegalArgumentException.class, () -> job.setExecutable(sparkExec));
        assertEquals(defaultExec, job.getExecutable());
    }
}
