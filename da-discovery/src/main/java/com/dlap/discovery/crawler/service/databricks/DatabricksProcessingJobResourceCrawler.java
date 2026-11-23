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

package com.dlap.discovery.crawler.service.databricks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.dlap.common.assetid.AssetKind;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.DiscoveryConstants;
import com.dlap.discovery.common.constants.ResourceTypeConstants;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.databricks.DatabricksProcessingJobResource;
import com.dlap.discovery.crawler.service.AbstractResourceCrawler;
import com.dlap.discovery.crawler.service.databricks.DatabricksJobListClient.DatabricksJob;
import com.dlap.discovery.event.service.type.ResourceEvent;

/**
 * V4-DBX processing-job scanner: discovers Databricks data-processing
 * <em>jobs</em> across two engines and publishes a {@link ResourceEvent} per job
 * so each becomes a first-class job catalog asset via the crawler -&gt; log-spine
 * bridge (ADR-0033 / {@code CrawlerAssetSpineBridge}). Every referenced/destination
 * reference it captures lets lineage draw {@code source -> job -> destination}.
 *
 * <p>Shape mirrors the V4-AWS reference {@code AwsProcessingJobResourceCrawler}:
 * gated on {@code cloud.provider.databricks.enabled}, publishes to
 * {@code dlap.discovery.asset.discovered}. The engine-neutral
 * {@link #mapJobToResource} is the single tested unit; it is {@code public} so the
 * provider-package test can drive it directly.
 *
 * <p><strong>SDK seam:</strong> the Databricks SDK is not in the offline build
 * cache, so the crawler depends on the thin {@link DatabricksJobListClient} SPI,
 * which returns plain {@link DatabricksJob} DTOs (no Databricks SDK types). The
 * default {@link StubDatabricksJobListClient} returns empty lists offline; a real
 * SDK-backed bean supersedes it ({@code @ConditionalOnMissingBean}) - same pattern
 * as {@code da-mcp-pep}'s {@code PdpClient}.
 *
 * <p>Kind mapping (da-catalog serviceType derivation): both a job run and a
 * Databricks SQL query -&gt; {@code DATABRICKS_JOB} (all Databricks processing
 * endpoints share one neutral job kind).
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.databricks.enabled", havingValue = "true", matchIfMissing = false)
public class DatabricksProcessingJobResourceCrawler
        extends AbstractResourceCrawler<DatabricksProcessingJobResource> {

    private static final Logger log = LoggerFactory.getLogger(DatabricksProcessingJobResourceCrawler.class);

    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;
    private final DatabricksJobListClient jobListClient;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Value("${cloud.provider.databricks.workspace-id:default}")
    private String defaultWorkspaceId;

    @Autowired
    public DatabricksProcessingJobResourceCrawler(
            CloudHierarchyRegistry hierarchyRegistry,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate,
            DatabricksJobListClient jobListClient) {
        super(List.of());
        this.hierarchyRegistry = Objects.requireNonNull(hierarchyRegistry, "hierarchyRegistry cannot be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");
        this.jobListClient = Objects.requireNonNull(jobListClient, "jobListClient cannot be null");
        registerType(ResourceTypeConstants.DATABRICKS_JOB);
        registerType(ResourceTypeConstants.DATABRICKS_SQL_QUERY);
        log.info("DatabricksProcessingJobResourceCrawler initialized for job-run / SQL-query job types");
    }

    private void registerType(String typeId) {
        ResourceType rt = hierarchyRegistry.getResourceType(typeId);
        if (rt != null) {
            getSupportedResourceTypes().add(rt);
        } else {
            log.warn("ResourceType record not found in registry for ID: {}. "
                    + "Falling back to a locally-built type for job discovery.", typeId);
        }
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.DATABRICKS;
    }

    /**
     * Required by {@code IResourceCrawler}. C7 lesson: the reference GCP job crawler
     * initially omitted this abstract method and failed to compile - a job crawler
     * extending {@link AbstractResourceCrawler} directly must provide it.
     */
    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing Databricks processing-job discovery for job: {}",
                job != null ? job.getJobId() : "<none>");
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous discovery of Databricks processing jobs for workspace: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        String workspace = accountId != null ? accountId : defaultWorkspaceId;
        log.info(DiscoveryConstants.LOG_DISCOVERY_STARTING, "DatabricksProcessingJob", "workspace", workspace);
        int discoveredCount = 0;
        try {
            discoveredCount += discoverJobRuns(workspace);
            discoveredCount += discoverSqlQueries(workspace);
            log.info(DiscoveryConstants.LOG_DISCOVERY_COMPLETED, "DatabricksProcessingJob", "workspace", discoveredCount);
        } catch (Exception e) {
            log.error(DiscoveryConstants.LOG_DISCOVERY_FAILED, "DatabricksProcessingJob", "workspace", e.getMessage(), e);
        }
    }

    // --- Job runs -> DATABRICKS_JOB ------------------------------------------

    int discoverJobRuns(String workspace) {
        int count = 0;
        ResourceType rt = resolveResourceType(ResourceTypeConstants.DATABRICKS_JOB);
        for (DatabricksJob job : jobListClient.listJobRuns(workspace)) {
            DatabricksProcessingJobResource resource = mapJobToResource(
                    "job_run", job.id(), "DATABRICKS_JOB", job.state(), job.query(),
                    job.inputs(), job.output(), workspace, rt);
            if (resource != null) {
                // Canonical id (ADR-0047): dbx://{workspaceId}/runs/{runId}. The workspace must be the
                // numeric workspace id (or an adb-{id}.{n}.azuredatabricks.net host); anything else leaves
                // the resource without a uri.
                CrawlerAssetIds.assignBuilt(resource, AssetKind.DATABRICKS_JOB, workspace, "runs", job.id());
                publishResourceEvent(workspace, resource, ChangeType.CREATE, "Databricks job run");
                count++;
            }
        }
        return count;
    }

    // --- SQL queries -> DATABRICKS_JOB ---------------------------------------

    int discoverSqlQueries(String workspace) {
        int count = 0;
        ResourceType rt = resolveResourceType(ResourceTypeConstants.DATABRICKS_SQL_QUERY);
        for (DatabricksJob job : jobListClient.listSqlQueries(workspace)) {
            DatabricksProcessingJobResource resource = mapJobToResource(
                    "sql_query", job.id(), "DATABRICKS_JOB", job.state(), job.query(),
                    job.inputs(), job.output(), workspace, rt);
            if (resource != null) {
                // Canonical id (ADR-0047): dbx://{workspaceId}/queries/{queryId}.
                CrawlerAssetIds.assignBuilt(resource, AssetKind.DATABRICKS_JOB, workspace, "queries", job.id());
                publishResourceEvent(workspace, resource, ChangeType.CREATE, "Databricks SQL query");
                count++;
            }
        }
        return count;
    }

    /**
     * Map an engine-neutral job description to a {@link DatabricksProcessingJobResource}.
     * The single tested unit (mirrors AWS's {@code mapJobToResource}); {@code public}
     * so the provider-package test can drive it directly. Returns {@code null} for a
     * job with no usable id.
     */
    public DatabricksProcessingJobResource mapJobToResource(
            String source, String jobId, String jobType, String state, String query,
            List<String> inputs, String output, String workspace, ResourceType rt) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        if (rt == null) {
            log.warn("ResourceType null for Databricks {} job {}; skipping.", source, jobId);
            return null;
        }
        DatabricksProcessingJobResource resource = new DatabricksProcessingJobResource(rt, jobId, jobId);
        resource.setAccountId(workspace);
        resource.setSource(source);
        resource.setJobType(jobType);
        resource.setState(state);
        resource.setQuery(query);
        resource.setInputTables(inputs != null ? new ArrayList<>(inputs) : new ArrayList<>());
        resource.setOutputTable(output);
        resource.setLastDiscoveredAt(Instant.now());
        resource.setUpdatedAt(Instant.now());
        return resource;
    }

    /**
     * The registry type when configured, else a locally-built one so job discovery
     * still functions before the hierarchy YAML is extended (the type-id is the
     * contract the catalog reads).
     */
    private ResourceType resolveResourceType(String typeId) {
        ResourceType rt = hierarchyRegistry.getResourceType(typeId);
        if (rt != null) {
            return rt;
        }
        for (ResourceType t : getSupportedResourceTypes()) {
            if (typeId.equals(t.id())) {
                return t;
            }
        }
        return new ResourceType(
                typeId, "Databricks Processing Job",
                new CloudService("databricks_processing_job", "Databricks Processing Job", CloudProvider.DATABRICKS));
    }

    void publishResourceEvent(String workspace, DatabricksProcessingJobResource resource,
            ChangeType changeType, String label) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.DATABRICKS.toString(),
                    workspace,
                    changeType,
                    resource);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event);
            log.debug("Published event for discovered {}: {}", label, resource.getResourceId());
        } catch (Exception e) {
            log.error("Error publishing resource event for {} {}: {}",
                    label, resource.getResourceId(), e.getMessage(), e);
        }
    }
}
