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

package com.dlap.discovery.scheduler;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.client.admin.AdminConnectionDetails;
import com.dlap.discovery.client.admin.IAdminServiceClient;
import com.dlap.discovery.common.model.CloudProviderHandler;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.config.dto.ConnectionDiscoveryConfigDTO;
import com.dlap.discovery.config.service.IDiscoveryConfigService;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.callable.ResourceCrawlerCallable;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Auto-discovery trigger: creates and runs a discovery job for each configured connection
 * on a cron schedule, as a cluster-wide singleton.
 *
 * <p>Without this, da-discovery only crawls when an operator hits
 * {@code POST /api/v1/discovery/scans} manually, so nothing would flow into the catalog
 * from a freshly-connected cloud account without human action.
 *
 * <p>This bean reads the comma-separated list at
 * {@code dlap.discovery.scheduled.connection-ids}, resolves each id through
 * {@link IAdminServiceClient} to an account and provider, and for every connection that
 * is enabled and due creates a job, attaches the default crawler executable and submits it.
 * Attaching the executable is what makes the job run: {@code DiscoveryJobService.executeJob}
 * refuses a job that has none, and an earlier version of this class created jobs that
 * stayed in {@code CREATED} forever because it never attached one. A job that cannot be
 * started is marked {@code FAILED} with the reason, and the connection is not stamped as
 * scanned, so the next tick retries it.
 *
 * <p>The cron expression is {@code dlap.discovery.scheduled.cron} (default: once an hour at
 * minute 17 to spread load). The bean exists only when
 * {@code dlap.discovery.scheduled.enabled=true}.
 *
 * <h2>Singleton across replicas (ShedLock)</h2>
 * Every replica runs this method on the same tick. {@link SchedulerLock} takes the row
 * {@value #LOCK_NAME} in the {@code shedlock} table (Flyway V10) using the database clock,
 * so exactly one replica runs the tick. {@code lockAtMostFor} (default {@code PT30M}) is the
 * crash-safety bound, well above the seconds a tick takes, because the method only submits
 * jobs; {@code lockAtLeastFor} (default {@code PT2M}) keeps a replica whose clock fires a
 * few seconds late from running the same tick again after the winner has already finished.
 * Both are overridable with {@code dlap.discovery.scheduled.lock-at-most-for} and
 * {@code dlap.discovery.scheduled.lock-at-least-for}.
 *
 * <p>The live pull of connections from da-admin-service (instead of the static id list) is
 * not part of this class yet; {@link IAdminServiceClient#listConnections()} is the hook for it.
 */
@Component
@ConditionalOnProperty(name = "dlap.discovery.scheduled.enabled", havingValue = "true")
@Slf4j
public class ScheduledDiscoveryTrigger {

    /** Name of the ShedLock row that serialises the scheduled scan across replicas. */
    static final String LOCK_NAME = "dlap-discovery-scheduled-scan";

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final IDiscoveryJobService jobService;
    private final IAdminServiceClient adminServiceClient;
    private final IDiscoveryConfigService configService;
    private final IResourceCrawlerRegistry crawlerRegistry;
    private final ICatalogService catalogService;
    private final CloudHierarchyRegistry cloudHierarchyRegistry;
    private final List<CloudProviderHandler> cloudProviderHandlers;
    private final Clock clock;

    @Value("${dlap.discovery.scheduled.connection-ids:}")
    private String connectionIdsCsv;

    @Value("${dlap.discovery.scheduled.job-name-prefix:scheduled-scan}")
    private String jobNamePrefix;

    /**
     * Spring constructor.
     *
     * @param jobService             creates, configures and executes jobs
     * @param adminServiceClient     resolves a connection id to an account and provider
     * @param configService          per-connection scan configuration
     * @param crawlerRegistry        crawlers the default executable dispatches to
     * @param catalogService         catalog the crawl results are written to
     * @param cloudHierarchyRegistry resource-type lookup for scoped scans
     * @param cloudProviderHandlers  provider-specific folder handlers
     * @param clock                  time source for scan-due decisions; the system UTC clock
     *                               when the context has no {@link Clock} bean
     */
    @Autowired
    public ScheduledDiscoveryTrigger(IDiscoveryJobService jobService,
                                     IAdminServiceClient adminServiceClient,
                                     IDiscoveryConfigService configService,
                                     IResourceCrawlerRegistry crawlerRegistry,
                                     ICatalogService catalogService,
                                     CloudHierarchyRegistry cloudHierarchyRegistry,
                                     List<CloudProviderHandler> cloudProviderHandlers,
                                     ObjectProvider<Clock> clock) {
        this(jobService, adminServiceClient, configService, crawlerRegistry, catalogService,
                cloudHierarchyRegistry, cloudProviderHandlers, clock.getIfUnique(Clock::systemUTC));
    }

    /**
     * Constructor with an explicit clock, for tests and for callers that already hold one.
     *
     * @param jobService             creates, configures and executes jobs
     * @param adminServiceClient     resolves a connection id to an account and provider
     * @param configService          per-connection scan configuration
     * @param crawlerRegistry        crawlers the default executable dispatches to
     * @param catalogService         catalog the crawl results are written to
     * @param cloudHierarchyRegistry resource-type lookup for scoped scans
     * @param cloudProviderHandlers  provider-specific folder handlers
     * @param clock                  time source for scan-due decisions
     */
    public ScheduledDiscoveryTrigger(IDiscoveryJobService jobService,
                                     IAdminServiceClient adminServiceClient,
                                     IDiscoveryConfigService configService,
                                     IResourceCrawlerRegistry crawlerRegistry,
                                     ICatalogService catalogService,
                                     CloudHierarchyRegistry cloudHierarchyRegistry,
                                     List<CloudProviderHandler> cloudProviderHandlers,
                                     Clock clock) {
        this.jobService = jobService;
        this.adminServiceClient = adminServiceClient;
        this.configService = configService;
        this.crawlerRegistry = crawlerRegistry;
        this.catalogService = catalogService;
        this.cloudHierarchyRegistry = cloudHierarchyRegistry;
        this.cloudProviderHandlers = cloudProviderHandlers;
        this.clock = clock;
    }

    /**
     * Starts a scan for every configured connection that is enabled and due. Runs under a
     * cluster-wide lock, so with several replicas only one of them executes a given tick.
     */
    @Scheduled(cron = "${dlap.discovery.scheduled.cron:0 17 * * * ?}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtMostFor = "${dlap.discovery.scheduled.lock-at-most-for:PT30M}",
            lockAtLeastFor = "${dlap.discovery.scheduled.lock-at-least-for:PT2M}")
    public void runScheduledScans() {
        List<String> connectionIds = parseConnectionIds();
        if (connectionIds.isEmpty()) {
            log.debug("Scheduled discovery: no connection ids configured, skipping");
            return;
        }

        log.info("Scheduled discovery: evaluating {} connection(s)", connectionIds.size());
        int triggered = 0;
        int skipped = 0;
        int failed = 0;
        for (String connectionId : connectionIds) {
            try {
                ConnectionDiscoveryConfigDTO cfg =
                        configService.getConnectionDiscoveryConfig(connectionId).orElse(null);
                Instant now = clock.instant();

                // Per-connection config drives WHETHER and HOW OFTEN to scan.
                if (cfg != null && Boolean.FALSE.equals(cfg.getIsEnabled())) {
                    log.debug("Scheduled discovery: connection {} is disabled in config, skipping", connectionId);
                    skipped++;
                    continue;
                }
                if (cfg != null && !isDueForScan(cfg, now)) {
                    log.debug("Scheduled discovery: connection {} not due yet (interval {}h), skipping",
                            connectionId, cfg.getScanIntervalHours());
                    skipped++;
                    continue;
                }

                AdminConnectionDetails details = adminServiceClient.getConnectionDetails(connectionId);
                if (details == null) {
                    log.warn("Scheduled discovery: admin client returned null for connection {}", connectionId);
                    failed++;
                    continue;
                }
                if (!startScan(connectionId, details, cfg)) {
                    failed++;
                    continue;
                }
                if (cfg != null) {
                    configService.recordScheduledScanStarted(connectionId, now);
                }
                triggered++;
            } catch (Exception e) {
                log.warn("Scheduled discovery: failed to trigger scan for connection {}: {}",
                        connectionId, e.getMessage());
                failed++;
            }
        }
        log.info("Scheduled discovery: triggered={} skipped={} failed={}", triggered, skipped, failed);
    }

    /** A connection is due when it has no interval, has never scanned, or the
     *  interval has elapsed since its last scheduled scan. */
    private boolean isDueForScan(ConnectionDiscoveryConfigDTO cfg, Instant now) {
        Integer intervalHours = cfg.getScanIntervalHours();
        if (intervalHours == null || intervalHours <= 0 || cfg.getLastScanStartedAt() == null) {
            return true;
        }
        Instant nextDue = cfg.getLastScanStartedAt().plus(Duration.ofHours(intervalHours));
        return !now.isBefore(nextDue);
    }

    /**
     * Creates the job, attaches the default crawler executable and submits it.
     *
     * @return true when the job was submitted; false when it could not be started, in which
     *         case it is persisted as {@code FAILED} with the reason
     */
    private boolean startScan(String connectionId, AdminConnectionDetails details,
                              ConnectionDiscoveryConfigDTO cfg) {
        String jobName = jobNamePrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        CloudProvider provider = details.getProvider();
        DiscoveryJob created = jobService.createJob(
                JobType.RESOURCE_CRAWLER,
                details.getAccountId(),
                provider,
                jobName);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("originalScanRequest_cloudConnectionId", connectionId);
        parameters.put("query_cloudConnectionId", connectionId);
        parameters.put("originalScanRequest_scanType", "FULL");
        parameters.put("query_scanType", "FULL");
        parameters.put("triggerSource", "scheduled");

        // Config-driven scope: resourceTypesToInclude actually narrows which
        // crawlers run (ResourceCrawlerCallable reads "resourceTypesToCrawl");
        // exclude + regions are carried for providers that honor them.
        if (cfg != null) {
            if (cfg.getResourceTypesToInclude() != null && !cfg.getResourceTypesToInclude().isEmpty()) {
                parameters.put("resourceTypesToCrawl", cfg.getResourceTypesToInclude());
            }
            if (cfg.getResourceTypesToExclude() != null && !cfg.getResourceTypesToExclude().isEmpty()) {
                parameters.put("resourceTypesToExclude", cfg.getResourceTypesToExclude());
            }
            if (cfg.getSpecificRegions() != null && !cfg.getSpecificRegions().isEmpty()) {
                parameters.put("query_regions", cfg.getSpecificRegions());
            }
        }
        created.setParameters(parameters);
        // Work on the instance saveJob returns: the executable is @Transient, so it must be
        // attached to the final instance after the last save and before executeJob.
        DiscoveryJob job = jobService.saveJob(created);

        try {
            configureDefaultCrawlerExecutable(job);
            jobService.executeJob(job);
            log.info("Scheduled discovery: started job {} for connection {} (provider={}, types={})",
                    job.getJobId(), connectionId, provider,
                    cfg != null ? cfg.getResourceTypesToInclude() : "ALL");
            return true;
        } catch (Exception e) {
            log.warn("Scheduled discovery: could not start job {} for connection {}: {}",
                    job.getJobId(), connectionId, e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Failed to start scheduled scan: " + e.getMessage());
            jobService.saveJob(job);
            return false;
        }
    }

    /**
     * Attaches the default {@link ResourceCrawlerCallable} to the job, the same wiring
     * {@code DiscoveryJobController} uses for a manual scan.
     */
    private void configureDefaultCrawlerExecutable(DiscoveryJob job) {
        ResourceCrawlerCallable callable = new ResourceCrawlerCallable(
                job, crawlerRegistry, catalogService, cloudHierarchyRegistry, cloudProviderHandlers);
        jobService.configureJob(job).withDefaultExecution(callable);
    }

    private List<String> parseConnectionIds() {
        if (connectionIdsCsv == null || connectionIdsCsv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(connectionIdsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Builds the JDBC lock provider used by {@link SchedulerLock}.
     *
     * <p>Uses the database clock ({@code usingDbTime()}) so replica clock skew cannot shorten
     * or extend a lock, and addresses the table by schema because the lock provider issues
     * plain JDBC rather than going through Hibernate's default schema.
     *
     * @param dataSource the service datasource
     * @param schema     schema that holds the {@code shedlock} table (Flyway V10)
     * @return the lock provider
     * @throws IllegalArgumentException if {@code schema} is not a plain SQL identifier
     */
    static LockProvider createLockProvider(DataSource dataSource, String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for the ShedLock table: " + schema);
        }
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(schema + ".shedlock")
                .usingDbTime()
                .build());
    }

    /** Enables {@link SchedulerLock} and supplies the lock provider, only when the trigger exists. */
    @Configuration
    @ConditionalOnProperty(name = "dlap.discovery.scheduled.enabled", havingValue = "true")
    @EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
    static class ShedLockConfiguration {

        @Bean
        LockProvider discoveryLockProvider(DataSource dataSource,
                @Value("${spring.jpa.properties.hibernate.default_schema:dlap_discovery}") String schema) {
            return createLockProvider(dataSource, schema);
        }
    }
}
