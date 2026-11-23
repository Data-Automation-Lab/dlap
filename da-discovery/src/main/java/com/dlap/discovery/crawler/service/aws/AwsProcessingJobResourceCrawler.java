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

package com.dlap.discovery.crawler.service.aws;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import com.dlap.discovery.crawler.model.aws.AwsProcessingJobResource;
import com.dlap.discovery.crawler.service.AbstractResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.GetQueryExecutionRequest;
import software.amazon.awssdk.services.athena.model.ListQueryExecutionsRequest;
import software.amazon.awssdk.services.athena.model.ListQueryExecutionsResponse;
import software.amazon.awssdk.services.athena.model.QueryExecution;
import software.amazon.awssdk.services.emr.EmrClient;
import software.amazon.awssdk.services.emr.model.ClusterSummary;
import software.amazon.awssdk.services.emr.model.ListClustersRequest;
import software.amazon.awssdk.services.emr.model.ListClustersResponse;
import software.amazon.awssdk.services.emr.model.ListStepsRequest;
import software.amazon.awssdk.services.emr.model.ListStepsResponse;
import software.amazon.awssdk.services.emr.model.StepSummary;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetJobsRequest;
import software.amazon.awssdk.services.glue.model.GetJobsResponse;
import software.amazon.awssdk.services.glue.model.Job;

/**
 * V4-AWS processing-job scanner: discovers AWS data-processing <em>jobs</em>
 * across three engines and publishes a {@link ResourceEvent} per job so each
 * becomes a first-class job catalog asset via the crawler -&gt; log-spine bridge
 * (ADR-0033 / {@code CrawlerAssetSpineBridge}). Every referenced/destination
 * reference it captures lets lineage draw {@code source -> job -> destination}.
 *
 * <p>Shape mirrors the C7 reference {@code GcpBigQueryJobResourceCrawler}: gated
 * on {@code cloud.provider.aws.enabled}, constructor-injected clients obtained
 * from {@link AwsClientFactory} (the SDK seam a unit test mocks - LocalStack
 * community lacks Glue/Athena/EMR), publishes to
 * {@code dlap.discovery.asset.discovered}. The engine-neutral
 * {@link #mapJobToResource} is the single tested unit (mirroring GCP's
 * {@code mapJobToResource}); it is {@code public} so the provider-package test
 * can drive it directly.
 *
 * <p>Kind mapping (da-catalog serviceType derivation):
 * Glue job -&gt; {@code SPARK_JOB} (Glue runs Spark) or {@code PYTHON_JOB} (Python-shell);
 * Athena query -&gt; {@code REDSHIFT_QUERY} (neutral SQL-analytics kind);
 * EMR step -&gt; {@code SPARK_JOB}.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class AwsProcessingJobResourceCrawler extends AbstractResourceCrawler<AwsProcessingJobResource> {

    private static final Logger log = LoggerFactory.getLogger(AwsProcessingJobResourceCrawler.class);

    private final AWSConfigService configService;
    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;
    private final AwsClientFactory clientFactory;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public AwsProcessingJobResourceCrawler(
            CloudHierarchyRegistry hierarchyRegistry,
            AWSConfigService configService,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate,
            AwsClientFactory clientFactory) {
        super(List.of());
        this.hierarchyRegistry = Objects.requireNonNull(hierarchyRegistry, "hierarchyRegistry cannot be null");
        this.configService = Objects.requireNonNull(configService, "configService cannot be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory cannot be null");
        registerType(ResourceTypeConstants.AWS_GLUE_JOB);
        registerType(ResourceTypeConstants.AWS_ATHENA_QUERY);
        registerType(ResourceTypeConstants.AWS_EMR_STEP);
        log.info("AwsProcessingJobResourceCrawler initialized for Glue/Athena/EMR job types");
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
        return CloudProvider.AWS;
    }

    /**
     * Required by {@code IResourceCrawler}. C7 lesson: the reference GCP job crawler
     * initially omitted this abstract method and failed to compile - a job crawler
     * extending {@link AbstractResourceCrawler} directly must provide it.
     */
    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing AWS processing-job discovery for job: {}",
                job != null ? job.getJobId() : "<none>");
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous discovery of AWS processing jobs for account: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        String region = configService.getRegion();
        String effectiveAccount = accountId != null ? accountId : configService.getAccountId();
        log.info(DiscoveryConstants.LOG_DISCOVERY_STARTING, "AwsProcessingJob", region, effectiveAccount);
        int discoveredCount = 0;
        try {
            discoveredCount += discoverGlueJobs(region, effectiveAccount);
            discoveredCount += discoverAthenaQueries(region, effectiveAccount);
            discoveredCount += discoverEmrSteps(region, effectiveAccount);
            log.info(DiscoveryConstants.LOG_DISCOVERY_COMPLETED, "AwsProcessingJob", region, discoveredCount);
        } catch (AwsServiceException e) {
            log.error("AWS API error during processing-job discovery: {} [Request ID: {}]",
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(),
                    e.requestId(), e);
        } catch (Exception e) {
            log.error(DiscoveryConstants.LOG_DISCOVERY_FAILED, "AwsProcessingJob", region, e.getMessage(), e);
        }
    }

    // --- Glue jobs -> SPARK_JOB / PYTHON_JOB ---------------------------------

    int discoverGlueJobs(String region, String accountId) {
        int count = 0;
        ResourceType rt = resolveResourceType(ResourceTypeConstants.AWS_GLUE_JOB);
        try (GlueClient glue = clientFactory.glueClient(Region.of(region))) {
            String token = null;
            do {
                GetJobsRequest.Builder req = GetJobsRequest.builder();
                if (token != null) {
                    req.nextToken(token);
                }
                GetJobsResponse response = glue.getJobs(req.build());
                for (Job job : response.jobs()) {
                    // Python-shell Glue jobs carry command.name == "pythonshell"; all others
                    // (glueetl / gluestreaming) run Spark.
                    String jobType = "SPARK_JOB";
                    String script = null;
                    if (job.command() != null) {
                        if ("pythonshell".equalsIgnoreCase(job.command().name())) {
                            jobType = "PYTHON_JOB";
                        }
                        script = job.command().scriptLocation();
                    }
                    AwsProcessingJobResource resource = mapJobToResource(
                            "glue", job.name(), jobType, null, script,
                            List.of(), null, accountId, region, rt);
                    if (resource != null) {
                        // Canonical id (ADR-0047): the Glue job ARN; a Python shell job is refined by the kind hint.
                        CrawlerAssetIds.assignParsed(resource,
                                arn(region, "glue", resource.getAccountId(), "job/" + job.name()),
                                CrawlerAssetIds.hints(AssetProvider.AWS,
                                        "PYTHON_JOB".equals(jobType) ? AssetKind.PYTHON_JOB : AssetKind.SPARK_JOB,
                                        resource.getAccountId(), region));
                        publishResourceEvent(accountId, resource, ChangeType.CREATE, "Glue job");
                        count++;
                    }
                }
                token = response.nextToken();
            } while (token != null);
        }
        return count;
    }

    // --- Athena queries -> REDSHIFT_QUERY ------------------------------------

    int discoverAthenaQueries(String region, String accountId) {
        int count = 0;
        ResourceType rt = resolveResourceType(ResourceTypeConstants.AWS_ATHENA_QUERY);
        try (AthenaClient athena = clientFactory.athenaClient(Region.of(region))) {
            ListQueryExecutionsResponse listed =
                    athena.listQueryExecutions(ListQueryExecutionsRequest.builder().build());
            for (String executionId : listed.queryExecutionIds()) {
                QueryExecution qe = athena.getQueryExecution(
                        GetQueryExecutionRequest.builder().queryExecutionId(executionId).build())
                        .queryExecution();
                String state = null;
                String output = null;
                if (qe.status() != null && qe.status().state() != null) {
                    state = qe.status().state().toString();
                }
                if (qe.resultConfiguration() != null) {
                    output = qe.resultConfiguration().outputLocation();
                }
                AwsProcessingJobResource resource = mapJobToResource(
                        "athena", executionId, "REDSHIFT_QUERY", state, qe.query(),
                        List.of(), output, accountId, region, rt);
                if (resource != null) {
                    // Canonical id (ADR-0047): athena://{region}/{account}/{queryExecutionId}. The old
                    // athena://{region}/query/{id} form lacked the account.
                    CrawlerAssetIds.assignBuilt(resource, AssetKind.REDSHIFT_QUERY,
                            region, resource.getAccountId(), executionId);
                    publishResourceEvent(accountId, resource, ChangeType.CREATE, "Athena query");
                    count++;
                }
            }
        }
        return count;
    }

    // --- EMR steps -> SPARK_JOB ----------------------------------------------

    int discoverEmrSteps(String region, String accountId) {
        int count = 0;
        ResourceType rt = resolveResourceType(ResourceTypeConstants.AWS_EMR_STEP);
        try (EmrClient emr = clientFactory.emrClient(Region.of(region))) {
            ListClustersResponse clusters = emr.listClusters(ListClustersRequest.builder().build());
            for (ClusterSummary cluster : clusters.clusters()) {
                ListStepsResponse steps = emr.listSteps(
                        ListStepsRequest.builder().clusterId(cluster.id()).build());
                for (StepSummary step : steps.steps()) {
                    String stepId = cluster.id() + "/" + step.id();
                    String state = null;
                    if (step.status() != null && step.status().state() != null) {
                        state = step.status().state().toString();
                    }
                    AwsProcessingJobResource resource = mapJobToResource(
                            "emr", stepId, "SPARK_JOB", state, step.name(),
                            List.of(), null, accountId, region, rt);
                    if (resource != null) {
                        // Canonical id (ADR-0047): the EMR step ARN.
                        CrawlerAssetIds.assignParsed(resource,
                                arn(region, "elasticmapreduce", resource.getAccountId(),
                                        "cluster/" + cluster.id() + "/step/" + step.id()),
                                CrawlerAssetIds.hints(AssetProvider.AWS, AssetKind.SPARK_JOB,
                                        resource.getAccountId(), region));
                        publishResourceEvent(accountId, resource, ChangeType.CREATE, "EMR step");
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Map an engine-neutral job description to an {@link AwsProcessingJobResource}.
     * The single tested unit (mirrors GCP's {@code mapJobToResource}); {@code public}
     * so the provider-package test can drive it directly. Returns {@code null} for a
     * job with no usable id.
     */
    public AwsProcessingJobResource mapJobToResource(
            String source, String jobId, String jobType, String state, String query,
            List<String> inputs, String output, String accountId, String region, ResourceType rt) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        if (rt == null) {
            log.warn("ResourceType null for AWS {} job {}; skipping.", source, jobId);
            return null;
        }
        AwsProcessingJobResource resource = new AwsProcessingJobResource(rt, jobId, jobId);
        resource.setAccountId(accountId != null ? accountId : configService.getAccountId());
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

    /** An ARN with the partition the region belongs to ({@code aws}, {@code aws-cn}, {@code aws-us-gov}, ...). */
    static String arn(String region, String service, String account, String resource) {
        return "arn:" + partition(region) + ":" + service + ":" + region + ":" + account + ":" + resource;
    }

    private static String partition(String region) {
        String r = region == null ? "" : region.toLowerCase(Locale.ROOT);
        if (r.startsWith("cn-")) {
            return "aws-cn";
        }
        if (r.startsWith("us-gov-")) {
            return "aws-us-gov";
        }
        if (r.startsWith("us-isob-")) {
            return "aws-iso-b";
        }
        if (r.startsWith("us-iso-")) {
            return "aws-iso";
        }
        return "aws";
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
                typeId, "AWS Processing Job",
                new CloudService("aws_processing_job", "AWS Processing Job", CloudProvider.AWS));
    }

    void publishResourceEvent(String accountId, AwsProcessingJobResource resource,
            ChangeType changeType, String label) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.AWS.toString(),
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
