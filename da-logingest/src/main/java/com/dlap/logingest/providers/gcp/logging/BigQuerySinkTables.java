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

package com.dlap.logingest.providers.gcp.logging;

import com.dlap.logingest.transport.StreamContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Sink table access through the BigQuery client: {@code tables.list} for the dataset, and a
 * parameterised standard SQL query per table and window whose result pages are converted with
 * {@link BqRowJson}. One client per credentials reference and project.
 */
final class BigQuerySinkTables implements BqSinkTransport.SinkTables {

    private final UnaryOperator<String> environment;
    private final Map<String, BigQuery> clients = new ConcurrentHashMap<>();

    /**
     * Creates the access.
     *
     * @param environment the environment lookup for credentials references
     */
    BigQuerySinkTables(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    @Override
    public List<String> list(StreamContext context, String project, String dataset) {
        List<String> out = new ArrayList<>();
        for (Table t : client(context, project).listTables(com.google.cloud.bigquery.DatasetId.of(project, dataset))
                .iterateAll()) {
            out.add(t.getTableId().getTable());
        }
        return out;
    }

    @Override
    public Iterator<BqSinkTransport.Page> query(StreamContext context, String project, String sql, Instant from,
                                                Instant to, int pageSize) {
        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql)
                .setUseLegacySql(false)
                .setMaxResults((long) pageSize)
                .addNamedParameter("from", QueryParameterValue.timestamp(ChronoUnit.MICROS.between(Instant.EPOCH, from)))
                .addNamedParameter("to", QueryParameterValue.timestamp(ChronoUnit.MICROS.between(Instant.EPOCH, to)))
                .build();
        TableResult first;
        try {
            first = client(context, project).query(config);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while querying a BigQuery sink table", e);
        } catch (BigQueryException e) {
            throw new IllegalStateException("BigQuery sink query failed: " + e.getMessage(), e);
        }
        return new Iterator<>() {
            private TableResult page = first;
            private int number;

            @Override
            public boolean hasNext() {
                return page != null;
            }

            @Override
            public BqSinkTransport.Page next() {
                if (page == null) {
                    throw new NoSuchElementException();
                }
                FieldList fields = page.getSchema().getFields();
                List<ObjectNode> rows = new ArrayList<>();
                for (FieldValueList row : page.getValues()) {
                    rows.add(BqRowJson.row(fields, row));
                }
                String token = "page-" + number++;
                page = page.hasNextPage() ? page.getNextPage() : null;
                return new BqSinkTransport.Page(rows, token);
            }
        };
    }

    private BigQuery client(StreamContext context, String project) {
        String ref = context.stream().credentialsRef();
        String key = (ref == null ? "" : ref) + "|" + project;
        return clients.computeIfAbsent(key, k -> BigQueryOptions.newBuilder()
                .setProjectId(project)
                .setCredentials(GcpCredentials.resolve(ref, environment, List.of(GcpCredentials.BIGQUERY)))
                .build()
                .getService());
    }
}
