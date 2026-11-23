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

package com.dlap.discovery.providers.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.ResourceTypeConstants;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.azure.AzureProcessingJobResource;
import com.dlap.discovery.crawler.service.azure.AzureJobListClient;
import com.dlap.discovery.crawler.service.azure.AzureJobListClient.AzureJob;
import com.dlap.discovery.crawler.service.azure.AzureProcessingJobResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.azure.config.AzureConfigService;

/**
 * V4-AZURE unit test for {@link AzureProcessingJobResourceCrawler}: discovers
 * Azure data-processing jobs across Data Factory / Synapse Spark and publishes a
 * {@link ResourceEvent} per job so each becomes a first-class job asset. The
 * neutral {@code mapJobToResource} is the single mapped unit (mirroring the
 * V4-AWS reference test). The Azure SDK is not on the classpath, so the crawler
 * depends on the {@link AzureJobListClient} SPI - mocked here to return plain
 * job DTOs (no cloud SDK types), the same seam {@code StubAzureJobListClient}
 * fills offline.
 */
class AzureProcessingJobResourceCrawlerTest {

    private static final ResourceType ADF_TYPE = new ResourceType(
            ResourceTypeConstants.AZURE_DATA_FACTORY_PIPELINE, "Data Factory Pipeline",
            new CloudService("azure_data_factory", "Azure Data Factory", CloudProvider.AZURE));
    private static final ResourceType SYNAPSE_TYPE = new ResourceType(
            ResourceTypeConstants.AZURE_SYNAPSE_SPARK_JOB, "Synapse Spark Job",
            new CloudService("azure_synapse", "Azure Synapse", CloudProvider.AZURE));

    private AzureProcessingJobResourceCrawler crawler(CloudHierarchyRegistry registry,
            AzureConfigService configService, AzureJobListClient jobListClient,
            KafkaTemplate<String, ResourceEvent> kafka) {
        AzureProcessingJobResourceCrawler c =
                new AzureProcessingJobResourceCrawler(registry, configService, kafka, jobListClient);
        ReflectionTestUtils.setField(c, "resourceEventsTopic", "dlap.discovery.asset.discovered");
        return c;
    }

    private CloudHierarchyRegistry registryWithAllTypes() {
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(ResourceTypeConstants.AZURE_DATA_FACTORY_PIPELINE)).thenReturn(ADF_TYPE);
        when(registry.getResourceType(ResourceTypeConstants.AZURE_SYNAPSE_SPARK_JOB)).thenReturn(SYNAPSE_TYPE);
        return registry;
    }

    @Test
    void mapJobToResource_mapsDataFactoryToDataflowJob() {
        AzureProcessingJobResourceCrawler crawler = crawler(
                registryWithAllTypes(), mock(AzureConfigService.class), mock(AzureJobListClient.class),
                mockKafka());

        AzureProcessingJobResource r = crawler.mapJobToResource(
                "data_factory", "ingest-pipeline", "DATAFLOW_JOB", "Succeeded", "copy activity",
                List.of("azure://blob/raw"), "azure://blob/curated", "sub-123", "eastus", ADF_TYPE);

        assertThat(r).isNotNull();
        assertThat(r.getResourceId()).isEqualTo("ingest-pipeline");
        assertThat(r.getCloudProviderEnum()).isEqualTo(CloudProvider.AZURE);
        assertThat(r.getTypeId()).isEqualTo(ResourceTypeConstants.AZURE_DATA_FACTORY_PIPELINE);
        assertThat(r.getSource()).isEqualTo("data_factory");
        assertThat(r.getJobType()).isEqualTo("DATAFLOW_JOB");
        assertThat(r.getState()).isEqualTo("Succeeded");
        assertThat(r.getOutputTable()).isEqualTo("azure://blob/curated");
        assertThat(r.getProviderSpecificType()).isEqualTo("AZURE::data_factory_pipeline");
    }

    @Test
    void mapJobToResource_mapsSynapseSparkToSparkJob() {
        AzureProcessingJobResourceCrawler crawler = crawler(
                registryWithAllTypes(), mock(AzureConfigService.class), mock(AzureJobListClient.class),
                mockKafka());

        AzureProcessingJobResource r = crawler.mapJobToResource(
                "synapse_spark", "spark-batch-1", "SPARK_JOB", "InProgress", "spark.read...",
                List.of(), null, "sub-123", "westeurope", SYNAPSE_TYPE);

        assertThat(r).isNotNull();
        assertThat(r.getJobType()).isEqualTo("SPARK_JOB");
        assertThat(r.getTypeId()).isEqualTo(ResourceTypeConstants.AZURE_SYNAPSE_SPARK_JOB);
        assertThat(r.getSource()).isEqualTo("synapse_spark");
        assertThat(r.getProviderSpecificType()).isEqualTo("AZURE::synapse_spark_job");
    }

    @Test
    void mapJobToResource_returnsNullForJobWithoutId() {
        AzureProcessingJobResourceCrawler crawler = crawler(
                mock(CloudHierarchyRegistry.class), mock(AzureConfigService.class),
                mock(AzureJobListClient.class), mockKafka());

        assertThat(crawler.mapJobToResource("data_factory", null, "DATAFLOW_JOB", null, null,
                List.of(), null, "sub", "eastus", ADF_TYPE)).isNull();
        assertThat(crawler.mapJobToResource("data_factory", "  ", "DATAFLOW_JOB", null, null,
                List.of(), null, "sub", "eastus", ADF_TYPE)).isNull();
    }

    /** What the Azure SDK reports as a pipeline's id: its ARM resource ID, in the case ARM returns it. */
    private static final String PIPELINE_ARM_ID = "/subscriptions/11111111-2222-3333-4444-555555555555"
            + "/resourceGroups/Rg-Data/providers/Microsoft.DataFactory/factories/Adf-Prod/pipelines/ingest-pipeline";

    @Test
    @SuppressWarnings("unchecked")
    void performActualDiscovery_publishesEventPerJobAcrossEngines() {
        AzureJobListClient jobListClient = mock(AzureJobListClient.class);
        when(jobListClient.listDataFactoryPipelines(any()))
                .thenReturn(List.of(new AzureJob(
                        PIPELINE_ARM_ID, "ingest-pipeline", "Succeeded", "copy",
                        List.of("azure://blob/raw"), "azure://blob/curated", "eastus")));
        when(jobListClient.listSynapseSparkJobs(any()))
                .thenReturn(List.of(new AzureJob(
                        "spark-batch-1", "spark-batch-1", "InProgress", "spark.read",
                        List.of(), "azure://synapse/out", "westeurope")));

        AzureConfigService configService = mock(AzureConfigService.class);
        when(configService.getRegion()).thenReturn("eastus");
        when(configService.getSubscriptionId()).thenReturn("sub-123");

        KafkaTemplate<String, ResourceEvent> kafka = mockKafka();

        AzureProcessingJobResourceCrawler crawler =
                crawler(registryWithAllTypes(), configService, jobListClient, kafka);

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery",
                "sub-123", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        // 1 Data Factory + 1 Synapse Spark = 2 events.
        verify(kafka, times(2))
                .send(eq("dlap.discovery.asset.discovered"), any(String.class), events.capture());

        Map<String, AzureProcessingJobResource> byId = new HashMap<>();
        events.getAllValues().forEach(e ->
                byId.put(e.getPayload().getResourceId(), (AzureProcessingJobResource) e.getPayload()));

        assertThat(byId.keySet()).containsExactlyInAnyOrder(PIPELINE_ARM_ID, "spark-batch-1");

        assertThat(byId.get(PIPELINE_ARM_ID).getJobType()).isEqualTo("DATAFLOW_JOB");
        // Canonical id (ADR-0047): the lower-cased ARM ID, not azure://data-factory/... (which the
        // normalizer rejects because it collides with a blob path).
        assertThat(byId.get(PIPELINE_ARM_ID).getUri()).isEqualTo(
                "/subscriptions/11111111-2222-3333-4444-555555555555/resourcegroups/rg-data/providers/"
                        + "microsoft.datafactory/factories/adf-prod/pipelines/ingest-pipeline");
        assertThat(byId.get(PIPELINE_ARM_ID).getState()).isEqualTo("Succeeded");
        assertThat(byId.get(PIPELINE_ARM_ID).getOutputTable()).isEqualTo("azure://blob/curated");
        assertThat(byId.get("spark-batch-1").getJobType()).isEqualTo("SPARK_JOB");
        // No canonical scheme covers a Synapse Spark job yet, and azure://synapse/... collides with a
        // blob path in a storage account named synapse, so no uri is written.
        assertThat(byId.get("spark-batch-1").getUri()).isNull();
        assertThat(byId.get("spark-batch-1").getState()).isEqualTo("InProgress");
    }

    @Test
    @SuppressWarnings("unchecked")
    void performActualDiscovery_pipelineIdThatIsNotAnArmIdLeavesTheUriUnset() {
        AzureJobListClient jobListClient = mock(AzureJobListClient.class);
        when(jobListClient.listDataFactoryPipelines(any()))
                .thenReturn(List.of(new AzureJob(
                        "ingest-pipeline", "ingest-pipeline", "Succeeded", "copy", List.of(), null, "eastus")));
        when(jobListClient.listSynapseSparkJobs(any())).thenReturn(List.of());
        AzureConfigService configService = mock(AzureConfigService.class);
        when(configService.getRegion()).thenReturn("eastus");
        KafkaTemplate<String, ResourceEvent> kafka = mockKafka();
        AzureProcessingJobResourceCrawler crawler =
                crawler(registryWithAllTypes(), configService, jobListClient, kafka);

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery",
                "sub-123", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        verify(kafka, times(1))
                .send(eq("dlap.discovery.asset.discovered"), any(String.class), events.capture());
        // A bare pipeline name lacks the resource group and factory, so no identity is guessed.
        assertThat(events.getValue().getPayload().getUri()).isNull();
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, ResourceEvent> mockKafka() {
        return mock(KafkaTemplate.class);
    }
}
