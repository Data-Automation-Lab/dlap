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

package com.dlap.discovery.providers.aws;

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
import com.dlap.discovery.crawler.model.aws.AwsProcessingJobResource;
import com.dlap.discovery.crawler.service.aws.AwsProcessingJobResourceCrawler;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.GetQueryExecutionRequest;
import software.amazon.awssdk.services.athena.model.GetQueryExecutionResponse;
import software.amazon.awssdk.services.athena.model.ListQueryExecutionsRequest;
import software.amazon.awssdk.services.athena.model.ListQueryExecutionsResponse;
import software.amazon.awssdk.services.athena.model.QueryExecution;
import software.amazon.awssdk.services.athena.model.QueryExecutionState;
import software.amazon.awssdk.services.athena.model.QueryExecutionStatus;
import software.amazon.awssdk.services.athena.model.ResultConfiguration;
import software.amazon.awssdk.services.emr.EmrClient;
import software.amazon.awssdk.services.emr.model.ClusterSummary;
import software.amazon.awssdk.services.emr.model.ListClustersRequest;
import software.amazon.awssdk.services.emr.model.ListClustersResponse;
import software.amazon.awssdk.services.emr.model.ListStepsRequest;
import software.amazon.awssdk.services.emr.model.ListStepsResponse;
import software.amazon.awssdk.services.emr.model.StepState;
import software.amazon.awssdk.services.emr.model.StepStatus;
import software.amazon.awssdk.services.emr.model.StepSummary;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetJobsRequest;
import software.amazon.awssdk.services.glue.model.GetJobsResponse;
import software.amazon.awssdk.services.glue.model.Job;
import software.amazon.awssdk.services.glue.model.JobCommand;

/**
 * V4-AWS unit test for {@link AwsProcessingJobResourceCrawler}: discovers AWS
 * data-processing jobs across Glue / Athena / EMR and publishes a
 * {@link ResourceEvent} per job so each becomes a first-class job asset. The
 * neutral {@code mapJobToResource} is the single mapped unit (mirroring the C7
 * GCP reference test); the AWS clients are mocked (LocalStack community lacks
 * Glue/Athena/EMR - same seam-mock rationale as {@code GlueResourceCrawlerTest}).
 */
class AwsProcessingJobResourceCrawlerTest {

    private static final ResourceType GLUE_TYPE = new ResourceType(
            ResourceTypeConstants.AWS_GLUE_JOB, "Glue Job",
            new CloudService("aws_glue", "AWS Glue", CloudProvider.AWS));
    private static final ResourceType ATHENA_TYPE = new ResourceType(
            ResourceTypeConstants.AWS_ATHENA_QUERY, "Athena Query",
            new CloudService("aws_athena", "Amazon Athena", CloudProvider.AWS));
    private static final ResourceType EMR_TYPE = new ResourceType(
            ResourceTypeConstants.AWS_EMR_STEP, "EMR Step",
            new CloudService("aws_emr", "Amazon EMR", CloudProvider.AWS));

    private AwsProcessingJobResourceCrawler crawler(CloudHierarchyRegistry registry,
            AwsClientFactory clientFactory, AWSConfigService configService,
            KafkaTemplate<String, ResourceEvent> kafka) {
        AwsProcessingJobResourceCrawler c =
                new AwsProcessingJobResourceCrawler(registry, configService, kafka, clientFactory);
        ReflectionTestUtils.setField(c, "resourceEventsTopic", "dlap.discovery.asset.discovered");
        return c;
    }

    private CloudHierarchyRegistry registryWithAllTypes() {
        CloudHierarchyRegistry registry = mock(CloudHierarchyRegistry.class);
        when(registry.getResourceType(ResourceTypeConstants.AWS_GLUE_JOB)).thenReturn(GLUE_TYPE);
        when(registry.getResourceType(ResourceTypeConstants.AWS_ATHENA_QUERY)).thenReturn(ATHENA_TYPE);
        when(registry.getResourceType(ResourceTypeConstants.AWS_EMR_STEP)).thenReturn(EMR_TYPE);
        return registry;
    }

    @Test
    void mapJobToResource_mapsGlueSparkJob() {
        AwsProcessingJobResourceCrawler crawler = crawler(
                registryWithAllTypes(), mock(AwsClientFactory.class), mock(AWSConfigService.class),
                mockKafka());

        AwsProcessingJobResource r = crawler.mapJobToResource(
                "glue", "nightly-etl", "SPARK_JOB", null, "s3://scripts/etl.py",
                List.of(), null, "123456789012", "us-east-1", GLUE_TYPE);

        assertThat(r).isNotNull();
        assertThat(r.getResourceId()).isEqualTo("nightly-etl");
        assertThat(r.getCloudProviderEnum()).isEqualTo(CloudProvider.AWS);
        assertThat(r.getTypeId()).isEqualTo(ResourceTypeConstants.AWS_GLUE_JOB);
        assertThat(r.getSource()).isEqualTo("glue");
        assertThat(r.getJobType()).isEqualTo("SPARK_JOB");
        assertThat(r.getProviderSpecificType()).isEqualTo("AWS::glue_job");
    }

    @Test
    void mapJobToResource_mapsAthenaToRedshiftQuery() {
        AwsProcessingJobResourceCrawler crawler = crawler(
                registryWithAllTypes(), mock(AwsClientFactory.class), mock(AWSConfigService.class),
                mockKafka());

        AwsProcessingJobResource r = crawler.mapJobToResource(
                "athena", "qexec-1", "REDSHIFT_QUERY", "SUCCEEDED", "SELECT * FROM sales",
                List.of(), "s3://results/qexec-1/", "123456789012", "us-east-1", ATHENA_TYPE);

        assertThat(r).isNotNull();
        assertThat(r.getJobType()).isEqualTo("REDSHIFT_QUERY");
        assertThat(r.getTypeId()).isEqualTo(ResourceTypeConstants.AWS_ATHENA_QUERY);
        assertThat(r.getState()).isEqualTo("SUCCEEDED");
        assertThat(r.getQuery()).isEqualTo("SELECT * FROM sales");
        assertThat(r.getOutputTable()).isEqualTo("s3://results/qexec-1/");
        assertThat(r.getProviderSpecificType()).isEqualTo("AWS::athena_query");
    }

    @Test
    void mapJobToResource_mapsEmrStepToSparkJob() {
        AwsProcessingJobResourceCrawler crawler = crawler(
                registryWithAllTypes(), mock(AwsClientFactory.class), mock(AWSConfigService.class),
                mockKafka());

        AwsProcessingJobResource r = crawler.mapJobToResource(
                "emr", "j-CLUSTER/s-STEP", "SPARK_JOB", "COMPLETED", "spark-submit app.jar",
                List.of(), null, "123456789012", "us-west-2", EMR_TYPE);

        assertThat(r).isNotNull();
        assertThat(r.getJobType()).isEqualTo("SPARK_JOB");
        assertThat(r.getTypeId()).isEqualTo(ResourceTypeConstants.AWS_EMR_STEP);
        assertThat(r.getProviderSpecificType()).isEqualTo("AWS::emr_step");
    }

    @Test
    void mapJobToResource_returnsNullForJobWithoutId() {
        AwsProcessingJobResourceCrawler crawler = crawler(
                mock(CloudHierarchyRegistry.class), mock(AwsClientFactory.class),
                mock(AWSConfigService.class), mockKafka());

        assertThat(crawler.mapJobToResource("glue", null, "SPARK_JOB", null, null,
                List.of(), null, "acct", "us-east-1", GLUE_TYPE)).isNull();
        assertThat(crawler.mapJobToResource("glue", "  ", "SPARK_JOB", null, null,
                List.of(), null, "acct", "us-east-1", GLUE_TYPE)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void performActualDiscovery_publishesEventPerJobAcrossEngines() {
        // Glue: one Spark job (glueetl) + one Python-shell job.
        GlueClient glue = mock(GlueClient.class);
        when(glue.getJobs(any(GetJobsRequest.class))).thenReturn(GetJobsResponse.builder()
                .jobs(
                        Job.builder().name("spark-etl")
                                .command(JobCommand.builder().name("glueetl")
                                        .scriptLocation("s3://scripts/spark.py").build())
                                .build(),
                        Job.builder().name("py-shell")
                                .command(JobCommand.builder().name("pythonshell")
                                        .scriptLocation("s3://scripts/shell.py").build())
                                .build())
                .build());

        // Athena: one query execution.
        AthenaClient athena = mock(AthenaClient.class);
        when(athena.listQueryExecutions(any(ListQueryExecutionsRequest.class)))
                .thenReturn(ListQueryExecutionsResponse.builder()
                        .queryExecutionIds("qexec-1").build());
        when(athena.getQueryExecution(any(GetQueryExecutionRequest.class)))
                .thenReturn(GetQueryExecutionResponse.builder()
                        .queryExecution(QueryExecution.builder()
                                .queryExecutionId("qexec-1")
                                .query("SELECT * FROM analytics.events")
                                .status(QueryExecutionStatus.builder()
                                        .state(QueryExecutionState.SUCCEEDED).build())
                                .resultConfiguration(ResultConfiguration.builder()
                                        .outputLocation("s3://results/qexec-1/").build())
                                .build())
                        .build());

        // EMR: one cluster with one step.
        EmrClient emr = mock(EmrClient.class);
        when(emr.listClusters(any(ListClustersRequest.class)))
                .thenReturn(ListClustersResponse.builder()
                        .clusters(ClusterSummary.builder().id("j-CLUSTER1").build()).build());
        when(emr.listSteps(any(ListStepsRequest.class)))
                .thenReturn(ListStepsResponse.builder()
                        .steps(StepSummary.builder().id("s-STEP1").name("spark-step")
                                .status(StepStatus.builder().state(StepState.COMPLETED).build())
                                .build())
                        .build());

        AwsClientFactory clientFactory = mock(AwsClientFactory.class);
        when(clientFactory.glueClient(any(Region.class))).thenReturn(glue);
        when(clientFactory.athenaClient(any(Region.class))).thenReturn(athena);
        when(clientFactory.emrClient(any(Region.class))).thenReturn(emr);

        AWSConfigService configService = mock(AWSConfigService.class);
        when(configService.getRegion()).thenReturn("us-east-1");
        when(configService.getAccountId()).thenReturn("123456789012");

        KafkaTemplate<String, ResourceEvent> kafka = mockKafka();

        AwsProcessingJobResourceCrawler crawler =
                crawler(registryWithAllTypes(), clientFactory, configService, kafka);

        ReflectionTestUtils.invokeMethod(crawler, "performActualDiscovery",
                "123456789012", new HashMap<String, Object>());

        ArgumentCaptor<ResourceEvent> events = ArgumentCaptor.forClass(ResourceEvent.class);
        // 2 Glue + 1 Athena + 1 EMR = 4 events.
        verify(kafka, times(4))
                .send(eq("dlap.discovery.asset.discovered"), any(String.class), events.capture());

        Map<String, AwsProcessingJobResource> byId = new HashMap<>();
        events.getAllValues().forEach(e ->
                byId.put(e.getPayload().getResourceId(), (AwsProcessingJobResource) e.getPayload()));

        assertThat(byId.keySet()).containsExactlyInAnyOrder(
                "spark-etl", "py-shell", "qexec-1", "j-CLUSTER1/s-STEP1");

        assertThat(byId.get("spark-etl").getJobType()).isEqualTo("SPARK_JOB");
        assertThat(byId.get("spark-etl").getUri())
                .isEqualTo("arn:aws:glue:us-east-1:123456789012:job/spark-etl");
        assertThat(byId.get("py-shell").getJobType()).isEqualTo("PYTHON_JOB");
        // Canonical ids (ADR-0047): the ARN for a Glue job and an EMR step, and the account-qualified
        // athena://{region}/{account}/{id} for a query (the old athena://{region}/query/{id} lacked the account).
        assertThat(byId.get("py-shell").getUri())
                .isEqualTo("arn:aws:glue:us-east-1:123456789012:job/py-shell");
        assertThat(byId.get("qexec-1").getUri()).isEqualTo("athena://us-east-1/123456789012/qexec-1");
        assertThat(byId.get("j-CLUSTER1/s-STEP1").getUri())
                .isEqualTo("arn:aws:elasticmapreduce:us-east-1:123456789012:cluster/j-CLUSTER1/step/s-STEP1");
        assertThat(byId.get("qexec-1").getJobType()).isEqualTo("REDSHIFT_QUERY");
        assertThat(byId.get("qexec-1").getState()).isEqualTo("SUCCEEDED");
        assertThat(byId.get("qexec-1").getOutputTable()).isEqualTo("s3://results/qexec-1/");
        assertThat(byId.get("j-CLUSTER1/s-STEP1").getJobType()).isEqualTo("SPARK_JOB");
        assertThat(byId.get("j-CLUSTER1/s-STEP1").getState()).isEqualTo("COMPLETED");
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, ResourceEvent> mockKafka() {
        return mock(KafkaTemplate.class);
    }
}
