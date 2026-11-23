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

package com.dlap.discovery.crawler.model.aws;

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
 * An AWS data-processing <em>job</em> modeled as a first-class processing-job
 * asset (V4-AWS). A JOINED-inheritance subtype of {@link AwsResource}, mirroring
 * the GCP reference {@code GcpBigQueryJobResource extends GcpResource}.
 *
 * <p>Why a job is an asset: log-derived lineage records read -&gt; write edges,
 * but the transform in the middle, the job, previously had nowhere to attach.
 * With a job asset, lineage becomes {@code source -> job -> destination}, so a
 * job node is a real, queryable endpoint carrying the engine, job type and the
 * input/output references it read and wrote.
 *
 * <p>One resource type covers three AWS job engines via a {@link #source}
 * discriminator (cloud-neutral, mirroring the single {@code GcpBigQueryJobResource}
 * shape rather than proliferating one entity per engine):
 * <ul>
 *   <li>Glue job (Spark or Python-shell) - {@code typeId = }{@link ResourceTypeConstants#AWS_GLUE_JOB}</li>
 *   <li>Athena query                     - {@code typeId = }{@link ResourceTypeConstants#AWS_ATHENA_QUERY}</li>
 *   <li>EMR step                         - {@code typeId = }{@link ResourceTypeConstants#AWS_EMR_STEP}</li>
 * </ul>
 * da-catalog's serviceType derivation maps these kinds to one of
 * {@code DlapConstants.ServiceType.JOB_SERVICE_TYPES}: Glue/EMR -&gt; {@code SPARK_JOB}
 * (or {@code PYTHON_JOB} for a Python-shell Glue job), Athena -&gt; {@code REDSHIFT_QUERY}.
 */
@Entity
@Table(name = "aws_processing_job_resource")
@Getter
@Setter
@NoArgsConstructor
public class AwsProcessingJobResource extends AwsResource {

    /** Cloud-neutral processing engine that ran the job: {@code glue}, {@code athena}, {@code emr}. */
    @Column(name = "aws_job_source")
    private String source;

    /**
     * Neutral job kind this maps to downstream: {@code SPARK_JOB}, {@code PYTHON_JOB},
     * {@code REDSHIFT_QUERY} - one of {@code DlapConstants.ServiceType.JOB_SERVICE_TYPES}.
     */
    @Column(name = "aws_job_type")
    private String jobType;

    /** Terminal/current state: {@code SUCCEEDED}, {@code RUNNING}, {@code FAILED}, {@code READY}, … */
    @Column(name = "aws_job_state")
    private String state;

    /** The SQL/script text, when available (Athena query string, Glue script location). May be null. */
    @Column(name = "aws_job_query", length = 8192)
    private String query;

    /**
     * Input references this job read (lineage sources). Transient: carried on the
     * discovery event for lineage attribution, not a persisted inventory column
     * (the catalog asset is the system of record).
     */
    @Transient
    private List<String> inputTables = new ArrayList<>();

    /** Output reference this job wrote (lineage target), if any. */
    @Column(name = "aws_job_output")
    private String outputTable;

    public AwsProcessingJobResource(ResourceType resourceTypeRecord, String jobId, String name) {
        super(resourceTypeRecord, jobId, name);
        String typeId = getTypeId();
        if (!ResourceTypeConstants.AWS_GLUE_JOB.equals(typeId)
                && !ResourceTypeConstants.AWS_ATHENA_QUERY.equals(typeId)
                && !ResourceTypeConstants.AWS_EMR_STEP.equals(typeId)) {
            throw new IllegalArgumentException(
                    "AwsProcessingJobResource requires an AWS job ResourceType ("
                            + ResourceTypeConstants.AWS_GLUE_JOB + " / "
                            + ResourceTypeConstants.AWS_ATHENA_QUERY + " / "
                            + ResourceTypeConstants.AWS_EMR_STEP + ") but got: " + typeId);
        }
    }

    @Override
    @Transient
    public String getProviderSpecificType() {
        String typeId = getTypeId();
        if (ResourceTypeConstants.AWS_GLUE_JOB.equals(typeId)) {
            return "AWS::glue_job";
        }
        if (ResourceTypeConstants.AWS_ATHENA_QUERY.equals(typeId)) {
            return "AWS::athena_query";
        }
        if (ResourceTypeConstants.AWS_EMR_STEP.equals(typeId)) {
            return "AWS::emr_step";
        }
        return "AWS::processing_job";
    }
}
