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

package com.dlap.discovery.job.executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.config.JobConfiguration;
import com.dlap.discovery.job.executable.CrawlerJavaExecutable;
import com.dlap.discovery.job.executable.SparkExecutable;
import com.dlap.discovery.job.executable.SparkJobConfiguration;
import com.dlap.discovery.job.executor.impl.DefaultJobExecutor;
import com.dlap.discovery.job.executor.impl.SparkJobExecutor;
import com.dlap.discovery.job.integration.spark.SparkClient;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for the various job executors.
 */
@ExtendWith(MockitoExtension.class)
public class JobExecutorTest {

    private DefaultJobExecutor defaultExecutor;
    private SparkJobExecutor sparkExecutor;
    private ExecutorService executorService;
    private AutoCloseable closeable;

    @Mock
    private SparkClient sparkClient;

    @Mock
    private IDiscoveryJobService jobService;

    @Mock
    private JobConfiguration jobConfiguration;

    @Mock
    private IResourceCrawlerRegistry crawlerRegistry;

    @Mock
    private ICatalogService catalogService;

    private DiscoveryJob job;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        executorService = Executors.newSingleThreadExecutor();
        defaultExecutor = new DefaultJobExecutor(executorService, jobService);
        sparkExecutor = new SparkJobExecutor(sparkClient);

        job = new DiscoveryJob(UUID.randomUUID());
        job.setJobName("Test Job");
        job.setAccountId("test-account");
        job.setCloudProvider(CloudProvider.GCP);
        job.setJobType(JobType.RESOURCE_CRAWLER);
        job.setExecutionMode(ExecutionMode.DEFAULT);

        lenient().when(jobService.configureJob(any(DiscoveryJob.class))).thenReturn(jobConfiguration);
        lenient().when(jobConfiguration.withDefaultExecution(any(Callable.class))).thenReturn(jobConfiguration);
        lenient().when(jobConfiguration.build()).thenReturn(job);
    }

    @AfterEach
    void tearDown() throws Exception {
        executorService.shutdown();
        closeable.close();
    }

    @Test
    void testDefaultExecutorSupportsCorrectMode() {
        assertTrue(defaultExecutor.supportsExecutionMode(ExecutionMode.DEFAULT));
        assertFalse(defaultExecutor.supportsExecutionMode(ExecutionMode.SPARK));
        assertFalse(defaultExecutor.supportsExecutionMode(ExecutionMode.NIFI));
        assertEquals(ExecutionMode.DEFAULT, defaultExecutor.getExecutionMode());
    }

    @Test
    void testDefaultExecutorExecutesCallable() throws Exception {
        job.setExecutionMode(ExecutionMode.DEFAULT);
        Callable<Boolean> mockCallable = mock(Callable.class);
        when(mockCallable.call()).thenReturn(true);
        job.setExecutable(new CrawlerJavaExecutable<>(mockCallable));

        Future<?> future = defaultExecutor.execute(job);
        assertNotNull(future);
        future.get(10, TimeUnit.SECONDS);

        verify(mockCallable, times(1)).call();
    }

    @Test
    void testDefaultExecutorThrowsIfWrongMode() {
        // Create a test job with SPARK execution mode
        DiscoveryJob sparkJob = new DiscoveryJob(UUID.randomUUID());
        sparkJob.setJobName("Test Spark Job");
        sparkJob.setAccountId("test-account");
        sparkJob.setCloudProvider(CloudProvider.GCP);
        sparkJob.setJobType(JobType.RESOURCE_CRAWLER);
        sparkJob.setExecutionMode(ExecutionMode.SPARK);

        // Create a mock executable for SPARK mode
        SparkJobConfiguration sparkConfig = mock(SparkJobConfiguration.class);
        SparkExecutable sparkExecutable = mock(SparkExecutable.class);
        when(sparkExecutable.getMode()).thenReturn(ExecutionMode.SPARK);
        sparkJob.setExecutable(sparkExecutable);

        // Now test that DefaultExecutor throws exception for wrong mode
        assertThrows(IllegalStateException.class, () -> defaultExecutor.execute(sparkJob));
    }

    @Test
    void testDefaultExecutorThrowsIfWrongExecutableType() {
        // Create a mock SparkExecutable that reports DEFAULT mode
        SparkJobConfiguration sparkConfig = mock(SparkJobConfiguration.class);
        SparkExecutable sparkExecutable = mock(SparkExecutable.class);
        when(sparkExecutable.getMode()).thenReturn(ExecutionMode.DEFAULT);
        job.setExecutable(sparkExecutable);

        // The executor should throw an exception because the executable is the wrong
        // type
        assertThrows(IllegalStateException.class, () -> defaultExecutor.execute(job));
    }

    @Test
    void testDefaultExecutorThrowsIfCallableIsNull() {
        // Create a mock CrawlerJavaExecutable with null callable
        CrawlerJavaExecutable<?> executable = mock(CrawlerJavaExecutable.class);
        when(executable.getCallable()).thenReturn(null);
        when(executable.getMode()).thenReturn(ExecutionMode.DEFAULT);
        job.setExecutable(executable);

        // The executor should throw an exception because the callable is null
        assertThrows(IllegalStateException.class, () -> defaultExecutor.execute(job));
    }

    @Test
    void testDefaultExecutorControlMethodsReturnFalse() {
        UUID jobId = UUID.randomUUID();
        assertFalse(defaultExecutor.cancelJob(jobId));
        assertFalse(defaultExecutor.pauseJob(jobId));
        assertFalse(defaultExecutor.resumeJob(jobId));
        assertEquals(JobStatus.UNKNOWN, defaultExecutor.getJobStatus(jobId));
    }

    @Test
    void testSparkExecutorSupportsCorrectMode() {
        assertTrue(sparkExecutor.supportsExecutionMode(ExecutionMode.SPARK));
        assertFalse(sparkExecutor.supportsExecutionMode(ExecutionMode.DEFAULT));
        assertFalse(sparkExecutor.supportsExecutionMode(ExecutionMode.NIFI));
        assertEquals(ExecutionMode.SPARK, sparkExecutor.getExecutionMode());
    }

    @Test
    void testSparkExecutorSubmitsJob() throws Exception {
        // Create a test job with SPARK execution mode
        DiscoveryJob sparkJob = new DiscoveryJob(UUID.randomUUID());
        sparkJob.setJobName("Test Spark Job");
        sparkJob.setAccountId("test-account");
        sparkJob.setCloudProvider(CloudProvider.GCP);
        sparkJob.setJobType(JobType.RESOURCE_CRAWLER);
        sparkJob.setExecutionMode(ExecutionMode.SPARK);

        // Create SparkJobConfiguration and SparkExecutable
        SparkJobConfiguration sparkConfig = mock(SparkJobConfiguration.class);
        SparkExecutable sparkExecutable = new SparkExecutable(sparkConfig);
        sparkJob.setExecutable(sparkExecutable);

        String expectedAppId = "app-123";
        when(sparkClient.submitJob(sparkConfig)).thenReturn(expectedAppId);

        Future<?> future = sparkExecutor.execute(sparkJob);
        assertNotNull(future);
        assertEquals(expectedAppId, future.get(1, TimeUnit.SECONDS));

        verify(sparkClient, times(1)).submitJob(sparkConfig);
    }

    @Test
    void testSparkExecutorThrowsIfWrongMode() {
        // Create a new test class that extends the original executor to override the
        // validation
        SparkJobExecutor testExecutor = new SparkJobExecutor(sparkClient) {
            @Override
            public boolean supportsExecutionMode(ExecutionMode mode) {
                // This will make the validation in execute() fail
                return false;
            }
        };

        // Create a test job with SPARK execution mode so setExecutable doesn't fail
        DiscoveryJob sparkJob = new DiscoveryJob(UUID.randomUUID());
        sparkJob.setJobName("Test Spark Job");
        sparkJob.setAccountId("test-account");
        sparkJob.setCloudProvider(CloudProvider.GCP);
        sparkJob.setJobType(JobType.RESOURCE_CRAWLER);
        sparkJob.setExecutionMode(ExecutionMode.SPARK);

        // Create spark executable
        SparkJobConfiguration sparkConfig = mock(SparkJobConfiguration.class);
        SparkExecutable sparkExecutable = new SparkExecutable(sparkConfig);
        sparkJob.setExecutable(sparkExecutable);

        // The modified executor should throw due to the overridden
        // supportsExecutionMode
        assertThrows(IllegalStateException.class, () -> testExecutor.execute(sparkJob));
    }

    @Test
    void testSparkExecutorThrowsIfWrongExecutableType() {
        // Create a test job with SPARK execution mode
        DiscoveryJob sparkJob = new DiscoveryJob(UUID.randomUUID());
        sparkJob.setJobName("Test Spark Job");
        sparkJob.setAccountId("test-account");
        sparkJob.setCloudProvider(CloudProvider.GCP);
        sparkJob.setJobType(JobType.RESOURCE_CRAWLER);
        sparkJob.setExecutionMode(ExecutionMode.SPARK);

        // Create a mock CrawlerJavaExecutable that reports SPARK mode
        Callable<Boolean> mockCallable = mock(Callable.class);
        CrawlerJavaExecutable<?> javaExecutable = mock(CrawlerJavaExecutable.class);
        when(javaExecutable.getMode()).thenReturn(ExecutionMode.SPARK);
        sparkJob.setExecutable(javaExecutable);

        // The executor should throw an exception because the executable is the wrong
        // type
        assertThrows(IllegalStateException.class, () -> sparkExecutor.execute(sparkJob));
    }

    @Test
    void testSparkExecutorCancelsJob() {
        UUID jobId = UUID.randomUUID();
        when(sparkClient.killApplication(jobId.toString())).thenReturn(true);
        assertTrue(sparkExecutor.cancelJob(jobId));
        verify(sparkClient, times(1)).killApplication(jobId.toString());
    }

    @Test
    void testSparkExecutorHandlesCancellationFailure() {
        UUID jobId = UUID.randomUUID();
        when(sparkClient.killApplication(jobId.toString())).thenThrow(new RuntimeException("Spark error"));
        assertFalse(sparkExecutor.cancelJob(jobId));
        verify(sparkClient, times(1)).killApplication(jobId.toString());
    }

    @Test
    void testSparkExecutorPauseResumeReturnFalse() {
        UUID jobId = UUID.randomUUID();
        assertFalse(sparkExecutor.pauseJob(jobId));
        assertFalse(sparkExecutor.resumeJob(jobId));
    }

    @Test
    void testSparkExecutorGetsJobStatus() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(sparkClient.getApplicationStatus(jobId.toString())).thenReturn("RUNNING");
        assertEquals(JobStatus.RUNNING, sparkExecutor.getJobStatus(jobId));
        verify(sparkClient, times(1)).getApplicationStatus(jobId.toString());

        when(sparkClient.getApplicationStatus(jobId.toString())).thenReturn("FINISHED");
        assertEquals(JobStatus.COMPLETED, sparkExecutor.getJobStatus(jobId));

        when(sparkClient.getApplicationStatus(jobId.toString())).thenReturn("FAILED");
        assertEquals(JobStatus.FAILED, sparkExecutor.getJobStatus(jobId));

        when(sparkClient.getApplicationStatus(jobId.toString())).thenReturn("UNKNOWN_STATE");
        assertEquals(JobStatus.UNKNOWN, sparkExecutor.getJobStatus(jobId));

        when(sparkClient.getApplicationStatus(jobId.toString())).thenThrow(new RuntimeException("Spark error"));
        assertEquals(JobStatus.UNKNOWN, sparkExecutor.getJobStatus(jobId));
    }
}
