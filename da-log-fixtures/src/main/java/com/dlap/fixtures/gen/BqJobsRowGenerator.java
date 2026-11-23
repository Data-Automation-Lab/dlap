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

package com.dlap.fixtures.gen;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic stream of BigQuery {@code INFORMATION_SCHEMA.JOBS_BY_PROJECT} rows for differential
 * tests (GCP-02). The same seed gives the same rows.
 *
 * <p>The stream is realistic about the things the read-history rules turn on: every statement type the
 * rules name, cache hits, failures, running jobs, background jobs, script parents with child jobs,
 * jobs that reference and write one table, logical views, hidden anonymous result tables, references
 * into INFORMATION_SCHEMA, and every kind of caller (people, service accounts, Google service agents,
 * workforce and workload identities, and nobody). Times are whole seconds, so a database that rounds
 * to seconds and a parser that keeps fractions agree.
 *
 * <p>This is generator output: cases built from it are not fixture cases, and they are not scored
 * (the coverage score counts hand-written cases only).
 */
public final class BqJobsRowGenerator {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String PROJECT = "proj-5e7a0c11";
    private static final String OTHER_PROJECT = "proj-b7f30a29";
    private static final String LOCATION = "US";

    private static final String[] TABLES = {
        "sales.orders", "sales.customers", "sales.order_items", "analytics.daily_orders", "analytics.order_summary",
        "staging.landing_orders", "staging.new_rows", "staging.tmp_load", "finance.ledger", "finance.invoices",
        "hr.people", "logs.events_2026"};
    private static final String[] VIEWS = {"analytics.v_orders_recent", "analytics.v_customer_360", "finance.v_open_invoices"};

    private static final String[] PEOPLE = {"ana@example.com", "bo@example.com", "chi@example.com", "dev@example.com",
        "eli@example.com", "fay@example.com"};
    private static final String[] SERVICE_ACCOUNTS = {"sa-4c1d9e02@proj-5e7a0c11.iam.gserviceaccount.com",
        "sa-81b2f7a0@proj-5e7a0c11.iam.gserviceaccount.com", "sa-0d9e5c33@proj-b7f30a29.iam.gserviceaccount.com"};
    private static final String[] AGENTS = {"service-100@gcp-sa-bigquerydatatransfer.iam.gserviceaccount.com",
        "100-compute@cloudservices.gserviceaccount.com"};
    private static final String WORKFORCE = "principal://iam.googleapis.com/locations/global/workforcePools/fx-pool/subject/jane@example.com";
    private static final String WORKLOAD = "principal://iam.googleapis.com/projects/100/locations/global/workloadIdentityPools/ci/subject/runner";

    private static final String[] DML = {"INSERT", "UPDATE", "DELETE", "MERGE"};
    private static final String[] DDL = {"CREATE_VIEW", "DROP_TABLE", "ALTER_TABLE", "TRUNCATE_TABLE", "CREATE_TABLE",
        "CREATE_SCHEMA", "CREATE_FUNCTION", "DROP_VIEW"};

    private final Prng random;
    private final List<ObjectNode> rows = new ArrayList<>();
    private int sequence;

    private BqJobsRowGenerator(long seed) {
        this.random = new Prng(seed);
    }

    /**
     * Generates rows.
     *
     * @param seed the seed
     * @param jobs how many top-level jobs to generate; script children and parents add rows
     * @return the rows, oldest end time first within each job group
     */
    public static List<ObjectNode> generate(long seed, int jobs) {
        BqJobsRowGenerator g = new BqJobsRowGenerator(seed);
        for (int i = 0; i < jobs; i++) {
            g.next();
        }
        return List.copyOf(g.rows);
    }

    private void next() {
        int pick = random.nextInt(100);
        Instant end = Instant.parse("2026-08-20T00:00:00Z").plusSeconds(sequence * 997L + random.nextInt(600));
        sequence++;
        if (pick < 38) {
            query(end, "SELECT", true);
        } else if (pick < 46) {
            query(end, DML[random.nextInt(DML.length)], false);
        } else if (pick < 52) {
            query(end, "CREATE_TABLE_AS_SELECT", false);
        } else if (pick < 58) {
            query(end, DDL[random.nextInt(DDL.length)], false);
        } else if (pick < 62) {
            query(end, "EXPORT_DATA", false);
        } else if (pick < 72) {
            cacheHit(end);
        } else if (pick < 78) {
            failed(end);
        } else if (pick < 82) {
            running(end);
        } else if (pick < 86) {
            otherJobType(end);
        } else {
            script(end);
        }
    }

    private ObjectNode base(String jobId, Instant end, String jobType, String statement) {
        ObjectNode r = JSON.objectNode();
        r.put("location", LOCATION);
        r.put("project_id", PROJECT);
        r.put("job_id", jobId);
        r.put("creation_time", end.minusSeconds(2 + random.nextInt(20)).toString());
        r.put("end_time", end.toString());
        principal(r);
        if (jobType == null) {
            r.putNull("job_type");
        } else {
            r.put("job_type", jobType);
        }
        if (statement == null) {
            r.putNull("statement_type");
        } else {
            r.put("statement_type", statement);
        }
        r.put("state", "DONE");
        r.putNull("error_result");
        r.put("cache_hit", false);
        r.putNull("parent_job_id");
        r.putNull("destination_table");
        r.putArray("referenced_tables");
        r.putArray("referenced_logical_views");
        return r;
    }

    private void principal(ObjectNode r) {
        int pick = random.nextInt(100);
        r.putNull("user_email");
        r.putNull("principal_subject");
        if (pick < 45) {
            r.put("user_email", PEOPLE[random.nextInt(PEOPLE.length)]);
        } else if (pick < 70) {
            r.put("user_email", SERVICE_ACCOUNTS[random.nextInt(SERVICE_ACCOUNTS.length)]);
        } else if (pick < 78) {
            r.put("user_email", AGENTS[random.nextInt(AGENTS.length)]);
        } else if (pick < 84) {
            r.put("principal_subject", WORKFORCE);
        } else if (pick < 88) {
            r.put("principal_subject", WORKLOAD);
        } else if (pick < 92) {
            r.put("principal_subject", "user:" + PEOPLE[random.nextInt(PEOPLE.length)]);
        } else if (pick < 95) {
            r.put("user_email", PEOPLE[random.nextInt(PEOPLE.length)]);
            r.put("principal_subject", "user:somebody-else@example.com");
        }
        // else: neither column is set, an unknown caller
    }

    private ObjectNode table(String qualified) {
        String[] parts = qualified.split("\\.");
        ObjectNode t = JSON.objectNode();
        t.put("project_id", random.nextInt(25) == 0 ? OTHER_PROJECT : PROJECT);
        t.put("dataset_id", parts[0]);
        t.put("table_id", parts[1]);
        return t;
    }

    private ObjectNode anonymous(String jobId) {
        ObjectNode t = JSON.objectNode();
        t.put("project_id", PROJECT);
        t.put("dataset_id", "_" + Integer.toHexString(random.nextInt(0xffffff)));
        t.put("table_id", "anon" + Integer.toHexString(jobId.hashCode()));
        return t;
    }

    private void references(ObjectNode r, int max) {
        ArrayNode refs = r.putArray("referenced_tables");
        int n = 1 + random.nextInt(max);
        for (int i = 0; i < n; i++) {
            refs.add(table(TABLES[random.nextInt(TABLES.length)]));
        }
        if (random.nextInt(4) == 0) {
            refs.add(refs.get(random.nextInt(refs.size())).deepCopy());
        }
        if (random.nextInt(5) == 0) {
            ((ArrayNode) r.get("referenced_logical_views")).add(table(VIEWS[random.nextInt(VIEWS.length)]));
        }
        if (random.nextInt(40) == 0) {
            ObjectNode info = refs.addObject();
            info.put("project_id", PROJECT).put("dataset_id", "INFORMATION_SCHEMA").put("table_id", "JOBS");
        }
    }

    private void query(Instant end, String statement, boolean select) {
        String jobId = "bquxjob_" + sequence + "_" + random.nextInt(1_000_000);
        ObjectNode r = base(jobId, end, "QUERY", statement);
        references(r, 4);
        ArrayNode refs = (ArrayNode) r.get("referenced_tables");
        if (select) {
            int d = random.nextInt(10);
            if (d < 7) {
                r.set("destination_table", anonymous(jobId));
            } else if (d == 7) {
                r.set("destination_table", table(TABLES[random.nextInt(TABLES.length)]));
            }
        } else if (!"EXPORT_DATA".equals(statement)) {
            ObjectNode destination = table(TABLES[random.nextInt(TABLES.length)]);
            r.set("destination_table", destination);
            if (random.nextBoolean()) {
                // DML and DDL name their own table among the references.
                refs.add(destination.deepCopy());
            }
        }
        rows.add(r);
    }

    private void cacheHit(Instant end) {
        String jobId = "bquxjob_" + sequence + "_" + random.nextInt(1_000_000);
        ObjectNode r = base(jobId, end, "QUERY", "SELECT");
        r.put("cache_hit", true);
        if (random.nextBoolean()) {
            references(r, 2);
        }
        r.set("destination_table", anonymous(jobId));
        rows.add(r);
    }

    private void failed(Instant end) {
        String jobId = "bquxjob_" + sequence + "_" + random.nextInt(1_000_000);
        ObjectNode r = base(jobId, end, "QUERY", random.nextBoolean() ? "SELECT" : "INSERT");
        references(r, 3);
        r.putObject("error_result").put("reason", "accessDenied");
        rows.add(r);
    }

    private void running(Instant end) {
        String jobId = "bquxjob_" + sequence + "_" + random.nextInt(1_000_000);
        ObjectNode r = base(jobId, end, "QUERY", "SELECT");
        r.put("state", random.nextBoolean() ? "RUNNING" : "PENDING");
        r.putNull("end_time");
        references(r, 3);
        rows.add(r);
    }

    private void otherJobType(Instant end) {
        String jobId = "job_" + sequence + "_" + random.nextInt(1_000_000);
        int pick = random.nextInt(4);
        String type = pick == 0 ? "LOAD" : pick == 1 ? "COPY" : pick == 2 ? "EXTRACT" : null;
        ObjectNode r = base(jobId, end, type, null);
        if ("LOAD".equals(type) || "COPY".equals(type)) {
            r.set("destination_table", table(TABLES[random.nextInt(TABLES.length)]));
        }
        rows.add(r);
    }

    private void script(Instant end) {
        String parentId = "script_job_" + sequence + "_" + random.nextInt(1_000_000);
        ObjectNode parent = base(parentId, end, "QUERY", "SCRIPT");
        parent.putNull("cache_hit");
        rows.add(parent);
        int children = 1 + random.nextInt(3);
        for (int i = 0; i < children; i++) {
            ObjectNode child = base(parentId + "_" + i, end.minusSeconds(children - i), "QUERY", i == 0 ? "SELECT" : "INSERT");
            child.put("parent_job_id", parentId);
            child.put("user_email", parent.get("user_email").isNull() ? null : parent.get("user_email").asText());
            if (parent.get("principal_subject").isNull()) {
                child.putNull("principal_subject");
            } else {
                child.put("principal_subject", parent.get("principal_subject").asText());
            }
            references(child, 3);
            if (i > 0) {
                child.set("destination_table", table(TABLES[random.nextInt(TABLES.length)]));
            }
            rows.add(child);
        }
    }

    /**
     * SplitMix64, a small seeded generator with a fixed algorithm, so the stream is the same on every JDK
     * and the generator names no JDK random class (the no-invented-data guard bans those in main code).
     */
    private static final class Prng {
        private long state;

        Prng(long seed) {
            this.state = seed;
        }

        private long next() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        int nextInt(int bound) {
            return (int) ((next() >>> 33) % bound);
        }

        boolean nextBoolean() {
            return (next() & 1L) == 1L;
        }
    }
}
