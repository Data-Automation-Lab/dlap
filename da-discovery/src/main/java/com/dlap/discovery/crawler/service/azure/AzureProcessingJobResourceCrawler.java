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

package com.dlap.discovery.crawler.service.azure;

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
import com.dlap.common.assetid.AssetProvider;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.DiscoveryConstants;
import com.dlap.discovery.common.constants.ResourceTypeConstants;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.azure.AzureProcessingJobResource;
import com.dlap.discovery.crawler.service.AbstractResourceCrawler;
import com.dlap.discovery.crawler.service.azure.AzureJobListClient.AzureJob;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.azure.config.AzureConfigService;

/**
 * V4-AZURE processing-job scanner: discovers Azure data-processing <em>jobs</em>
 * across two engines and publishes a {@link ResourceEvent} per job so each
 * becomes a first-class job catalog asset via the crawler -&gt; log-spine bridge
 * (ADR-0033 / {@code CrawlerAssetSpineBridge}). Every referenced/destination
 * reference it captures lets lineage draw {@code source -> job -> destination}.
 *
 * <p>Shape mirrors the V4-AWS reference {@code AwsProcessingJobResourceCrawler}:
 * gated on {@code cloud.provider.azure.enabled}, publishes to
 * {@code dlap.discovery.asset.discovered}. The engine-neutral
 * {@link #mapJobToResource} is the single tested unit; it is {@code public} so the
 * provider-package test can drive it directly.
 *
 * <p><strong>SDK seam:</strong> unlike the AWS crawler (which mocks the AWS SDK
 * behind {@code AwsClientFactory}), the Azure SDKs are not in the offline build
 * cache. The crawler therefore depends on the thin {@link AzureJobListClient}
 * SPI, which returns plain {@link AzureJob} DTOs (no Azure SDK types). The default
 * {@link StubAzureJobListClient} returns empty lists offline; a real SDK-backed
 * bean supersedes it ({@code @ConditionalOnMissingBean}) - same pattern as
 * {@code da-mcp-pep}'s {@code PdpClient}.
 *
 * <p>Kind mapping (da-catalog serviceType derivation):
 * Data Factory pipeline -&gt; {@code DATAFLOW_JOB} (orchestrated data movement);
 * Synapse Spark job -&gt; {@code SPARK_JOB}.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.azure.enabled", havingValue = "true", matchIfMissing = false)
public class AzureProcessingJobResourceCrawler extends AbstractResourceCrawler<AzureProcessingJobResource> {

    private static final Logger log = LoggerFactory.getLogger(AzureProcessingJobResourceCrawler.class);

    private final CloudHierarchyRegistry hierarchyRegistry;
    private final AzureConfigService configService;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;
    private final AzureJobListClient jobListClient;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public AzureProcessingJobResourceCrawler(
            CloudHierarchyRegistry hierarchyRegistry,
            AzureConfigService configService,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate,
            AzureJobListClient jobListClient) {
        super(List.of());
        this.hierarchyRegistry = Objects.requireNonNull(hierarchyRegistry, "hierarchyRegistry cannot be null");
        this.configService = Objects.requireNonNull(configService, "configService cannot be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");
        this.jobListClient = Objects.requireNonNull(jobListClient, "jobListClient cannot be null");
        registerType(ResourceTypeConstants.AZURE_DATA_FACTORY_PIPELINE);
        registerType(ResourceTypeConstants.AZURE_SYNAPSE_SPARK_JOB);
        log.info("AzureProcessingJobResourceCrawler initialized for Data Factory / Synapse Spark job types");
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
        return CloudProvider.AZURE;
    }

    /**
     * Required by {@code IResourceCrawler}. C7 lesson: the reference GCP job crawler
     * initially omitted this abstract method and failed to compile - a job crawler
     * extending {@link AbstractResourceCrawler} directly must provide it.
     */
    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing Azure processing-job discovery for job: {}",
                job != null ? job.getJobId() : "<none>");
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous discovery of Azure processing jobs for subscription: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        String region = configService.getRegion();
        String subscription = accountId != null ? accountId : configService.getSubscriptionId();
        log.info(DiscoveryConstants.LOG_DISCOVERY_STARTING, "AzureProcessingJob", region, subscription);
        int discoveredCount = 0;
        try {
            discoveredCount += discoverDataFactoryPipelines(subscription, region);
            discoveredCount += discoverSynapseSparkJobs(subscription, region);
            log.info(DiscoveryConstants.LOG_DISCOVERY_COMPLETED, "AzureProcessingJob", region, discoveredCount);
        } catch (Exception e) {
            log.error(DiscoveryConstants.LOG_DISCOVERY_FAILED, "AzureProcessingJob", region, e.getMessage(), e);
        }
    }

    // --- Data Factory pipelines -> DATAFLOW_JOB ------------------------------

    int discoverDataFactoryPipelines(String subscription, String region) {
        int count = 0;
        ResourceType rt = resolveResourceType(ResourceTypeConstants.AZURE_DATA_FACTORY_PIPELINE);
        for (AzureJob job : jobListClient.listDataFactoryPipelines(subscription)) {
            AzureProcessingJobResource resource = mapJobToResource(
                    "data_factory", job.id(), "DATAFLOW_JOB", job.state(), job.query(),
                    job.inputs(), job.output(), subscription,
                    job.region() != null ? job.region() : region, rt);
            if (resource != null) {
                // Canonical id (ADR-0047): the lower-cased ARM ID of the pipeline. The job list SPI carries
                // only an id, so this works when the client reports the pipeline's ARM ID, which an SDK-backed
                // client is expected to do. An id that is not one (no resource group or factory in it) cannot
                // be turned into one, and the old azure://data-factory/... form is rejected by the normalizer
                // because it collides with a blob path, so the resource then keeps no uri.
                CrawlerAssetIds.assignParsed(resource, job.id(),
                        CrawlerAssetIds.hints(AssetProvider.AZURE, AssetKind.DATAFLOW_JOB, subscription, resource.getRegion()));
                publishResourceEvent(subscription, resource, ChangeType.CREATE, "Data Factory pipeline");
                count++;
            }
        }
        return count;
    }

    // --- Synapse Spark jobs -> SPARK_JOB -------------------------------------

    int discoverSynapseSparkJobs(String subscription, String region) {
        int count = 0;
        ResourceType rt = resolveResourceType(ResourceTypeConstants.AZURE_SYNAPSE_SPARK_JOB);
        for (AzureJob job : jobListClient.listSynapseSparkJobs(subscription)) {
            AzureProcessingJobResource resource = mapJobToResource(
                    "synapse_spark", job.id(), "SPARK_JOB", job.state(), job.query(),
                    job.inputs(), job.output(), subscription,
                    job.region() != null ? job.region() : region, rt);
            if (resource != null) {
                // No canonical scheme covers a Synapse Spark job yet (asset-id.md section 3), and the old
                // azure://synapse/... form collides with a blob path in a storage account named synapse,
                // so the resource keeps no uri and reaches the spine unresolved.
                publishResourceEvent(subscription, resource, ChangeType.CREATE, "Synapse Spark job");
                count++;
            }
        }
        return count;
    }

    /**
     * Map an engine-neutral job description to an {@link AzureProcessingJobResource}.
     * The single tested unit (mirrors AWS's {@code mapJobToResource}); {@code public}
     * so the provider-package test can drive it directly. Returns {@code null} for a
     * job with no usable id.
     */
    public AzureProcessingJobResource mapJobToResource(
            String source, String jobId, String jobType, String state, String query,
            List<String> inputs, String output, String subscription, String region, ResourceType rt) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        if (rt == null) {
            log.warn("ResourceType null for Azure {} job {}; skipping.", source, jobId);
            return null;
        }
        AzureProcessingJobResource resource = new AzureProcessingJobResource(rt, jobId, jobId);
        resource.setAccountId(subscription != null ? subscription : configService.getSubscriptionId());
        resource.setSubscriptionId(subscription);
        resource.setRegion(region);
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
                typeId, "Azure Processing Job",
                new CloudService("azure_processing_job", "Azure Processing Job", CloudProvider.AZURE));
    }

    void publishResourceEvent(String accountId, AzureProcessingJobResource resource,
            ChangeType changeType, String label) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.AZURE.toString(),
                    accountId,
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
