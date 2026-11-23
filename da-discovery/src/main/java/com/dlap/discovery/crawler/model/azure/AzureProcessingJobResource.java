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

package com.dlap.discovery.crawler.model.azure;

import java.util.ArrayList;
import java.util.List;

import com.dlap.discovery.common.constants.ResourceTypeConstants;
import com.dlap.discovery.common.model.ResourceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An Azure data-processing <em>job</em> modeled as a first-class processing-job
 * asset (V4-AZURE). A JOINED-inheritance subtype of {@link AzureResource},
 * mirroring the V4-AWS reference {@code AwsProcessingJobResource extends AwsResource}
 * and the C7 {@code GcpBigQueryJobResource extends GcpResource}.
 *
 * <p>Why a job is an asset: log-derived lineage records read -&gt; write edges,
 * but the transform in the middle, the job, previously had nowhere to attach.
 * With a job asset, lineage becomes {@code source -> job -> destination}, so a
 * job node is a real, queryable endpoint carrying the engine, job type and the
 * input/output references it read and wrote.
 *
 * <p>One resource type covers two Azure job engines via a {@link #source}
 * discriminator (cloud-neutral, mirroring the single {@code AwsProcessingJobResource}
 * shape rather than proliferating one entity per engine):
 * <ul>
 *   <li>Data Factory pipeline  - {@code typeId = }{@link ResourceTypeConstants#AZURE_DATA_FACTORY_PIPELINE}</li>
 *   <li>Synapse Spark job      - {@code typeId = }{@link ResourceTypeConstants#AZURE_SYNAPSE_SPARK_JOB}</li>
 * </ul>
 * da-catalog's serviceType derivation maps these kinds to one of
 * {@code DlapConstants.ServiceType.JOB_SERVICE_TYPES}: Data Factory pipeline -&gt;
 * {@code DATAFLOW_JOB} (orchestrated data movement), Synapse Spark -&gt; {@code SPARK_JOB}.
 */
@Entity
@Table(name = "azure_processing_job_resource")
@Getter
@Setter
@NoArgsConstructor
public class AzureProcessingJobResource extends AzureResource {

    /** Cloud-neutral processing engine that ran the job: {@code data_factory}, {@code synapse_spark}. */
    @Column(name = "azure_job_source")
    private String source;

    /**
     * Neutral job kind this maps to downstream: {@code DATAFLOW_JOB}, {@code SPARK_JOB}
     * - one of {@code DlapConstants.ServiceType.JOB_SERVICE_TYPES}.
     */
    @Column(name = "azure_job_type")
    private String jobType;

    /** Terminal/current state: {@code Succeeded}, {@code InProgress}, {@code Failed}, … */
    @Column(name = "azure_job_state")
    private String state;

    /** The script/definition text, when available (Synapse notebook/script, pipeline definition). May be null. */
    @Column(name = "azure_job_query", length = 8192)
    private String query;

    /**
     * Input references this job read (lineage sources). Transient: carried on the
     * discovery event for lineage attribution, not a persisted inventory column
     * (the catalog asset is the system of record).
     */
    @Transient
    private List<String> inputTables = new ArrayList<>();

    /** Output reference this job wrote (lineage target), if any. */
    @Column(name = "azure_job_output")
    private String outputTable;

    public AzureProcessingJobResource(ResourceType resourceTypeRecord, String jobId, String name) {
        super(resourceTypeRecord, jobId, name);
        String typeId = getTypeId();
        if (!ResourceTypeConstants.AZURE_DATA_FACTORY_PIPELINE.equals(typeId)
                && !ResourceTypeConstants.AZURE_SYNAPSE_SPARK_JOB.equals(typeId)) {
            throw new IllegalArgumentException(
                    "AzureProcessingJobResource requires an Azure job ResourceType ("
                            + ResourceTypeConstants.AZURE_DATA_FACTORY_PIPELINE + " / "
                            + ResourceTypeConstants.AZURE_SYNAPSE_SPARK_JOB + ") but got: " + typeId);
        }
    }

    @Override
    @Transient
    public String getProviderSpecificType() {
        String typeId = getTypeId();
        if (ResourceTypeConstants.AZURE_DATA_FACTORY_PIPELINE.equals(typeId)) {
            return "AZURE::data_factory_pipeline";
        }
        if (ResourceTypeConstants.AZURE_SYNAPSE_SPARK_JOB.equals(typeId)) {
            return "AZURE::synapse_spark_job";
        }
        return "AZURE::processing_job";
    }
}
