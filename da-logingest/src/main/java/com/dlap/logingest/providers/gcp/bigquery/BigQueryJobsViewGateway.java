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

package com.dlap.logingest.providers.gcp.bigquery;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.TableResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The BigQuery client behind {@link JobsViewGateway}. Credentials come from Application Default
 * Credentials (workload identity, a service-account key file named by
 * {@code GOOGLE_APPLICATION_CREDENTIALS}, or the metadata server); nothing secret is configured here.
 * The client for a project is built on first use, so a service without BigQuery bindings never looks
 * for credentials.
 *
 * <p>The identity needs {@code bigquery.jobs.create} in the billing project and
 * {@code bigquery.jobs.listAll} on the project whose jobs are read (roles/bigquery.resourceViewer plus
 * roles/bigquery.user); the JOBS view lists every user's queries, so treat the role as sensitive.
 * Each poll is billed at least the 10 MB on-demand minimum.
 */
@Component
public class BigQueryJobsViewGateway implements JobsViewGateway {

    private static final Logger log = LoggerFactory.getLogger(BigQueryJobsViewGateway.class);

    private final Map<String, BigQuery> clients = new ConcurrentHashMap<>();
    private final Function<String, BigQuery> clientFactory;

    /** Creates the gateway with Application Default Credentials; clients are built on first use. */
    @Autowired
    public BigQueryJobsViewGateway() {
        this(project -> BigQueryOptions.newBuilder().setProjectId(project).build().getService());
    }

    /**
     * Creates the gateway with another way to build clients, for tests against an emulator.
     *
     * @param clientFactory builds the client for a billing project
     */
    public BigQueryJobsViewGateway(Function<String, BigQuery> clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public List<String> columns(Query query) {
        try {
            QueryJobConfiguration config = QueryJobConfiguration.newBuilder(query.sql())
                    .setUseLegacySql(false)
                    .setDryRun(true)
                    .build();
            Job job = client(query.billingProject()).create(JobInfo.newBuilder(config)
                    .setJobId(jobId(query))
                    .build());
            JobStatistics.QueryStatistics statistics = job.getStatistics();
            Schema schema = statistics == null ? null : statistics.getSchema();
            if (schema == null) {
                // BigQuery documents the schema of a successful dry run; a server that omits it (an
                // emulator, or a future change) gets the one-row-free query instead, which also yields it.
                log.debug("The dry run returned no schema; reading it from a LIMIT 0 query");
                schema = client(query.billingProject())
                        .query(QueryJobConfiguration.newBuilder(query.sql() + " LIMIT 0").setUseLegacySql(false).build(),
                                jobId(query))
                        .getSchema();
            }
            if (schema == null) {
                throw new ViewFailure(ViewFailure.Kind.OTHER, "BigQuery returned no schema for the JOBS view", null);
            }
            List<String> names = new ArrayList<>();
            schema.getFields().forEach(f -> names.add(f.getName()));
            return names;
        } catch (BigQueryException e) {
            throw classify(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ViewFailure(ViewFailure.Kind.OTHER, "interrupted while waiting for BigQuery", e);
        }
    }

    @Override
    public Iterator<ObjectNode> rows(Query query) {
        try {
            QueryJobConfiguration.Builder config = QueryJobConfiguration.newBuilder(query.sql()).setUseLegacySql(false);
            query.timestamps().forEach((name, instant) ->
                    config.addNamedParameter(name, QueryParameterValue.timestamp(ChronoUnit.MICROS.between(Instant.EPOCH, instant))));
            TableResult result = client(query.billingProject()).query(config.build(), jobId(query));
            FieldList fields = result.getSchema().getFields();
            Iterator<FieldValueList> it = result.iterateAll().iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    try {
                        return it.hasNext();
                    } catch (BigQueryException e) {
                        throw classify(e);
                    }
                }

                @Override
                public ObjectNode next() {
                    try {
                        return BigQueryRowJson.toJson(fields, it.next());
                    } catch (BigQueryException e) {
                        throw classify(e);
                    }
                }
            };
        } catch (BigQueryException e) {
            throw classify(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ViewFailure(ViewFailure.Kind.OTHER, "interrupted while waiting for BigQuery", e);
        }
    }

    /** A job id in the billing project and the query location, with a name of our own so it is always set. */
    private static JobId jobId(Query query) {
        return JobId.newBuilder().setProject(query.billingProject()).setLocation(query.location())
                .setJob("dlap-jobs-view-" + java.util.UUID.randomUUID()).build();
    }

    private BigQuery client(String billingProject) {
        return clients.computeIfAbsent(billingProject, p -> {
            log.info("Creating the BigQuery client for the JOBS view (billing project {})", p);
            return clientFactory.apply(p);
        });
    }

    /**
     * Sorts a BigQuery failure into the kinds the transport acts on. The message names the HTTP status
     * and BigQuery's reason code only: BigQuery messages can quote the SQL or a table name.
     *
     * @param e the SDK exception
     * @return the failure to throw
     */
    static ViewFailure classify(BigQueryException e) {
        String reason = e.getReason() == null ? "" : e.getReason();
        String text = (e.getMessage() == null ? "" : e.getMessage()).toLowerCase(Locale.ROOT);
        ViewFailure.Kind kind = ViewFailure.Kind.OTHER;
        if (e.getCode() == 403 || reason.equals("accessDenied") || reason.equals("forbidden")) {
            kind = ViewFailure.Kind.PERMISSION_DENIED;
        } else if (text.contains("location") && (text.contains("not found") || text.contains("does not match")
                || text.contains("same location") || text.contains("was not found in location"))) {
            kind = ViewFailure.Kind.LOCATION_MISMATCH;
        }
        return new ViewFailure(kind, "BigQuery refused the JOBS query: " + kind + " (HTTP " + e.getCode()
                + ", reason " + (reason.isEmpty() ? "none" : reason) + ")", e);
    }
}
