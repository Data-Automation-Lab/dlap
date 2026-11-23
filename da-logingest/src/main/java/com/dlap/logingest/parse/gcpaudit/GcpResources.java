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

package com.dlap.logingest.parse.gcpaudit;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetKind;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.proto.log.v1.ResourceRelation;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the resources of GCP events from resource names, always through
 * {@code AssetIdNormalizer} (ADR-0047, plan rule R8).
 *
 * <p>Kinds are the cloud-native resource kinds ({@code storage.googleapis.com/Object},
 * {@code bigquery.googleapis.com/Table}, ...). A BigQuery dataset whose id starts with {@code _}
 * is an anonymous dataset that holds query results; {@link #anonymous(String)} tells the callers,
 * which never make such a table a data asset. A wildcard table ({@code events_*}) names several
 * tables, so its read is recorded on the dataset with the wildcard in the technical metadata.
 */
final class GcpResources {

    /** Kind of a Cloud Storage object. */
    static final String GCS_OBJECT = "storage.googleapis.com/Object";
    /** Kind of a Cloud Storage bucket. */
    static final String GCS_BUCKET = "storage.googleapis.com/Bucket";
    /** Kind of a BigQuery table or view. */
    static final String BQ_TABLE = "bigquery.googleapis.com/Table";
    /** Kind of a BigQuery dataset. */
    static final String BQ_DATASET = "bigquery.googleapis.com/Dataset";
    /** Kind of a BigQuery job. */
    static final String BQ_JOB = "bigquery.googleapis.com/Job";
    /** Technical-metadata key of a wildcard table read. */
    static final String WILDCARD_KEY = "bigquery.wildcard_table";

    private static final Pattern BUCKET = Pattern.compile("projects/[^/]+/buckets/([^/]+)(?:/objects/(.+))?", Pattern.DOTALL);
    private static final Pattern TABLE = Pattern.compile("projects/([^/]+)/datasets/([^/]+)/tables/([^/]+)");
    private static final Pattern DATASET = Pattern.compile("projects/([^/]+)/datasets/([^/]+)");
    private static final Pattern JOB = Pattern.compile("projects/([^/]+)/(?:locations/([^/]+)/)?jobs/([^/]+)");
    private static final Pattern PROJECT = Pattern.compile("projects/([^/]+)(?:/.*)?");

    private GcpResources() {
    }

    /**
     * A Cloud Storage bucket or object from its resource name
     * ({@code projects/_/buckets/B[/objects/O]}).
     *
     * @param resourceName the name
     * @param project      the project, from {@code resource.labels.project_id}
     * @param region       the location
     * @param hints        the binding's hints
     * @return the resource; its canonical URI is empty when the name does not normalize
     */
    static CanonicalEvent.Resource gcs(String resourceName, String project, String region, AssetIdHints hints) {
        Matcher m = BUCKET.matcher(resourceName);
        boolean object = m.matches() && m.group(2) != null;
        String name = m.matches() ? (object ? m.group(2) : m.group(1)) : resourceName;
        return CanonicalEvent.Resource.builder()
                .kind(object ? GCS_OBJECT : GCS_BUCKET)
                .id(resourceName)
                .name(name)
                .project(project)
                .region(region)
                .resourcePath(resourceName)
                .canonicalFrom(resourceName, hints.withKind(object ? AssetKind.GCS_OBJECT : AssetKind.GCS_BUCKET))
                .build();
    }

    /**
     * The bucket of an object resource name, as a related parent.
     *
     * @param resourceName an object's resource name
     * @param project      the project
     * @param hints        the binding's hints
     * @return the bucket, or empty for a bucket-level name
     */
    static Optional<CanonicalEvent.Resource> gcsParent(String resourceName, String project, AssetIdHints hints) {
        Matcher m = BUCKET.matcher(resourceName);
        if (!m.matches() || m.group(2) == null) {
            return Optional.empty();
        }
        String bucket = "projects/_/buckets/" + m.group(1);
        CanonicalEvent.Resource r = CanonicalEvent.Resource.builder()
                .kind(GCS_BUCKET).id(bucket).name(m.group(1)).project(project).resourcePath(bucket)
                .canonicalFrom(bucket, hints.withKind(AssetKind.GCS_BUCKET))
                .relation(ResourceRelation.RESOURCE_RELATION_PARENT)
                .build();
        return r.canonicalUri().isEmpty() ? Optional.empty() : Optional.of(r);
    }

    /**
     * A BigQuery table, dataset, job or other resource from a resource name, a
     * {@code projects/P/datasets/D/tables/T} path or a {@code {projectId, datasetId, tableId}}
     * object.
     *
     * @param resourceName the name
     * @param region       the location, or empty
     * @param hints        the binding's hints
     * @return the resource
     */
    static CanonicalEvent.Resource bigQuery(String resourceName, String region, AssetIdHints hints) {
        Matcher t = TABLE.matcher(resourceName);
        if (t.matches()) {
            String table = t.group(3);
            if (table.indexOf('*') >= 0) {
                String dataset = "projects/" + t.group(1) + "/datasets/" + t.group(2);
                return CanonicalEvent.Resource.builder()
                        .kind(BQ_TABLE).id(resourceName).name(table).project(t.group(1)).region(region)
                        .resourcePath(resourceName)
                        .canonicalFrom(dataset, hints.withKind(AssetKind.BIGQUERY_DATASET))
                        .technicalMetadata(Map.of(WILDCARD_KEY, table))
                        .build();
            }
            return CanonicalEvent.Resource.builder()
                    .kind(BQ_TABLE).id(resourceName).name(table).project(t.group(1)).region(region)
                    .resourcePath(resourceName)
                    .canonicalFrom(resourceName, hints.withKind(AssetKind.BIGQUERY_TABLE))
                    .build();
        }
        Matcher d = DATASET.matcher(resourceName);
        if (d.matches()) {
            return CanonicalEvent.Resource.builder()
                    .kind(BQ_DATASET).id(resourceName).name(d.group(2)).project(d.group(1)).region(region)
                    .resourcePath(resourceName)
                    .canonicalFrom(resourceName, hints.withKind(AssetKind.BIGQUERY_DATASET))
                    .build();
        }
        Matcher j = JOB.matcher(resourceName);
        if (j.matches()) {
            String jobRef = "projects/" + j.group(1) + "/jobs/" + j.group(3);
            return CanonicalEvent.Resource.builder()
                    .kind(BQ_JOB).id(resourceName).name(j.group(3)).project(j.group(1))
                    .region(j.group(2) != null ? j.group(2) : region)
                    .resourcePath(resourceName)
                    .canonicalFrom(jobRef, hints.withKind(AssetKind.BIGQUERY_JOB))
                    .build();
        }
        Matcher p = PROJECT.matcher(resourceName);
        return CanonicalEvent.Resource.builder()
                .kind(kindOf(resourceName)).id(resourceName).name(lastSegment(resourceName))
                .project(p.matches() ? p.group(1) : "").region(region).resourcePath(resourceName)
                .build();
    }

    /**
     * A BigQuery table from a v1 {@code TableName} object.
     *
     * @param tableName {@code {projectId, datasetId, tableId}}
     * @param hints     the binding's hints
     * @return the resource, or empty when a part is missing
     */
    static Optional<CanonicalEvent.Resource> bigQueryTable(JsonNode tableName, AssetIdHints hints) {
        Optional<String> p = GcpJson.text(tableName, "projectId");
        Optional<String> d = GcpJson.text(tableName, "datasetId");
        Optional<String> t = GcpJson.text(tableName, "tableId");
        if (p.isEmpty() || d.isEmpty() || t.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(bigQuery("projects/" + p.get() + "/datasets/" + d.get() + "/tables/" + t.get(), "", hints));
    }

    /**
     * A BigQuery job from a v1 {@code JobName} object.
     *
     * @param jobName {@code {projectId, jobId, location}}
     * @param hints   the binding's hints
     * @return the resource, or empty when a part is missing
     */
    static Optional<CanonicalEvent.Resource> bigQueryJob(JsonNode jobName, AssetIdHints hints) {
        Optional<String> p = GcpJson.text(jobName, "projectId");
        Optional<String> j = GcpJson.text(jobName, "jobId");
        if (p.isEmpty() || j.isEmpty()) {
            return Optional.empty();
        }
        String location = GcpJson.textOr(jobName, "location");
        return Optional.of(bigQuery("projects/" + p.get() + "/jobs/" + j.get(), location, hints));
    }

    /**
     * The same resource with a relation, for {@code related_resources}.
     *
     * @param r        the resource
     * @param relation the relation
     * @return the copy
     */
    static CanonicalEvent.Resource related(CanonicalEvent.Resource r, ResourceRelation relation) {
        return new CanonicalEvent.Resource(r.kind(), r.id(), r.name(), r.project(), r.region(), r.resourcePath(),
                r.labels(), r.canonicalUri(), r.createdTime(), r.sizeBytes(), r.technicalMetadata(), relation);
    }

    /**
     * Whether a resource name is a table of an anonymous (query result) dataset.
     *
     * @param resourceName the name
     * @return true for {@code projects/P/datasets/_.../tables/T}
     */
    static boolean anonymous(String resourceName) {
        Matcher t = TABLE.matcher(resourceName);
        return t.matches() && t.group(2).startsWith("_");
    }

    /**
     * Whether a resource name is a BigQuery table path.
     *
     * @param resourceName the name
     * @return true for {@code projects/P/datasets/D/tables/T}
     */
    static boolean isTable(String resourceName) {
        return TABLE.matcher(resourceName).matches();
    }

    /**
     * The {@code projects/P/jobs/J} form of a job name.
     *
     * @param jobName a job resource name, with or without a location
     * @return the location-free form, or the input when it is not a job name
     */
    static String jobKey(String jobName) {
        Matcher j = JOB.matcher(jobName);
        return j.matches() ? "projects/" + j.group(1) + "/jobs/" + j.group(3) : jobName;
    }

    private static String kindOf(String resourceName) {
        if (resourceName.contains("/models/")) {
            return "bigquery.googleapis.com/Model";
        }
        if (resourceName.contains("/routines/")) {
            return "bigquery.googleapis.com/Routine";
        }
        if (resourceName.contains("/rowAccessPolicies/")) {
            return "bigquery.googleapis.com/RowAccessPolicy";
        }
        return "";
    }

    private static String lastSegment(String s) {
        int slash = s.lastIndexOf('/');
        return slash < 0 ? s : s.substring(slash + 1);
    }
}
