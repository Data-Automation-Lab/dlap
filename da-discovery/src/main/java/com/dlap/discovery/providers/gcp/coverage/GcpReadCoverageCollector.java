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

package com.dlap.discovery.providers.gcp.coverage;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.dlap.discovery.coverage.CoverageVerdict;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.dlap.discovery.coverage.ReadCoverageCollector;
import com.dlap.discovery.coverage.ReadCoverageObservation;
import com.dlap.discovery.providers.gcp.coverage.GcpAuditConfigEvaluator.PolicyAt;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * GCP read coverage (COV-01). Per project: the IAM policies of the project and of every folder and
 * the organization above it ({@code auditConfigs}), then one observation for the project's BigQuery
 * datasets and tables (root {@code bq://<project>}) and one per Cloud Storage bucket (root
 * {@code gs://<bucket>}), channel {@code GCP_CLOUD_AUDIT}, scope the project id. The rules are in
 * {@link GcpAuditConfigEvaluator}.
 *
 * <p>When the buckets cannot be listed the project's buckets get no observation (there is nothing
 * to name them by) and the refusal is logged with the permission; their stored coverage stays as
 * it was, or unknown.
 *
 * <p>A failure of one bucket's policy read (not a refusal: a 404, a 429, a 5xx, a timeout) makes
 * only that bucket's coverage unknown, with the status as the reason, and the run goes on (Q-474).
 * A failure that stops the listing, the project or the hierarchy reads is not per bucket: the
 * exception ends the collector, nothing is published, and the catalog keeps what it had (fail
 * closed).
 */
public class GcpReadCoverageCollector implements ReadCoverageCollector {

    private static final Logger log = LoggerFactory.getLogger(GcpReadCoverageCollector.class);

    /** Collector id. */
    public static final String ID = "gcp-audit-config-coverage";
    /** Collector version. */
    public static final String VERSION = "1.0.0";
    /** Source kind that carries Cloud Audit Logs records (log-event.md section 5.2). */
    public static final String CHANNEL = "GCP_CLOUD_AUDIT";
    /** Longest folder chain followed above a project; Resource Manager allows 10 folder levels. */
    static final int MAX_DEPTH = 12;

    private final GcpCoverageApi api;
    private final List<String> projects;
    private final boolean bigqueryCrossProjectReadsVerified;

    /**
     * Creates the collector.
     *
     * @param api                               the GCP reads
     * @param projects                          the project ids to observe
     * @param bigqueryCrossProjectReadsVerified whether cross-project BigQuery read logging is confirmed
     */
    public GcpReadCoverageCollector(GcpCoverageApi api, List<String> projects, boolean bigqueryCrossProjectReadsVerified) {
        this.api = api;
        this.projects = List.copyOf(projects);
        this.bigqueryCrossProjectReadsVerified = bigqueryCrossProjectReadsVerified;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<ReadCoverageObservation> collect() {
        List<ReadCoverageObservation> out = new ArrayList<>();
        for (String project : projects) {
            out.addAll(collect(project));
        }
        return out;
    }

    private List<ReadCoverageObservation> collect(String project) {
        List<PolicyAt> levels = hierarchy(project);
        List<ReadCoverageObservation> out = new ArrayList<>();
        out.add(observation(project, "bq://" + project, List.of("BIGQUERY_DATASET", "BIGQUERY_TABLE"),
                GcpAuditConfigEvaluator.bigQuery(levels, bigqueryCrossProjectReadsVerified)));

        CoverageVerdict storage = GcpAuditConfigEvaluator.dataRead(GcpAuditConfigEvaluator.STORAGE, levels, false);
        List<JsonNode> buckets;
        try {
            buckets = api.listBuckets(project);
        } catch (MissingPermissionException e) {
            log.warn("GCP read coverage: {}; the project's buckets get no observation", e.reason());
            return out;
        }
        for (JsonNode bucket : buckets) {
            String name = bucket.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            JsonNode iam = null;
            String unreadable = null;
            boolean enforced = "enforced".equalsIgnoreCase(
                    bucket.path("iamConfiguration").path("publicAccessPrevention").asText(""));
            if (storage.known() && !enforced) {
                try {
                    iam = api.getBucketIamPolicy(name);
                } catch (MissingPermissionException e) {
                    unreadable = e.reason();
                } catch (CoverageTransportException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw e;
                    }
                    // One bucket's policy is unreadable (deleted mid-run, throttled, a server error):
                    // that bucket is unknown with the status as the reason, and the run goes on (Q-474).
                    log.warn("GCP read coverage: the IAM policy of gs://{} could not be read: {}", name,
                            e.getMessage());
                    unreadable = e.getMessage();
                }
            }
            out.add(observation(project, "gs://" + name, List.of("GCS_BUCKET", "GCS_OBJECT"),
                    GcpAuditConfigEvaluator.gcsBucketWithReason(storage, bucket, iam, unreadable)));
        }
        return out;
    }

    /** The project's policy, then each ancestor's; a refused read ends the chain with an unknown level. */
    List<PolicyAt> hierarchy(String project) {
        List<PolicyAt> levels = new ArrayList<>();
        levels.add(policy("projects/" + project));
        String parent;
        try {
            parent = api.getProject(project).path("parent").asText("");
        } catch (MissingPermissionException e) {
            levels.add(new PolicyAt("the ancestors of projects/" + project, null, e));
            return levels;
        }
        for (int depth = 0; depth < MAX_DEPTH && !parent.isBlank(); depth++) {
            levels.add(policy(parent));
            if (parent.startsWith("organizations/")) {
                return levels;
            }
            try {
                parent = api.getFolder(parent).path("parent").asText("");
            } catch (MissingPermissionException e) {
                levels.add(new PolicyAt("the ancestors of " + parent, null, e));
                return levels;
            }
        }
        if (!parent.isBlank()) {
            levels.add(new PolicyAt(parent, null, null));
        }
        return levels;
    }

    private PolicyAt policy(String resource) {
        try {
            return new PolicyAt(resource, api.getIamPolicy(resource), null);
        } catch (MissingPermissionException e) {
            return new PolicyAt(resource, null, e);
        }
    }

    private static ReadCoverageObservation observation(String project, String root, List<String> kinds,
                                                       CoverageVerdict verdict) {
        return new ReadCoverageObservation("GCP", project, root, kinds, CHANNEL, verdict, ID + "@" + VERSION);
    }
}
