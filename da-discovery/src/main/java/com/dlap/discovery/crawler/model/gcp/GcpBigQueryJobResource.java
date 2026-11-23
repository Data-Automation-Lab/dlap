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

package com.dlap.discovery.crawler.model.gcp;

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
 * A Google BigQuery job (query / load / extract) modeled as a first-class
 * <em>processing-job</em> asset (C7). A JOINED-inheritance subtype of
 * {@link GcpResource}, mirroring {@code RdsResource extends AwsResource}.
 *
 * <p>Why a job is an asset: log-derived lineage records read -&gt; write edges,
 * but the transform in the middle, the job, previously had nowhere to attach.
 * With a job asset, lineage becomes {@code source -> job -> destination}, so a
 * job node is a real, queryable endpoint carrying the engine, job type and the
 * input/output table references it read and wrote.
 *
 * <p>The crawler sets {@code typeId = }{@link ResourceTypeConstants#GCP_BIGQUERY_JOB},
 * which da-catalog's serviceType derivation maps to {@code BIGQUERY_JOB} - one of
 * {@code DlapConstants.ServiceType.JOB_SERVICE_TYPES}.
 */
@Entity
@Table(name = "gcp_bigquery_job_resource")
@Getter
@Setter
@NoArgsConstructor
public class GcpBigQueryJobResource extends GcpResource {

    /** Processing engine that ran the job (always {@code bigquery} here). Cloud-neutral field. */
    @Column(name = "bq_job_engine")
    private String engine = "bigquery";

    /** Job type: {@code QUERY}, {@code LOAD}, {@code EXTRACT}, {@code COPY}. */
    @Column(name = "bq_job_type")
    private String jobType;

    /** Terminal state: {@code DONE}, {@code RUNNING}, {@code PENDING}. */
    @Column(name = "bq_job_state")
    private String state;

    /** The SQL statement, when this is a query job (may be null for load/extract). */
    @Column(name = "bq_job_query", length = 8192)
    private String query;

    /**
     * Fully-qualified input table URIs this job read (lineage sources).
     * Transient: carried on the discovery event for lineage attribution, not a
     * persisted inventory column (the catalog asset is the system of record).
     */
    @Transient
    private List<String> inputTables = new ArrayList<>();

    /** Fully-qualified output table URI this job wrote (lineage target), if any. */
    @Column(name = "bq_job_output")
    private String outputTable;

    public GcpBigQueryJobResource(ResourceType resourceTypeRecord, String jobId, String name) {
        super(resourceTypeRecord, jobId, name);
        String typeId = getTypeId();
        if (!ResourceTypeConstants.GCP_BIGQUERY_JOB.equals(typeId)) {
            throw new IllegalArgumentException(
                    "GcpBigQueryJobResource requires ResourceType " + ResourceTypeConstants.GCP_BIGQUERY_JOB
                            + " but got: " + typeId);
        }
    }

    @Override
    @Transient
    public String getProviderSpecificType() {
        return "GCP::bigquery_job";
    }
}
