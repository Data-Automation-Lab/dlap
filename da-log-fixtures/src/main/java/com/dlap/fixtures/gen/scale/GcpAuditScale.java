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

package com.dlap.fixtures.gen.scale;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Synthetic GCP Cloud Audit log entries for Cloud Storage objects (E2E-02): the {@code LogEntry} JSON
 * with an {@code AuditLog} payload, the shape the {@code gcpaudit} mapping reads, and its normalized
 * projection for the replay seam. Projects, buckets, callers and addresses are invented (the addresses
 * are the RFC 5737 documentation ranges).
 */
public final class GcpAuditScale {

    private static final int LANE_OP = 2;
    private static final int LANE_BUCKET = 3;
    private static final int LANE_KEY = 4;
    private static final int LANE_CALLER = 5;
    private static final int LANE_IP = 6;
    private static final int LANE_DENY = 7;
    private static final int LANE_INSERT = 8;
    private static final int BUCKETS = 24;

    private record Op(String method, String change, String kind, boolean dataRead) {
    }

    private static final List<Op> OPS = List.of(
            new Op("storage.objects.get", "ACCESS", "storage.googleapis.com/Object", true),
            new Op("storage.objects.create", "CREATE", "storage.googleapis.com/Object", false),
            new Op("storage.objects.update", "UPDATE", "storage.googleapis.com/Object", false),
            new Op("storage.objects.list", "ACCESS", "storage.googleapis.com/Bucket", true),
            new Op("storage.objects.delete", "DELETE", "storage.googleapis.com/Object", false));
    /** Per mille weights of {@link #OPS}. */
    private static final int[] WEIGHTS = {560, 230, 80, 110, 20};
    private static final List<String> CALLERS = List.of("etl", "analyst", "ml", "ingest", "report", "backup");

    private final long seed;
    private final ScaleWindow window;
    private final String projectId;

    /**
     * Creates a generator.
     *
     * @param window    the seed, count and event-time window
     * @param projectId the GCP project id, 6 to 30 lower case letters, digits and hyphens
     */
    public GcpAuditScale(ScaleWindow window, String projectId) {
        if (projectId == null || !projectId.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]")) {
            throw new IllegalArgumentException("projectId must be a GCP project id");
        }
        this.seed = window.seed();
        this.window = window;
        this.projectId = projectId;
    }

    /**
     * The window of this stream.
     *
     * @return the window
     */
    public ScaleWindow window() {
        return window;
    }

    /**
     * The {@code insertId} of a record.
     *
     * @param index the record index
     * @return 16 lower case hex digits
     */
    public String insertId(long index) {
        return ScaleRandom.hex(seed, index, LANE_INSERT, 16).toLowerCase(Locale.ROOT);
    }

    /**
     * The raw {@code LogEntry} of a record.
     *
     * @param index the record index
     * @return JSON text
     */
    public String entry(long index) {
        return normalized(index).rawJson();
    }

    /**
     * The normalized projection of a record, which carries the raw entry.
     *
     * @param index the record index, 0 to {@code count - 1}
     * @return the event
     */
    public NormalizedEvent normalized(long index) {
        Instant time = window.eventTime(index);
        Op op = OPS.get(ScaleRandom.weighted(seed, index, LANE_OP, WEIGHTS));
        String bucket = String.format("%s-b%02d", projectId, ScaleRandom.below(seed, index, LANE_BUCKET, BUCKETS));
        String object = "raw/dt=" + String.format("%02d", 1 + ScaleRandom.below(seed, index, LANE_KEY, 28))
                + "/part-" + ScaleRandom.hex(seed, index, 24, 6).toLowerCase(Locale.ROOT) + ".parquet";
        String caller = CALLERS.get(ScaleRandom.below(seed, index, LANE_CALLER, CALLERS.size()));
        String email = "dlap-scale-" + caller + "@" + projectId + ".iam.gserviceaccount.com";
        String ip = "198.51.100." + (1 + ScaleRandom.below(seed, index, LANE_IP, 250));
        boolean denied = ScaleRandom.below(seed, index, LANE_DENY, 200) == 0;
        String insert = insertId(index);
        String agent = "gcloud-golang/1.0 (scale " + caller + ")";
        boolean bucketLevel = "storage.googleapis.com/Bucket".equals(op.kind());
        String resourceName = "projects/_/buckets/" + bucket + (bucketLevel ? "" : "/objects/" + object);
        String logId = op.dataRead() ? "data_access" : "activity";

        StringBuilder raw = new StringBuilder(1000);
        raw.append("{\"logName\":\"projects/").append(projectId).append("/logs/cloudaudit.googleapis.com%2F")
                .append(logId).append("\",\"severity\":\"").append(denied ? "ERROR" : "INFO").append("\",");
        raw.append("\"timestamp\":\"").append(time).append("\",\"insertId\":\"").append(insert).append("\",");
        raw.append("\"resource\":{\"type\":\"gcs_bucket\",\"labels\":{\"project_id\":\"").append(projectId)
                .append("\",\"bucket_name\":\"").append(bucket).append("\",\"location\":\"us-central1\"}},");
        raw.append("\"protoPayload\":{\"@type\":\"type.googleapis.com/google.cloud.audit.AuditLog\",");
        raw.append("\"serviceName\":\"storage.googleapis.com\",\"methodName\":\"").append(op.method()).append("\",");
        raw.append("\"resourceName\":");
        JsonText.append(raw, resourceName);
        raw.append(",\"authenticationInfo\":{\"principalEmail\":\"").append(email).append("\"},");
        raw.append("\"authorizationInfo\":[{\"resource\":");
        JsonText.append(raw, resourceName);
        raw.append(",\"permission\":\"").append(op.method()).append("\",\"granted\":").append(!denied).append("}],");
        if (denied) {
            raw.append("\"status\":{\"code\":7,\"message\":\"PERMISSION_DENIED\"},");
        }
        raw.append("\"requestMetadata\":{\"callerIp\":\"").append(ip).append("\",\"callerSuppliedUserAgent\":");
        JsonText.append(raw, agent);
        raw.append("}}}");

        String targetId = bucketLevel ? "gs://" + bucket : "gs://" + bucket + "/" + object;
        return new NormalizedEvent("GCP", "storage.googleapis.com", op.method(), op.change(),
                denied ? "FAILURE" : "SUCCESS", time, "sa-" + caller + "-" + projectId, "SERVICE_ACCOUNT", email,
                projectId, op.kind(), targetId, bucketLevel ? bucket : object, projectId, "us-central1", ip, agent,
                "gcp-scale-" + insert, raw.toString());
    }
}
