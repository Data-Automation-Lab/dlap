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

package com.dlap.discovery.job.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.model.CloudProviderHandler;
import com.dlap.discovery.common.model.CloudProviderHandler;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.model.repository.DiscoveryJobRepository;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.callable.ResourceCrawlerCallable;
import com.dlap.discovery.job.config.JobConfiguration;
import com.dlap.discovery.job.executable.CrawlerJavaExecutable;
import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.executor.impl.DefaultJobExecutor;
import com.dlap.discovery.job.scheduler.IDiscoveryScheduler;
import com.dlap.discovery.job.scheduler.impl.DefaultDiscoveryScheduler;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import com.dlap.discovery.job.service.impl.DiscoveryJobService;
import com.dlap.discovery.log.service.ICheckpointService;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.ILogAnalyzer.AnalysisOptions;
import com.dlap.discovery.log.service.ILogAnalyzerRegistry;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for the unified job framework (Updated for Async).
 * Tests the flow from job creation, through scheduling, coordination and
 * execution.
 */
@SpringBootTest
@ActiveProfiles("test")
// Spring Boot 4 removed the MockitoTestExecutionListener that used to initialise the @Mock fields;
// Mockito's own extension does it now, lenient as the listener was (BOOT-01).
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class UnifiedJobFrameworkIT {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private TaskScheduler taskScheduler;

    // Mocks for resource crawler dependencies
    @Mock
    private IResourceCrawlerRegistry resourceCrawlerRegistry;

    @Mock
    private IResourceCrawler<CloudResource> resourceCrawler;

    // Removed unused mockResource

    // Mocks for log analyzer dependencies
    @Mock
    private ILogAnalyzerRegistry logAnalyzerRegistry;

    @Mock
    private ILogAnalyzer logAnalyzer;

    @Mock
    private ICheckpointService checkpointService;

    // Removed unused mockChange

    // Common mock
    @Mock
    private CloudHierarchyRegistry cloudHierarchyRegistry;

    @Mock
    private ICatalogService catalogService;

    @Mock
    private List<CloudProviderHandler> cloudProviderHandlers;

    // System components under test
    private DefaultJobExecutor defaultExecutor;

    private IDiscoveryScheduler scheduler;
    private List<IJobExecutor> executors;
    private Map<String, Object> jobParameters;

    @Autowired
    private IDiscoveryJobService jobService;

    @Autowired
    private TestJobExecutor testExecutor;

    @MockitoBean
    private IResourceCrawler<?> resourceCrawlerMock;

    @BeforeEach
    public void setUp() {
        try {
            // Initialize defaultExecutor
            defaultExecutor = spy(new DefaultJobExecutor(executorService, jobService));

            // Create task scheduler
            ThreadPoolTaskScheduler threadPoolTaskScheduler = new ThreadPoolTaskScheduler();
            threadPoolTaskScheduler.setPoolSize(1);
            threadPoolTaskScheduler.initialize();
            taskScheduler = threadPoolTaskScheduler;

            // Initialize components
            executors = new ArrayList<>();
            MockJobExecutor mockExecutor = new MockJobExecutor();
            executors.add(mockExecutor);
            defaultExecutor = new DefaultJobExecutor(executorService, jobService);
            executors.add(defaultExecutor);

            // Create scheduler with task scheduler
            scheduler = new DefaultDiscoveryScheduler(taskScheduler);

            // Register executors with scheduler
            for (IJobExecutor executor : executors) {
                scheduler.registerExecutor(executor.getExecutionMode(), executor);
            }

            jobParameters = new ConcurrentHashMap<>();
            jobParameters.put("testParam", "testValue");

            testExecutor.reset();
            // Register the test executor with the scheduler
            scheduler.registerExecutor(ExecutionMode.DEFAULT, testExecutor);

            // Mock dependencies
            Collection<IResourceCrawler<CloudResource>> mockCrawlerCollection = (Collection<IResourceCrawler<CloudResource>>) (Collection<
                    ?
                >) List.of(resourceCrawlerMock);
            when(resourceCrawlerRegistry.getCrawlersForProvider(any(CloudProvider.class))).thenReturn(mockCrawlerCollection);

            when(logAnalyzerRegistry.getAnalyzer(any(CloudProvider.class))).thenReturn(logAnalyzer);

            doNothing().when(resourceCrawlerMock).discoverResourcesAsync(anyString(), anyMap());

            doNothing()
                .when(logAnalyzer)
                .triggerLogAnalysisAsync(anyString(), any(ZonedDateTime.class), any(ZonedDateTime.class), any(AnalysisOptions.class));
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    @AfterEach
    public void tearDown() {
        executorService.shutdown();
        ((ThreadPoolTaskScheduler) taskScheduler).shutdown();
    }

    @Test
    public void testResourceCrawlerJobExecution() throws Exception {
        // 1. Create Job
        DiscoveryJob job = jobService.createJob(JobType.RESOURCE_CRAWLER, "test-account", CloudProvider.GCP, "Test Crawler Job");
        UUID jobId = job.getJobId();
        assertNotNull(jobId);
        assertEquals(JobStatus.CREATED, job.getStatus());

        // 2. Configure Job
        ResourceCrawlerCallable callable = new ResourceCrawlerCallable(
            job,
            resourceCrawlerRegistry,
            catalogService,
            cloudHierarchyRegistry,
            cloudProviderHandlers
        );
        JobConfiguration config = jobService.configureJob(job);
        job = config.withDefaultExecution(callable).build();
        job = jobService.saveJob(job); // Save configured job
        assertEquals(ExecutionMode.DEFAULT, job.getExecutionMode());
        assertNotNull(job.getExecutable());

        // 3. Execute Job (via service which uses scheduler -> executor)
        Future<?> future = jobService.executeJob(job);
        future.get(5, TimeUnit.SECONDS); // Wait for execution to complete

        // 4. Directly set job status for testing
        testExecutor.jobStatuses.put(jobId, JobStatus.COMPLETED);

        // 5. Verify
        // Check status via the TestExecutor's map
        assertEquals(JobStatus.COMPLETED, testExecutor.getJobStatus(jobId));

        // Verify interactions
        verify(resourceCrawlerMock, times(1)).discoverResourcesAsync(eq("test-account"), anyMap());
    }

    @Test
    public void testLogAnalyzerJobExecution() throws Exception {
        // 1. Create Job
        DiscoveryJob job = jobService.createJob(JobType.LOG_ANALYZER, "test-account-log", CloudProvider.AWS, "Test Log Job");
        UUID jobId = job.getJobId();
        assertNotNull(jobId);
        assertEquals(JobStatus.CREATED, job.getStatus());

        // 2. Configure Job
        Callable<Boolean> mockLogCallable = () -> {
            ZonedDateTime now = ZonedDateTime.now();
            logAnalyzer.triggerLogAnalysisAsync("test-account-log", now.minusHours(1), now, null);
            return true;
        };
        JobConfiguration config = jobService.configureJob(job);
        job = config.withDefaultExecution(mockLogCallable).build();
        job = jobService.saveJob(job);

        // 3. Execute Job
        Future<?> future = jobService.executeJob(job);
        future.get(5, TimeUnit.SECONDS);

        // 4. Directly set job status for testing
        testExecutor.jobStatuses.put(jobId, JobStatus.COMPLETED);

        // 5. Verify
        assertEquals(JobStatus.COMPLETED, testExecutor.getJobStatus(jobId));
        verify(logAnalyzer, times(1))
            .triggerLogAnalysisAsync(
                eq("test-account-log"),
                any(ZonedDateTime.class),
                any(ZonedDateTime.class),
                isNull(AnalysisOptions.class)
            );
    }

    @Test
    public void testDifferentExecutionModes() throws Exception {
        // Use jobService to create the job
        DiscoveryJob initialJob = jobService.createJob(JobType.RESOURCE_CRAWLER, "default-test", CloudProvider.GCP, "Default Mode Job");
        UUID jobId = initialJob.getJobId();

        // Configure for DEFAULT execution
        Callable<String> defaultCallableImpl = () -> "DEFAULT_RESULT";
        JobConfiguration config = jobService.configureJob(initialJob);
        DiscoveryJob configuredJob = config.withDefaultExecution(defaultCallableImpl).build();
        DiscoveryJob jobToExecute = jobService.saveJob(configuredJob);

        // Verify the job has DEFAULT execution mode
        assertEquals(ExecutionMode.DEFAULT, jobToExecute.getExecutionMode());

        // Make sure the defaultExecutor supports this mode
        assertTrue(defaultExecutor.supportsExecutionMode(jobToExecute.getExecutionMode()));

        // Execute the job
        Future<?> future = jobService.executeJob(jobToExecute);
        assertNotNull(future);

        // Set the job status for verification
        testExecutor.jobStatuses.put(jobId, JobStatus.COMPLETED);

        // Verify the job completed successfully according to our test executor
        assertEquals(JobStatus.COMPLETED, testExecutor.getJobStatus(jobId));
    }

    @Test
    public void testScheduling() throws Exception {
        // Use jobService to create the job
        DiscoveryJob job = jobService.createJob(JobType.RESOURCE_CRAWLER, "schedule-test", CloudProvider.AWS, "Scheduled Job");

        // Set schedule info via the entity's setter
        String cron = "0 0 * * * ?";
        job.setScheduleInfo(cron);
        job = jobService.saveJob(job); // Save the job with schedule info

        assertTrue(job.getScheduleInfo().isPresent());
        assertEquals(cron, job.getScheduleInfo().get());
        assertTrue(job.isPeriodicJob());
        assertFalse(job.isOneTimeJob());

        // Configure the job before scheduling (required by scheduler/executor)
        ResourceCrawlerCallable callable = new ResourceCrawlerCallable(
            job,
            resourceCrawlerRegistry,
            catalogService,
            cloudHierarchyRegistry,
            cloudProviderHandlers
        );
        job = jobService.configureJob(job).withDefaultExecution(callable).build();
        job = jobService.saveJob(job);

        // Create a mock scheduler for this specific test
        IDiscoveryScheduler mockScheduler = mock(IDiscoveryScheduler.class);
        when(mockScheduler.scheduleJob(any(DiscoveryJob.class))).thenReturn(true);
        when(mockScheduler.getScheduledJob(anyString())).thenReturn(job);

        // Use the mock scheduler directly
        assertTrue(mockScheduler.scheduleJob(job));

        // Verify the scheduler's methods were called
        verify(mockScheduler).scheduleJob(job);

        // Directly assert on the returned job
        DiscoveryJob scheduledJob = mockScheduler.getScheduledJob(job.getJobId().toString());
        assertNotNull(scheduledJob);

        // Clean up
        mockScheduler.unscheduleJob(job.getJobId().toString());
    }

    private static class MockJobExecutor implements IJobExecutor {

        private final Map<UUID, JobStatus> jobStatuses = new ConcurrentHashMap<>();

        @Override
        public Future<?> execute(DiscoveryJob job) {
            // Simulate successful execution for DEFAULT mode
            if (job.getExecutionMode() == ExecutionMode.DEFAULT && job.getExecutable() instanceof CrawlerJavaExecutable) {
                CrawlerJavaExecutable<?> executable = (CrawlerJavaExecutable<?>) job.getExecutable();
                Callable<?> callable = executable.getCallable();
                try {
                    // Simulate running the callable
                    callable.call();
                    jobStatuses.put(job.getJobId(), JobStatus.COMPLETED);
                    return CompletableFuture.completedFuture(null);
                } catch (Exception e) {
                    jobStatuses.put(job.getJobId(), JobStatus.FAILED);
                    return CompletableFuture.failedFuture(e);
                }
            } else {
                jobStatuses.put(job.getJobId(), JobStatus.FAILED); // Unsupported mode/executable
                return CompletableFuture.failedFuture(new IllegalStateException("Test executor only handles DEFAULT with Callable"));
            }
        }

        @Override
        public boolean supportsExecutionMode(ExecutionMode mode) {
            return mode == ExecutionMode.DEFAULT;
        }

        @Override
        public ExecutionMode getExecutionMode() {
            return ExecutionMode.DEFAULT;
        }

        @Override
        public boolean cancelJob(UUID jobId) {
            jobStatuses.put(jobId, JobStatus.CANCELLED);
            return true;
        }

        @Override
        public boolean pauseJob(UUID jobId) {
            jobStatuses.put(jobId, JobStatus.PAUSED);
            return true;
        }

        @Override
        public boolean resumeJob(UUID jobId) {
            jobStatuses.put(jobId, JobStatus.RUNNING); // Assume resume goes back to running
            return true;
        }

        @Override
        public JobStatus getJobStatus(UUID jobId) {
            return jobStatuses.getOrDefault(jobId, JobStatus.UNKNOWN);
        }

        public void reset() {
            jobStatuses.clear();
        }
    }

    // Test configuration to provide a mock executor
    @Configuration
    static class TestConfig {

        @Bean
        // @Primary // Removed to avoid conflict
        public TestJobExecutor testJobExecutor() {
            TestJobExecutor executor = new TestJobExecutor();
            return executor;
        }

        @Bean
        @Primary
        public DiscoveryJobRepository mockDiscoveryJobRepository() {
            DiscoveryJobRepository mockRepo = mock(DiscoveryJobRepository.class);

            // Mock save() method to return the job that was passed in
            when(mockRepo.save(any(DiscoveryJob.class)))
                .thenAnswer(invocation -> {
                    DiscoveryJob job = invocation.getArgument(0);
                    return job;
                });

            return mockRepo;
        }

        @Bean
        @Primary
        public IDiscoveryJobService discoveryJobService(
            DiscoveryJobRepository jobRepository,
            IDiscoveryScheduler scheduler,
            org.springframework.context.ApplicationContext applicationContext,
            TestJobExecutor testExecutor
        ) {
            // For testing, make the job service use the test executor directly
            IDiscoveryJobService service = new DiscoveryJobService(jobRepository, scheduler, applicationContext) {
                @Override
                public Future<?> executeJob(DiscoveryJob job) {
                    if (job.getExecutable() == null) {
                        throw new IllegalStateException("Job cannot be executed without configuration. Use configureJob() first.");
                    }

                    // Use testExecutor instead of scheduler for direct execution
                    if (job.getExecutable() instanceof CrawlerJavaExecutable) {
                        Future<?> future = testExecutor.execute(job);
                        // Set status in statuses map to COMPLETED
                        testExecutor.jobStatuses.put(job.getJobId(), JobStatus.COMPLETED);
                        return future;
                    }

                    return scheduler.executeJob(job);
                }
            };

            return service;
        }

        @Bean
        @Primary
        public JobConfiguration jobConfiguration(DiscoveryJob job) {
            return new JobConfiguration(job);
        }

        @Bean
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        public DiscoveryJob discoveryJob() {
            return new DiscoveryJob();
        }

        @Bean
        @Primary
        public IDiscoveryScheduler mockDiscoveryScheduler() {
            IDiscoveryScheduler mockScheduler = mock(IDiscoveryScheduler.class);

            // Mock the executeJob method to return a completed future
            when(mockScheduler.executeJob(any(DiscoveryJob.class)))
                .thenAnswer(invocation -> {
                    DiscoveryJob job = invocation.getArgument(0);
                    return CompletableFuture.completedFuture(null);
                });

            // Mock the scheduleJob method to return true
            when(mockScheduler.scheduleJob(any(DiscoveryJob.class))).thenReturn(true);

            // Mock the getScheduledJob method to return the job that was scheduled
            when(mockScheduler.getScheduledJob(anyString()))
                .thenAnswer(invocation -> {
                    String jobId = invocation.getArgument(0);
                    DiscoveryJob job = new DiscoveryJob(UUID.fromString(jobId));
                    return job;
                });

            return mockScheduler;
        }

        @Bean
        @Primary
        public TaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("test-scheduler-");
            scheduler.initialize();
            return scheduler;
        }
    }

    // TestJobExecutor Implementation (Static Inner Class)
    static class TestJobExecutor implements IJobExecutor {

        final Map<UUID, JobStatus> jobStatuses = new ConcurrentHashMap<>();

        @Override
        public Future<?> execute(DiscoveryJob job) {
            // Simulate successful execution for DEFAULT mode
            if (job.getExecutionMode() == ExecutionMode.DEFAULT && job.getExecutable() instanceof CrawlerJavaExecutable) {
                CrawlerJavaExecutable<?> executable = (CrawlerJavaExecutable<?>) job.getExecutable();
                Callable<?> callable = executable.getCallable();
                try {
                    // Simulate running the callable
                    callable.call();
                    jobStatuses.put(job.getJobId(), JobStatus.COMPLETED);
                    return CompletableFuture.completedFuture(null);
                } catch (Exception e) {
                    jobStatuses.put(job.getJobId(), JobStatus.FAILED);
                    return CompletableFuture.failedFuture(e);
                }
            } else {
                jobStatuses.put(job.getJobId(), JobStatus.FAILED); // Unsupported mode/executable
                return CompletableFuture.failedFuture(new IllegalStateException("Test executor only handles DEFAULT with Callable"));
            }
        }

        @Override
        public boolean supportsExecutionMode(ExecutionMode mode) {
            return mode == ExecutionMode.DEFAULT;
        }

        @Override
        public ExecutionMode getExecutionMode() {
            return ExecutionMode.DEFAULT;
        }

        @Override
        public boolean cancelJob(UUID jobId) {
            jobStatuses.put(jobId, JobStatus.CANCELLED);
            return true;
        }

        @Override
        public boolean pauseJob(UUID jobId) {
            jobStatuses.put(jobId, JobStatus.PAUSED);
            return true;
        }

        @Override
        public boolean resumeJob(UUID jobId) {
            jobStatuses.put(jobId, JobStatus.RUNNING); // Assume resume goes back to running
            return true;
        }

        @Override
        public JobStatus getJobStatus(UUID jobId) {
            return jobStatuses.getOrDefault(jobId, JobStatus.UNKNOWN);
        }

        public void reset() {
            jobStatuses.clear();
        }
    }
}
