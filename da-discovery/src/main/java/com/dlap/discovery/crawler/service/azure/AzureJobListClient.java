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

/**
 * Thin job-list SPI for Azure data-processing engines. Mirrors the way
 * {@code da-mcp-pep} abstracts the PDP behind {@code PdpClient}: the crawler
 * depends on this interface, never on a cloud SDK.
 *
 * <p><strong>Why an SPI rather than the Azure SDK directly:</strong> the Azure
 * Data Factory / Synapse SDKs are not present in the offline build cache and
 * cannot be resolved reliably. This interface returns plain {@link AzureJob}
 * DTOs (no Azure SDK types leak past da-discovery), so the crawler compiles and
 * unit-tests with zero cloud dependencies. A real SDK-backed implementation is
 * a deferred drop-in: provide a {@code @Component} implementing this interface
 * and it wins over {@link StubAzureJobListClient} (which is
 * {@code @ConditionalOnMissingBean}).
 */
public interface AzureJobListClient {

    /**
     * List Data Factory pipelines for the given subscription (each modeled as a
     * {@code DATAFLOW_JOB} - orchestrated data movement).
     *
     * @param subscriptionId Azure subscription ID (may be null; the impl may default it)
     * @return plain job DTOs; never null
     */
    List<AzureJob> listDataFactoryPipelines(String subscriptionId);

    /**
     * List Synapse Spark jobs for the given subscription (each modeled as a
     * {@code SPARK_JOB}).
     *
     * @param subscriptionId Azure subscription ID (may be null; the impl may default it)
     * @return plain job DTOs; never null
     */
    List<AzureJob> listSynapseSparkJobs(String subscriptionId);

    /**
     * Cloud-neutral plain job descriptor returned by the SPI. Carries only Java
     * types (no Azure SDK classes), so it is safe to depend on from the crawler
     * and to mock in tests.
     *
     * @param id     stable job/pipeline identifier (null-id jobs are skipped by the crawler)
     * @param name   human-readable name (may equal id)
     * @param state  provider state string (e.g. Succeeded / InProgress / Failed); may be null
     * @param query  script/definition text if available; may be null
     * @param inputs input references read (lineage sources); may be null/empty
     * @param output output reference written (lineage target); may be null
     * @param region Azure region; may be null
     */
    record AzureJob(
            String id,
            String name,
            String state,
            String query,
            List<String> inputs,
            String output,
            String region) {
    }
}
