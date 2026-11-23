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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A stand-in for BigQuery: a column set and a list of finished jobs. {@link #rows} answers like the
 * transport's SQL would for the window (end time in {@code [from, to)}), and keeps every query it was
 * asked for.
 */
final class FakeJobsViewGateway implements JobsViewGateway {

    /** The documented columns plus a few the transport must never select. */
    static final List<String> FULL_COLUMNS = List.of("job_id", "project_id", "creation_time", "start_time", "end_time",
            "user_email", "principal_subject", "job_type", "statement_type", "state", "error_result", "cache_hit",
            "parent_job_id", "destination_table", "referenced_tables", "referenced_logical_views", "total_bytes_billed",
            "query", "job_stages", "timeline", "edition", "continuous", "brand_new_column");

    List<String> columns = new ArrayList<>(FULL_COLUMNS);
    final List<ObjectNode> jobs = new ArrayList<>();
    final List<Query> columnQueries = new ArrayList<>();
    final List<Query> rowQueries = new ArrayList<>();
    ViewFailure columnsFailure;
    ViewFailure rowsFailure;

    @Override
    public List<String> columns(Query query) {
        columnQueries.add(query);
        if (columnsFailure != null) {
            throw columnsFailure;
        }
        return List.copyOf(columns);
    }

    @Override
    public Iterator<ObjectNode> rows(Query query) {
        rowQueries.add(query);
        if (rowsFailure != null) {
            throw rowsFailure;
        }
        Instant from = query.timestamps().get("from");
        Instant to = query.timestamps().get("to");
        // Answer with the columns the SQL selects, as BigQuery would, not with every column the job has.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^SELECT (.*?)\nFROM ", java.util.regex.Pattern.DOTALL)
                .matcher(query.sql());
        if (!m.find()) {
            throw new IllegalArgumentException("not a rows query: " + query.sql());
        }
        Set<String> selected = new java.util.HashSet<>();
        for (String item : m.group(1).split(", ")) {
            selected.add(item.replace("j.", "").trim());
        }
        List<ObjectNode> out = new ArrayList<>();
        jobs.stream()
                .filter(j -> j.hasNonNull("end_time"))
                .filter(j -> {
                    Instant end = Instant.parse(j.get("end_time").asText());
                    return !end.isBefore(from) && end.isBefore(to);
                })
                .sorted((a, b) -> a.get("end_time").asText().compareTo(b.get("end_time").asText()))
                .forEach(j -> {
                    ObjectNode copy = j.deepCopy();
                    List<String> names = new ArrayList<>();
                    copy.fieldNames().forEachRemaining(names::add);
                    names.stream().filter(n -> !selected.contains(n)).forEach(copy::remove);
                    out.add(copy);
                });
        return out.iterator();
    }

    /**
     * A finished query job reading one table.
     *
     * @param jobId the job id
     * @param end   the end time
     * @return the row
     */
    static ObjectNode job(String jobId, String end) {
        ObjectNode r = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        r.put("project_id", "proj-x");
        r.put("job_id", jobId);
        r.put("creation_time", Instant.parse(end).minusSeconds(5).toString());
        r.put("end_time", end);
        r.put("user_email", "analyst@example.com");
        r.putNull("principal_subject");
        r.put("job_type", "QUERY");
        r.put("statement_type", "SELECT");
        r.put("state", "DONE");
        r.putNull("error_result");
        r.put("cache_hit", false);
        r.putNull("parent_job_id");
        r.putNull("destination_table");
        ObjectNode t = r.putArray("referenced_tables").addObject();
        t.put("project_id", "proj-x").put("dataset_id", "sales").put("table_id", "orders");
        r.putArray("referenced_logical_views");
        r.put("query", "SELECT secret_marker FROM sales.orders");
        return r;
    }

    static String text(JsonNode node, String field) {
        return node.path(field).asText();
    }
}
