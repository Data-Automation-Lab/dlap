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

package com.dlap.discovery.crawler.service.gcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.dlap.common.assetid.AssetKind;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.DiscoveryConstants;
import com.dlap.discovery.common.constants.ResourceTypeConstants;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.gcp.GcpBigQueryJobResource;
import com.dlap.discovery.crawler.service.AbstractResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.JobStatistics.QueryStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableId;

/**
 * Reference C7 processing-job scanner: discovers Google BigQuery <em>jobs</em>
 * and publishes a {@link ResourceEvent} per job so each becomes a first-class
 * {@code BIGQUERY_JOB} catalog asset via the crawler -&gt; log-spine bridge
 * (ADR-0033 / {@code CrawlerAssetSpineBridge}). Every referenced/destination
 * table it captures lets lineage draw {@code source -> job -> destination}.
 *
 * <p>Shape mirrors {@code RdsResourceCrawler}: gated on
 * {@code cloud.provider.gcp.enabled}, constructor-injected {@link BigQuery}
 * client (the SDK seam a unit test mocks - LocalStack has no BigQuery), publishes
 * to {@code dlap.discovery.asset.discovered}. Uses the shared
 * {@link CrawlerAssetSpineBridge} downstream so no cloud-SDK types leak past
 * da-discovery.
 *
 * <p>Deferred (TODO, not v0.3.0 slice): Dataflow / Spark / dbt / Databricks /
 * Redshift job scanners follow this same pattern for their engines.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
public class GcpBigQueryJobResourceCrawler extends AbstractResourceCrawler<GcpBigQueryJobResource> {

    private static final Logger log = LoggerFactory.getLogger(GcpBigQueryJobResourceCrawler.class);
    private static final String RESOURCE_TYPE_ID = ResourceTypeConstants.GCP_BIGQUERY_JOB;

    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;
    private final BigQuery bigQuery;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    public GcpBigQueryJobResourceCrawler(
            CloudHierarchyRegistry hierarchyRegistry,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate,
            BigQuery bigQuery) {
        super(List.of());
        this.hierarchyRegistry = Objects.requireNonNull(hierarchyRegistry, "hierarchyRegistry cannot be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");
        this.bigQuery = Objects.requireNonNull(bigQuery, "bigQuery cannot be null");
        ResourceType resourceType = hierarchyRegistry.getResourceType(RESOURCE_TYPE_ID);
        if (resourceType != null) {
            getSupportedResourceTypes().add(resourceType);
            log.info("GcpBigQueryJobResourceCrawler initialized for type: {}", RESOURCE_TYPE_ID);
        } else {
            log.warn("ResourceType record not found in registry for ID: {}. "
                    + "Falling back to a locally-built type for job discovery.", RESOURCE_TYPE_ID);
        }
    }

    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing discovery for GCP BigQuery-job crawl: {}",
                job == null ? "<none>" : job.getJobId());
        // No per-job preparation is required: credentials/project come from the
        // injected BigQuery client and the discovery context. Mirrors the no-op
        // preparation of the sibling GCP crawlers.
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.GCP;
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous discovery of BigQuery jobs for project: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String projectId, Map<String, Object> context) {
        log.info(DiscoveryConstants.LOG_DISCOVERY_STARTING, "BigQueryJob", "global", projectId);
        int discoveredCount = 0;
        ResourceType rt = resolveResourceType();
        try {
            for (Job job : bigQuery.listJobs(BigQuery.JobListOption.allUsers()).iterateAll()) {
                GcpBigQueryJobResource resource = mapJobToResource(job, projectId, rt);
                if (resource != null) {
                    publishResourceEvent(projectId, resource, ChangeType.CREATE);
                    discoveredCount++;
                    log.debug("Published event for discovered BigQuery job: {}", resource.getResourceId());
                }
            }
            log.info(DiscoveryConstants.LOG_DISCOVERY_COMPLETED, "BigQueryJob", "global", discoveredCount);
        } catch (Exception e) {
            log.error(DiscoveryConstants.LOG_DISCOVERY_FAILED, "BigQueryJob", "global", e.getMessage(), e);
        }
    }

    /**
     * Map a BigQuery {@link Job} to a {@link GcpBigQueryJobResource}, extracting
     * the query text and the referenced (read) / destination (write) tables that
     * make the job a lineage endpoint. Returns {@code null} for a job with no
     * usable id.
     */
    // Package-visible for provider-package tests (providers.gcp); see GcpBigQueryJobResourceCrawlerTest.
    public GcpBigQueryJobResource mapJobToResource(Job job, String projectId, ResourceType rt) {
        if (job == null || job.getJobId() == null || job.getJobId().getJob() == null) {
            return null;
        }
        String jobId = job.getJobId().getJob();

        GcpBigQueryJobResource resource = new GcpBigQueryJobResource(rt, jobId, jobId);
        resource.setProjectId(projectId);
        // Canonical id (ADR-0047): bqjob://{project}/{jobId}. The job names its own project; the
        // connection's project is the fallback. The job location is an attribute, not identity,
        // because most job names lack it.
        String jobProject = job.getJobId().getProject();
        CrawlerAssetIds.assignBuilt(resource, AssetKind.BIGQUERY_JOB,
                jobProject != null && !jobProject.isBlank() ? jobProject : projectId, jobId);
        resource.setLastDiscoveredAt(Instant.now());
        resource.setUpdatedAt(Instant.now());
        if (job.getStatus() != null && job.getStatus().getState() != null) {
            resource.setState(job.getStatus().getState().toString());
        }

        JobStatistics stats = job.getStatistics();
        if (stats instanceof QueryStatistics qs) {
            resource.setJobType("QUERY");
            resource.setInputTables(tableUris(qs.getReferencedTables()));
            if (qs.getQueryPlan() != null) {
                // Plan present implies executed query; nothing else needed here.
                log.trace("BigQuery job {} query plan captured", jobId);
            }
        }

        // Query text (best-effort) from the job configuration.
        if (job.getConfiguration() instanceof QueryJobConfiguration qjc) {
            resource.setQuery(qjc.getQuery());
            TableId dest = qjc.getDestinationTable();
            if (dest != null) {
                resource.setOutputTable(tableUri(dest));
            }
            if (resource.getJobType() == null) {
                resource.setJobType("QUERY");
            }
        }
        return resource;
    }

    /** Convert a list of BigQuery {@link TableId}s to stable {@code project.dataset.table} URIs. */
    private static List<String> tableUris(List<TableId> tables) {
        List<String> out = new ArrayList<>();
        if (tables != null) {
            for (TableId t : tables) {
                String uri = tableUri(t);
                if (uri != null) {
                    out.add(uri);
                }
            }
        }
        return out;
    }

    /** A single {@link TableId} as {@code projects/<p>/datasets/<d>/tables/<t>} (matches the lineage extractor's shape). */
    private static String tableUri(TableId t) {
        if (t == null || t.getTable() == null) {
            return null;
        }
        String project = t.getProject();
        String dataset = t.getDataset();
        if (project == null || dataset == null) {
            return null;
        }
        return "projects/" + project + "/datasets/" + dataset + "/tables/" + t.getTable();
    }

    /**
     * The registry type when configured, else a locally-built one so job discovery
     * still functions before the hierarchy YAML is extended (the type-id is the
     * contract the catalog reads).
     */
    private ResourceType resolveResourceType() {
        ResourceType rt = hierarchyRegistry.getResourceType(RESOURCE_TYPE_ID);
        if (rt != null) {
            return rt;
        }
        for (ResourceType t : getSupportedResourceTypes()) {
            if (RESOURCE_TYPE_ID.equals(t.id())) {
                return t;
            }
        }
        return new ResourceType(
                RESOURCE_TYPE_ID, "BigQuery Job",
                new com.dlap.discovery.common.model.CloudService("gcp_bigquery", "BigQuery", CloudProvider.GCP));
    }

    void publishResourceEvent(String projectId, GcpBigQueryJobResource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.GCP.toString(),
                    projectId,
                    changeType,
                    resource);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event);
        } catch (Exception e) {
            log.error("Error publishing resource event for BigQuery job {}: {}",
                    resource.getResourceId(), e.getMessage(), e);
        }
    }
}
