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

package com.dlap.discovery.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.discovery.providers.aws.config.AwsClientFactory;
import com.dlap.discovery.providers.aws.coverage.AwsReadCoverageCollector;
import com.dlap.discovery.providers.aws.coverage.AwsReadCoverageConfiguration;
import com.dlap.discovery.providers.azure.auth.AzureAuthenticationService;
import com.dlap.discovery.providers.azure.config.AzureProperties;
import com.dlap.discovery.providers.azure.coverage.AzureReadCoverageCollector;
import com.dlap.discovery.providers.azure.coverage.AzureReadCoverageConfiguration;
import com.dlap.discovery.providers.gcp.auth.GCPAuthenticationServiceImpl;
import com.dlap.discovery.providers.gcp.coverage.GcpReadCoverageCollector;
import com.dlap.discovery.providers.gcp.coverage.GcpReadCoverageConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * COV-01: everything is off by default, and each cloud's collector appears only behind its own
 * switches. Nothing here calls a cloud: credentials are obtained on first use.
 */
class ReadCoverageWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReadCoverageConfiguration.class, AwsReadCoverageConfiguration.class,
                    GcpReadCoverageConfiguration.class, AzureReadCoverageConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean("stringKafkaTemplate", KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(GCPAuthenticationServiceImpl.class, () -> mock(GCPAuthenticationServiceImpl.class))
            .withBean(AwsClientFactory.class, () -> mock(AwsClientFactory.class))
            .withBean(AzureAuthenticationService.class, () -> mock(AzureAuthenticationService.class))
            .withBean(AzureProperties.class, () -> mock(AzureProperties.class));

    @Test
    void offByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ReadCoverageCollectionJob.class);
            assertThat(ctx).doesNotHaveBean(ReadCoverageCollector.class);
        });
    }

    @Test
    void theJobWithoutACloudSwitchHasNoCollector() {
        runner.withPropertyValues("dlap.discovery.coverage.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(ReadCoverageCollectionJob.class);
            assertThat(ctx).doesNotHaveBean(ReadCoverageCollector.class);
        });
    }

    @Test
    void eachCloudNeedsItsOwnSwitchAndTheProviderSwitch() {
        runner.withPropertyValues("dlap.discovery.coverage.enabled=true", "dlap.discovery.coverage.gcp.enabled=true",
                "dlap.discovery.coverage.gcp.projects=dlap-demo-data", "dlap.discovery.coverage.aws.enabled=true",
                "dlap.discovery.coverage.azure.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(GcpReadCoverageCollector.class);
            assertThat(ctx).as("cloud.provider.aws.enabled is off").doesNotHaveBean(AwsReadCoverageCollector.class);
            assertThat(ctx).as("cloud.provider.azure.enabled is off").doesNotHaveBean(AzureReadCoverageCollector.class);
        });

        runner.withPropertyValues("dlap.discovery.coverage.enabled=true", "dlap.discovery.coverage.gcp.enabled=true",
                "dlap.discovery.coverage.aws.enabled=true", "cloud.provider.aws.enabled=true",
                "dlap.discovery.coverage.azure.enabled=true", "cloud.provider.azure.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(GcpReadCoverageCollector.class);
            assertThat(ctx).hasSingleBean(AwsReadCoverageCollector.class);
            assertThat(ctx).hasSingleBean(AzureReadCoverageCollector.class);
            assertThat(ctx.getBean(ReadCoverageProperties.class).getLockAtMostFor().toMinutes()).isEqualTo(60);
        });
    }
}
