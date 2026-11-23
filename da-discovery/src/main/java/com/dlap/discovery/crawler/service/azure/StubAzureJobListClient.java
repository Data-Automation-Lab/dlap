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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default, offline {@link AzureJobListClient} used when no real SDK-backed
 * implementation is on the classpath ({@code @ConditionalOnMissingBean}). It
 * returns empty lists so the Azure processing-job crawler compiles, wires and
 * runs with zero cloud dependencies in dev/CI.
 *
 * <p><strong>Real SDK impl deferred:</strong> to enable live discovery, add a
 * {@code @Component} implementing {@link AzureJobListClient} backed by the Azure
 * Data Factory / Synapse SDKs (translating their responses into
 * {@link AzureJobListClient.AzureJob} DTOs). It automatically supersedes this stub.
 * The seam is identical to {@code da-mcp-pep}'s {@code PdpClient} / stub split.
 */
@Component
@ConditionalOnMissingBean(AzureJobListClient.class)
public class StubAzureJobListClient implements AzureJobListClient {

    private static final Logger log = LoggerFactory.getLogger(StubAzureJobListClient.class);

    public StubAzureJobListClient() {
        log.info("StubAzureJobListClient active (offline): Azure job discovery returns no jobs "
                + "until a real SDK-backed AzureJobListClient bean is provided.");
    }

    @Override
    public List<AzureJob> listDataFactoryPipelines(String subscriptionId) {
        log.debug("StubAzureJobListClient.listDataFactoryPipelines({}) -> [] (offline stub)", subscriptionId);
        return List.of();
    }

    @Override
    public List<AzureJob> listSynapseSparkJobs(String subscriptionId) {
        log.debug("StubAzureJobListClient.listSynapseSparkJobs({}) -> [] (offline stub)", subscriptionId);
        return List.of();
    }
}
