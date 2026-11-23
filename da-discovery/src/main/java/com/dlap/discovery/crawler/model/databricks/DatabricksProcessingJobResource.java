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

package com.dlap.discovery.crawler.model.databricks;

import java.util.ArrayList;
import java.util.List;

import com.dlap.discovery.common.constants.ResourceTypeConstants;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.fasterxml.jackson.annotation.JsonTypeName;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A Databricks data-processing <em>job</em> modeled as a first-class processing-job
 * asset (V4-DBX). A concrete entity within the {@link CloudResource} hierarchy:
 * mirroring {@code UnityCatalogResource}, which extends {@link CloudResource}
 * directly (Databricks is treated as a standalone provider, {@link CloudProvider#DATABRICKS},
 * not an AWS/Azure sub-service) and the V4-AWS {@code AwsProcessingJobResource}
 * job-asset shape.
 *
 * <p>Why a job is an asset: log-derived lineage records read -&gt; write edges,
 * but the transform in the middle, the job, previously had nowhere to attach.
 * With a job asset, lineage becomes {@code source -> job -> destination}, so a
 * job node is a real, queryable endpoint carrying the engine, job type and the
 * input/output references it read and wrote.
 *
 * <p>One resource type covers two Databricks job engines via a {@link #source}
 * discriminator (cloud-neutral, mirroring the single {@code AwsProcessingJobResource}
 * shape rather than proliferating one entity per engine):
 * <ul>
 *   <li>Job run   - {@code typeId = }{@link ResourceTypeConstants#DATABRICKS_JOB}</li>
 *   <li>SQL query - {@code typeId = }{@link ResourceTypeConstants#DATABRICKS_SQL_QUERY}</li>
 * </ul>
 * da-catalog's serviceType derivation maps both kinds to
 * {@code DlapConstants.ServiceType.DATABRICKS_JOB}. (Databricks SQL is modeled as
 * {@code DATABRICKS_JOB} rather than {@code REDSHIFT_QUERY} so all Databricks
 * processing endpoints share one neutral job kind - documented in
 * {@code ResourceTypeConstants#DATABRICKS_SQL_QUERY}.)
 */
@Entity
@Table(name = "databricks_processing_job_resource")
@JsonTypeName("DATABRICKS")
@Getter
@Setter
@NoArgsConstructor
public class DatabricksProcessingJobResource extends CloudResource {

    /** Cloud-neutral processing engine that ran the job: {@code job_run}, {@code sql_query}. */
    @Column(name = "databricks_job_source")
    private String source;

    /**
     * Neutral job kind this maps to downstream: {@code DATABRICKS_JOB},
     * one of {@code DlapConstants.ServiceType.JOB_SERVICE_TYPES}.
     */
    @Column(name = "databricks_job_type")
    private String jobType;

    /** Terminal/current state: {@code SUCCESS}, {@code RUNNING}, {@code FAILED}, {@code FINISHED}, … */
    @Column(name = "databricks_job_state")
    private String state;

    /** The SQL/notebook text, when available. May be null. */
    @Column(name = "databricks_job_query", length = 8192)
    private String query;

    /**
     * Input references this job read (lineage sources). Transient: carried on the
     * discovery event for lineage attribution, not a persisted inventory column
     * (the catalog asset is the system of record).
     */
    @Transient
    private List<String> inputTables = new ArrayList<>();

    /** Output reference this job wrote (lineage target), if any. */
    @Column(name = "databricks_job_output")
    private String outputTable;

    public DatabricksProcessingJobResource(ResourceType resourceTypeRecord, String jobId, String name) {
        super(resourceTypeRecord, jobId, name);
        if (getCloudProviderEnum() != CloudProvider.DATABRICKS) {
            throw new IllegalArgumentException(
                    "ResourceType must be for the DATABRICKS provider. Found: " + getCloudProviderEnum());
        }
        String typeId = getTypeId();
        if (!ResourceTypeConstants.DATABRICKS_JOB.equals(typeId)
                && !ResourceTypeConstants.DATABRICKS_SQL_QUERY.equals(typeId)) {
            throw new IllegalArgumentException(
                    "DatabricksProcessingJobResource requires a Databricks job ResourceType ("
                            + ResourceTypeConstants.DATABRICKS_JOB + " / "
                            + ResourceTypeConstants.DATABRICKS_SQL_QUERY + ") but got: " + typeId);
        }
    }

    @Override
    @Transient
    public String getProviderSpecificType() {
        String typeId = getTypeId();
        if (ResourceTypeConstants.DATABRICKS_JOB.equals(typeId)) {
            return "DATABRICKS::job_run";
        }
        if (ResourceTypeConstants.DATABRICKS_SQL_QUERY.equals(typeId)) {
            return "DATABRICKS::sql_query";
        }
        return "DATABRICKS::processing_job";
    }
}
