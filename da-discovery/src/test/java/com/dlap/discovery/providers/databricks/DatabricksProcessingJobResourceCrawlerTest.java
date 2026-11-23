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

package com.dlap.discovery.providers.databricks;

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
import com.dlap.discovery.crawler.model.databricks.DatabricksProcessingJobResource;
import com.dlap.discovery.crawler.service.databricks.DatabricksJobListClient;
import com.dlap.discovery.crawler.service.databricks.DatabricksJobListClient.DatabricksJob;
import com.dlap.discovery.crawler.service.databricks.DatabricksProcessingJobResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;

/**
 * V4-DBX unit test for {@link DatabricksProcessingJobResourceCrawler}: discovers
 * Databricks jobs across job-run / SQL-query engines and publishes a
 * {@link ResourceEvent} per job so each becomes a first-class job asset. The
 * neutral {@code mapJobToResource} is the single mapped unit (mirroring the
 * V4-AWS reference test). The Databricks SDK is not on the classpath, so the
 * crawler depends on the {@link DatabricksJobListClient} SPI - mocked here to
 * return plain job DTOs (no cloud SDK types), the same seam
 * {@code StubDatabricksJobListClient} fills offline.
 */
class DatabricksProcessingJobResourceCrawlerTest {

    private static final ResourceType JOB_TYPE = new ResourceType(
            ResourceTypeConstants.DATABRICKS_JOB, "Databricks Job",
            new CloudService("databricks_jobs", "Databricks Jobs", CloudProvider.DATABRICKS));
    private static final ResourceType SQL_TYPE = new ResourceType(
            ResourceTypeConstants.DATABRICKS_SQL_QUERY, "Databricks SQL Query",
            new CloudService("databricks_sql", "Databricks SQL", CloudProvider.DATABRICKS));

    private DatabricksProcessingJobResourceCrawler crawler(CloudHierarchyRegistry registry,
            DatabricksJobListClient jobListClient, KafkaTemplate<String, ResourceEvent> kafka) {
        DatabricksProcessingJobResourceCrawler c =
                new DatabricksProcessingJobResourceCrawler(registry, kafka, jobListClient);
        ReflectionTestUtils.setField(c, "resourceEventsTopic", "dlap.discovery.asset.discovered");
        ReflectionTestUtils.setField(c, "defaultWorkspaceId", "ws-1");
        return c;
    }

    private CloudHierarchyRegistry registryWithAllTypes() {
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(ResourceTypeConstants.DATABRICKS_JOB)).thenReturn(JOB_TYPE);
        when(registry.getResourceType(ResourceTypeConstants.DATABRICKS_SQL_QUERY)).thenReturn(SQL_TYPE);
        return registry;
    }

    @Test
    void mapJobToResource_mapsJobRunToDatabricksJob() {
        DatabricksProcessingJobResourceCrawler crawler = crawler(
                registryWithAllTypes(), mock(DatabricksJobListClient.class), mockKafka());

        DatabricksProcessingJobResource r = crawler.mapJobToResource(
                "job_run", "run-42", "DATABRICKS_JOB", "SUCCESS", "notebook: etl",
                List.of("main.raw.events"), "main.curated.events", "ws-1", JOB_TYPE);

        assertThat(r).isNotNull();
        assertThat(r.getResourceId()).isEqualTo("run-42");
        assertThat(r.getCloudProviderEnum()).isEqualTo(CloudProvider.DATABRICKS);
        assertThat(r.getTypeId()).isEqualTo(ResourceTypeConstants.DATABRICKS_JOB);
        assertThat(r.getSource()).isEqualTo("job_run");
        assertThat(r.getJobType()).isEqualTo("DATABRICKS_JOB");
        assertThat(r.getState()).isEqualTo("SUCCESS");
        assertThat(r.getOutputTable()).isEqualTo("main.curated.events");
        assertThat(r.getProviderSpecificType()).isEqualTo("DATABRICKS::job_run");
    }

    @Test
    void mapJobToResource_mapsSqlQueryToDatabricksJob() {
        DatabricksProcessingJobResourceCrawler crawler = crawler(
                registryWithAllTypes(), mock(DatabricksJobListClient.class), mockKafka());

        DatabricksProcessingJobResource r = crawler.mapJobToResource(
                "sql_query", "q-7", "DATABRICKS_JOB", "FINISHED", "SELECT * FROM sales",
                List.of(), null, "ws-1", SQL_TYPE);

        assertThat(r).isNotNull();
        assertThat(r.getJobType()).isEqualTo("DATABRICKS_JOB");
        assertThat(r.getTypeId()).isEqualTo(ResourceTypeConstants.DATABRICKS_SQL_QUERY);
        assertThat(r.getSource()).isEqualTo("sql_query");
        assertThat(r.getQuery()).isEqualTo("SELECT * FROM sales");
        assertThat(r.getProviderSpecificType()).isEqualTo("DATABRICKS::sql_query");
    }

    @Test
    void mapJobToResource_returnsNullForJobWithoutId() {
        DatabricksProcessingJobResourceCrawler crawler = crawler(
                mock(CloudHierarchyRegistry.class), mock(DatabricksJobListClient.class), mockKafka());

        assertThat(crawler.mapJobToResource("job_run", null, "DATABRICKS_JOB", null, null,
                List.of(), null, "ws-1", JOB_TYPE)).isNull();
        assertThat(crawler.mapJobToResource("job_run", "  ", "DATABRICKS_JOB", null, null,
                List.of(), null, "ws-1", JOB_TYPE)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void performActualDiscovery_publishesEventPerJobAcrossEngines() {
        DatabricksJobListClient jobListClient = mock(DatabricksJobListClient.class);
        when(jobListClient.listJobRuns(any()))
                .thenReturn(List.of(new DatabricksJob(
                        "42", "run-42", "SUCCESS", "notebook",
                        List.of("main.raw.events"), "main.curated.events")));
        when(jobListClient.listSqlQueries(any()))
                .thenReturn(List.of(new DatabricksJob(
                        "q-7", "q-7", "FINISHED", "SELECT 1",
                        List.of(), "main.reports.daily")));

        KafkaTemplate<String, ResourceEvent> kafka = mockKafka();

        DatabricksProcessingJobResourceCrawler crawler =
                crawler(registryWithAllTypes(), jobListClient, kafka);

        // The connection's workspace is the numeric workspace id (ADR-0047: dbx://{workspaceId}/...).
        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery",
                "1234567890123456", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        // 1 job run + 1 SQL query = 2 events.
        verify(kafka, times(2))
                .send(eq("dlap.discovery.asset.discovered"), any(String.class), events.capture());

        Map<String, DatabricksProcessingJobResource> byId = new HashMap<>();
        events.getAllValues().forEach(e ->
                byId.put(e.getPayload().getResourceId(), (DatabricksProcessingJobResource) e.getPayload()));

        assertThat(byId.keySet()).containsExactlyInAnyOrder("42", "q-7");

        assertThat(byId.get("42").getJobType()).isEqualTo("DATABRICKS_JOB");
        assertThat(byId.get("42").getSource()).isEqualTo("job_run");
        assertThat(byId.get("42").getUri()).isEqualTo("dbx://1234567890123456/runs/42");
        assertThat(byId.get("42").getState()).isEqualTo("SUCCESS");
        assertThat(byId.get("q-7").getJobType()).isEqualTo("DATABRICKS_JOB");
        assertThat(byId.get("q-7").getSource()).isEqualTo("sql_query");
        assertThat(byId.get("q-7").getUri()).isEqualTo("dbx://1234567890123456/queries/q-7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void performActualDiscovery_withoutNumericWorkspaceLeavesTheUriUnset() {
        DatabricksJobListClient jobListClient = mock(DatabricksJobListClient.class);
        when(jobListClient.listJobRuns(any()))
                .thenReturn(List.of(new DatabricksJob("42", "run-42", "SUCCESS", "notebook", List.of(), null)));
        when(jobListClient.listSqlQueries(any())).thenReturn(List.of());

        KafkaTemplate<String, ResourceEvent> kafka = mockKafka();
        DatabricksProcessingJobResourceCrawler crawler =
                crawler(registryWithAllTypes(), jobListClient, kafka);

        // A workspace that is neither numeric nor an Azure host cannot be part of an identity: the
        // normalizer rejects it and the old databricks://... alias is not written back.
        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery", "default", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        verify(kafka, times(1))
                .send(eq("dlap.discovery.asset.discovered"), any(String.class), events.capture());
        assertThat(events.getValue().getPayload().getUri()).isNull();
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, ResourceEvent> mockKafka() {
        return mock(KafkaTemplate.class);
    }
}
