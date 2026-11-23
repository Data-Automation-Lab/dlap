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

package com.dlap.discovery.providers.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.ResourceTypeConstants;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.gcp.GcpBigQueryJobResource;
import com.dlap.discovery.crawler.service.gcp.GcpBigQueryJobResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;

import com.google.api.gax.paging.Page;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobStatistics.QueryStatistics;
import com.google.cloud.bigquery.JobStatus;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableId;

/**
 * C7 unit test for {@link GcpBigQueryJobResourceCrawler}: maps a BigQuery
 * {@link Job} to a {@link GcpBigQueryJobResource} (a first-class processing-job
 * asset) and publishes one {@link ResourceEvent}, capturing the referenced
 * (read) and destination (write) tables that make the job a lineage endpoint.
 *
 * <p>The BigQuery client is mocked (LocalStack has no BigQuery - same seam-mock
 * rationale as {@code RdsResourceCrawlerTest}); this pins the model + wiring.
 */
class GcpBigQueryJobResourceCrawlerTest {

    private static final ResourceType JOB_TYPE = new ResourceType(
            ResourceTypeConstants.GCP_BIGQUERY_JOB, "BigQuery Job",
            new CloudService("gcp_bigquery", "BigQuery", CloudProvider.GCP));

    @Test
    @SuppressWarnings("unchecked")
    void mapsQueryJobAndPublishesResourceEvent() {
        // A query job that read two tables and wrote one destination.
        QueryStatistics stats = mock(QueryStatistics.class);
        when(stats.getReferencedTables()).thenReturn(List.of(
                TableId.of("dlap-demo", "raw", "orders"),
                TableId.of("dlap-demo", "raw", "customers")));

        QueryJobConfiguration config = mock(QueryJobConfiguration.class);
        when(config.getQuery()).thenReturn(
                "INSERT INTO mart.orders_enriched SELECT * FROM raw.orders JOIN raw.customers USING (id)");
        when(config.getDestinationTable()).thenReturn(TableId.of("dlap-demo", "mart", "orders_enriched"));

        JobStatus status = mock(JobStatus.class);
        when(status.getState()).thenReturn(JobStatus.State.DONE);

        Job job = mock(Job.class);
        when(job.getJobId()).thenReturn(JobId.of("dlap-demo", "job_abc123"));
        when(job.getStatistics()).thenReturn(stats);
        when(job.getConfiguration()).thenReturn(config);
        when(job.getStatus()).thenReturn(status);

        Page<Job> page = mock(Page.class);
        when(page.iterateAll()).thenReturn(List.of(job));

        BigQuery bigQuery = mock(BigQuery.class);
        when(bigQuery.listJobs(any(BigQuery.JobListOption.class))).thenReturn(page);

        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(ResourceTypeConstants.GCP_BIGQUERY_JOB)).thenReturn(JOB_TYPE);

        KafkaTemplate<String, ResourceEvent> kafka = mock(KafkaTemplate.class);

        GcpBigQueryJobResourceCrawler crawler =
                new GcpBigQueryJobResourceCrawler(registry, kafka, bigQuery);
        ReflectionTestUtils.setField(crawler, "resourceEventsTopic", "dlap.discovery.asset.discovered");

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery", "dlap-demo", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        verify(kafka, times(1))
                .send(eq("dlap.discovery.asset.discovered"), eq("job_abc123"), events.capture());

        GcpBigQueryJobResource resource = (GcpBigQueryJobResource) events.getValue().getPayload();
        assertThat(resource.getResourceId()).isEqualTo("job_abc123");
        assertThat(resource.getCloudProviderEnum()).isEqualTo(CloudProvider.GCP);
        assertThat(resource.getTypeId()).isEqualTo(ResourceTypeConstants.GCP_BIGQUERY_JOB);
        assertThat(resource.getEngine()).isEqualTo("bigquery");
        assertThat(resource.getJobType()).isEqualTo("QUERY");
        assertThat(resource.getState()).isEqualTo("DONE");
        // Canonical id (ADR-0047): bqjob://{project}/{jobId}; the old bigquery://projects/... alias is gone.
        assertThat(resource.getUri()).isEqualTo("bqjob://dlap-demo/job_abc123");
        assertThat(resource.getInputTables()).containsExactlyInAnyOrder(
                "projects/dlap-demo/datasets/raw/tables/orders",
                "projects/dlap-demo/datasets/raw/tables/customers");
        assertThat(resource.getOutputTable()).isEqualTo("projects/dlap-demo/datasets/mart/tables/orders_enriched");
    }

    @Test
    void mapJobToResource_returnsNullForJobWithoutId() {
        Job job = mock(Job.class);
        when(job.getJobId()).thenReturn(null);

        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, ResourceEvent> kafka = mock(KafkaTemplate.class);
        BigQuery bigQuery = mock(BigQuery.class);

        GcpBigQueryJobResourceCrawler crawler =
                new GcpBigQueryJobResourceCrawler(registry, kafka, bigQuery);

        GcpBigQueryJobResource result = crawler.mapJobToResource(job, "dlap-demo", JOB_TYPE);
        assertThat(result).isNull();
    }
}
