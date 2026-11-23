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

package com.dlap.common.assetid;

import com.dlap.common.assetid.CanonicalAssetId.Attribute;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GCP rules. Canonical forms: {@code gs://{bucket}[/{object}]}, {@code bq://{project}/{dataset}[/{table}]},
 * {@code bqjob://{project}/{jobId}} and {@code //cloudsql.googleapis.com/projects/{project}/instances/{instance}}.
 *
 * <p>Bucket names and project IDs are lower-cased (GCP allows only lower case in both). Object
 * names and BigQuery dataset and table names keep their case.
 */
final class GcpAssetIdScheme implements AssetIdScheme {

    static final GcpAssetIdScheme INSTANCE = new GcpAssetIdScheme();

    /** A project ID, optionally domain-scoped ({@code example.com:my-project}). */
    private static final String PROJECT_RE = "(?:[a-z0-9][a-z0-9.-]*[a-z0-9]:)?[a-z][a-z0-9-]{4,28}[a-z0-9]";
    private static final Pattern PROJECT = Pattern.compile(PROJECT_RE);
    private static final Pattern GCS_BUCKET = Pattern.compile("[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]");
    private static final Pattern DATASET = Pattern.compile("[A-Za-z0-9_]{1,1024}");
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,1024}");
    private static final Pattern LOCATION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SQL_INSTANCE = Pattern.compile("[a-z][a-z0-9-]{0,97}");

    private static final Pattern COLON_TABLE = Pattern.compile(
            "(" + PROJECT_RE + "):([A-Za-z0-9_]+)\\.(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COLON_DATASET = Pattern.compile(
            "(" + PROJECT_RE + "):([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLON_JOB = Pattern.compile(
            "(" + PROJECT_RE + "):([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_CONNECTION = Pattern.compile(
            "(" + PROJECT_RE + "):([a-z0-9-]+):([a-z][a-z0-9-]*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern REL_BUCKET =
            Pattern.compile("projects/([^/]+)/buckets/([^/]+)(?:/objects/(.*))?", Pattern.DOTALL);
    private static final Pattern REL_DATASET =
            Pattern.compile("projects/([^/]+)/datasets/([^/]+)(?:/tables/([^/]+))?");
    private static final Pattern REL_JOB = Pattern.compile("projects/([^/]+)/jobs/([^/]+)");
    private static final Pattern REL_LOCATION_JOB = Pattern.compile("projects/([^/]+)/locations/([^/]+)/jobs/([^/]+)");
    private static final Pattern REL_INSTANCE = Pattern.compile("projects/([^/]+)/instances/([^/]+)");

    /** {@code toString()} of the google-api-client DatasetReference, TableReference or JobReference. */
    private static final Pattern GENERIC_DATA =
            Pattern.compile("GenericData\\{classInfo=\\[[^\\]]*\\], \\{(.*)\\}\\}", Pattern.DOTALL);

    private static final String CLOUD_SQL_PREFIX = "//cloudsql.googleapis.com/";

    private GcpAssetIdScheme() {
    }

    @Override
    public Optional<AssetIdParseResult> parseSelfDescribing(String v, AssetIdHints hints) {
        String rest = IdText.afterPrefix(v, "gs://");
        if (rest != null) {
            return gcsPath(rest, Map.of());
        }
        rest = IdText.afterPrefix(v, "bq://");
        if (rest != null) {
            return bqUri(rest);
        }
        rest = IdText.afterPrefix(v, "bqjob://");
        if (rest != null) {
            return bqJobUri(rest);
        }
        rest = IdText.afterPrefix(v, "bigquery://");
        if (rest != null) {
            return rest.startsWith("projects/") ? relative(rest, hints)
                    : IdText.malformed("bigquery:// takes a projects/... resource name");
        }
        if (v.startsWith("projects/")) {
            return relative(v, hints);
        }
        rest = IdText.afterPrefix(v, "//storage.googleapis.com/");
        if (rest != null) {
            return storageFullName(rest, hints);
        }
        rest = IdText.afterPrefix(v, "//bigquery.googleapis.com/");
        if (rest != null) {
            return rest.startsWith("projects/") ? relative(rest, hints)
                    : IdText.malformed("a BigQuery full resource name continues with projects/");
        }
        rest = IdText.afterPrefix(v, CLOUD_SQL_PREFIX);
        if (rest == null) {
            rest = IdText.afterPrefix(v, "//sqladmin.googleapis.com/");
        }
        if (rest != null) {
            Matcher m = REL_INSTANCE.matcher(rest);
            return m.matches() ? cloudSql(m.group(1), m.group(2))
                    : IdText.malformed("a Cloud SQL full resource name is projects/{p}/instances/{i}");
        }
        rest = IdText.afterPrefix(v, "https://");
        if (rest == null) {
            rest = IdText.afterPrefix(v, "http://");
        }
        if (rest != null) {
            return url(rest, hints);
        }
        if (v.startsWith("GenericData{")) {
            return genericData(v);
        }
        Matcher m = COLON_TABLE.matcher(v);
        if (m.matches()) {
            if (hints.kind() == AssetKind.BIGQUERY_JOB) {
                Matcher j = COLON_JOB.matcher(v);
                return j.matches() ? job(j.group(1), j.group(3), j.group(2))
                        : IdText.malformed("a BigQuery job reference is project:location.jobId");
            }
            return table(m.group(1), m.group(2), m.group(3));
        }
        return Optional.empty();
    }

    @Override
    public Optional<AssetIdParseResult> parseBare(String v, AssetIdHints hints) {
        AssetKind kind = hints.kind();
        String s = IdText.stripBackticks(v);
        if (kind == AssetKind.GCS_BUCKET) {
            return s.indexOf('/') >= 0 ? IdText.malformed("a bare GCS bucket name cannot contain '/'")
                    : gcsPath(s, Map.of());
        }
        if (kind == AssetKind.GCS_OBJECT) {
            return s.indexOf('/') <= 0 ? IdText.malformed("a bare GCS object must be bucket/object")
                    : gcsPath(s, Map.of());
        }
        if (kind == AssetKind.BIGQUERY_JOB) {
            Matcher j = COLON_JOB.matcher(s);
            if (j.matches()) {
                return job(j.group(1), j.group(3), j.group(2));
            }
            return withProject(hints, "a bare BigQuery job ID", p -> job(p, s, null));
        }
        if (kind == AssetKind.CLOUD_SQL_INSTANCE) {
            Matcher c = SQL_CONNECTION.matcher(s);
            if (c.matches()) {
                return cloudSql(c.group(1), c.group(3));
            }
            Matcher r = REL_INSTANCE.matcher(s);
            if (r.matches()) {
                return cloudSql(r.group(1), r.group(2));
            }
            return withProject(hints, "a bare Cloud SQL instance", p -> cloudSql(p, s));
        }
        Matcher cd = COLON_DATASET.matcher(s);
        if (cd.matches() && (kind == null || kind == AssetKind.BIGQUERY_DATASET)) {
            return dataset(cd.group(1), cd.group(2));
        }
        if (kind == AssetKind.BIGQUERY_DATASET) {
            if (s.indexOf('.') >= 0) {
                return dotted(s, hints, false);
            }
            return withProject(hints, "a bare BigQuery dataset", p -> dataset(p, s));
        }
        if (kind == null || kind == AssetKind.BIGQUERY_TABLE) {
            if (s.indexOf('.') >= 0) {
                return dotted(s, hints, true);
            }
            return kind == null ? Optional.empty()
                    : IdText.malformed("a bare BigQuery table is dataset.table or project.dataset.table");
        }
        return Optional.empty();
    }

    @Override
    public Optional<AssetIdParseResult> build(AssetKind kind, List<String> parts, AssetIdHints hints) {
        return switch (kind) {
            case GCS_BUCKET -> parts.size() != 1 ? IdText.malformed("GCS_BUCKET takes [bucket]")
                    : gcsBucket(parts.get(0), Map.of()).map(IdText::ok).orElseGet(() -> IdText.malformed("invalid GCS bucket name"));
            case GCS_OBJECT -> parts.size() != 2 || parts.get(1).isEmpty() ? IdText.malformed("GCS_OBJECT takes [bucket, object]")
                    : gcsObject(parts.get(0), parts.get(1), Map.of());
            case BIGQUERY_DATASET -> parts.size() != 2 ? IdText.malformed("BIGQUERY_DATASET takes [project, dataset]")
                    : dataset(parts.get(0), parts.get(1));
            case BIGQUERY_TABLE -> parts.size() != 3 ? IdText.malformed("BIGQUERY_TABLE takes [project, dataset, table]")
                    : table(parts.get(0), parts.get(1), parts.get(2));
            case BIGQUERY_JOB -> parts.size() != 2 ? IdText.malformed("BIGQUERY_JOB takes [project, jobId]")
                    : job(parts.get(0), parts.get(1), null);
            case CLOUD_SQL_INSTANCE -> parts.size() != 2 ? IdText.malformed("CLOUD_SQL_INSTANCE takes [project, instance]")
                    : cloudSql(parts.get(0), parts.get(1));
            default -> Optional.empty();
        };
    }

    // ---- Cloud Storage ---------------------------------------------------------------------

    private static Optional<AssetIdParseResult> gcsPath(String rest, Map<String, String> attrs) {
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String object = slash < 0 ? "" : rest.substring(slash + 1);
        if (object.isEmpty()) {
            return gcsBucket(bucket, attrs).map(IdText::ok).orElseGet(() -> IdText.malformed("invalid GCS bucket name"));
        }
        return gcsObject(bucket, object, attrs);
    }

    private static Optional<CanonicalAssetId> gcsBucket(String bucket, Map<String, String> attrs) {
        String b = IdText.lower(bucket);
        if (!GCS_BUCKET.matcher(b).matches()) {
            return Optional.empty();
        }
        return Optional.of(new CanonicalAssetId(AssetProvider.GCP, AssetKind.GCS_BUCKET, List.of(b), "gs://" + b, attrs, null));
    }

    private static Optional<AssetIdParseResult> gcsObject(String bucket, String object, Map<String, String> attrs) {
        Optional<CanonicalAssetId> parent = gcsBucket(bucket, attrs);
        if (parent.isEmpty()) {
            return IdText.malformed("invalid GCS bucket name");
        }
        String b = parent.get().parts().get(0);
        return IdText.ok(new CanonicalAssetId(AssetProvider.GCP, AssetKind.GCS_OBJECT, List.of(b, object),
                "gs://" + b + "/" + object, attrs, parent.get()));
    }

    private static Optional<AssetIdParseResult> storageFullName(String rest, AssetIdHints hints) {
        if (rest.startsWith("projects/")) {
            return relative(rest, hints);
        }
        if (rest.indexOf('/') >= 0) {
            return IdText.malformed("a Cloud Storage full resource name is //storage.googleapis.com/{bucket}");
        }
        return gcsPath(rest, Map.of());
    }

    private static Map<String, String> projectAttr(String projectSegment) {
        if (projectSegment == null || "_".equals(projectSegment)) {
            return Map.of();
        }
        Map<String, String> attrs = new HashMap<>();
        attrs.put(Attribute.PROJECT, IdText.lower(projectSegment));
        return attrs;
    }

    // ---- resource names --------------------------------------------------------------------

    private static Optional<AssetIdParseResult> relative(String v, AssetIdHints hints) {
        Matcher m = REL_BUCKET.matcher(v);
        if (m.matches()) {
            Map<String, String> attrs = projectAttr(m.group(1));
            if (m.group(3) == null) {
                return gcsBucket(m.group(2), attrs).map(IdText::ok).orElseGet(() -> IdText.malformed("invalid GCS bucket name"));
            }
            if (m.group(3).isEmpty()) {
                return IdText.malformed("empty object name in a GCS resource name");
            }
            return gcsObject(m.group(2), m.group(3), attrs);
        }
        m = REL_DATASET.matcher(v);
        if (m.matches()) {
            return m.group(3) == null ? dataset(m.group(1), m.group(2)) : table(m.group(1), m.group(2), m.group(3));
        }
        m = REL_JOB.matcher(v);
        if (m.matches()) {
            return job(m.group(1), m.group(2), null);
        }
        m = REL_LOCATION_JOB.matcher(v);
        if (m.matches()) {
            if (hints.kind() == AssetKind.BIGQUERY_JOB) {
                return job(m.group(1), m.group(3), m.group(2));
            }
            return IdText.ambiguous("projects/{p}/locations/{l}/jobs/{j} names a BigQuery or a Dataflow job; pass a kind hint");
        }
        m = REL_INSTANCE.matcher(v);
        if (m.matches()) {
            if (hints.kind() == AssetKind.CLOUD_SQL_INSTANCE) {
                return cloudSql(m.group(1), m.group(2));
            }
            return IdText.ambiguous("projects/{p}/instances/{i} names a Cloud SQL, Spanner or Bigtable instance; pass a kind hint");
        }
        return IdText.unsupported("the GCP resource name is not a DLAP asset kind");
    }

    // ---- URLs ------------------------------------------------------------------------------

    private static Optional<AssetIdParseResult> url(String afterScheme, AssetIdHints hints) {
        String[] hostAndPath = IdText.hostAndPath(afterScheme);
        String host = hostAndPath[0];
        String path = hostAndPath[1];
        boolean jsonHost = "storage.googleapis.com".equals(host) || "www.googleapis.com".equals(host);
        if (jsonHost && path.startsWith("/storage/v1/b/")) {
            return storageJsonApi(path.substring("/storage/v1/b/".length()));
        }
        boolean bqHost = "bigquery.googleapis.com".equals(host) || "www.googleapis.com".equals(host);
        if (bqHost && path.startsWith("/bigquery/v2/projects/")) {
            Optional<String> decoded = IdText.percentDecode(path.substring("/bigquery/v2/".length()));
            return decoded.isEmpty() ? IdText.malformed("invalid percent-encoding in a BigQuery URL")
                    : relative(decoded.get(), hints);
        }
        if ("storage.googleapis.com".equals(host) || "storage.cloud.google.com".equals(host)) {
            Optional<String> decoded = IdText.percentDecode(path);
            if (decoded.isEmpty()) {
                return IdText.malformed("invalid percent-encoding in a Cloud Storage URL");
            }
            String p = decoded.get();
            return p.length() <= 1 ? IdText.malformed("a Cloud Storage URL needs a bucket") : gcsPath(p.substring(1), Map.of());
        }
        if (host.endsWith(".storage.googleapis.com")) {
            String bucket = host.substring(0, host.length() - ".storage.googleapis.com".length());
            Optional<String> decoded = IdText.percentDecode(path);
            if (decoded.isEmpty()) {
                return IdText.malformed("invalid percent-encoding in a Cloud Storage URL");
            }
            String object = decoded.get().startsWith("/") ? decoded.get().substring(1) : decoded.get();
            return object.isEmpty() ? gcsPath(bucket, Map.of()) : gcsObject(bucket, object, Map.of());
        }
        if (host.endsWith(".googleapis.com")) {
            return IdText.unsupported("the Google API URL is not a DLAP asset");
        }
        return Optional.empty();
    }

    private static Optional<AssetIdParseResult> storageJsonApi(String rest) {
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash);
        if (tail.isEmpty() || "/".equals(tail)) {
            return gcsPath(bucket, Map.of());
        }
        if (!tail.startsWith("/o/") || tail.length() == 3) {
            return IdText.unsupported("the Cloud Storage JSON API path is not a bucket or object");
        }
        Optional<String> object = IdText.percentDecode(tail.substring(3));
        if (object.isEmpty()) {
            return IdText.malformed("invalid percent-encoding in a Cloud Storage JSON API URL");
        }
        return gcsObject(bucket, object.get(), Map.of());
    }

    // ---- BigQuery --------------------------------------------------------------------------

    private static Optional<AssetIdParseResult> bqUri(String rest) {
        if (rest.indexOf('/') >= 0) {
            List<String> seg = IdText.split(rest, '/');
            if (seg.size() == 2) {
                int dot = seg.get(1).indexOf('.');
                // bq://p/d.t: the mixed form the retire path accepted (GcpCloudDeletionService splits on [/.]).
                return dot < 0 ? dataset(seg.get(0), seg.get(1))
                        : table(seg.get(0), seg.get(1).substring(0, dot), seg.get(1).substring(dot + 1));
            }
            if (seg.size() == 3) {
                return table(seg.get(0), seg.get(1), seg.get(2));
            }
            return IdText.malformed("bq:// takes {project}/{dataset}[/{table}]");
        }
        return dotted(rest, AssetIdHints.none(), false);
    }

    private static Optional<AssetIdParseResult> bqJobUri(String rest) {
        List<String> seg = IdText.split(rest, '/');
        if (seg.size() == 2) {
            return job(seg.get(0), seg.get(1), null);
        }
        if (seg.size() == 3) {
            return job(seg.get(0), seg.get(2), seg.get(1));
        }
        return IdText.malformed("bqjob:// takes {project}/{jobId}");
    }

    /**
     * Dotted names read from the right, so a domain-scoped project ({@code example.com:p.d.t})
     * keeps its dots. Three parts are project.dataset.table. Two parts are dataset.table in the
     * hint project when {@code twoPartIsTable}, otherwise project.dataset.
     */
    private static Optional<AssetIdParseResult> dotted(String raw, AssetIdHints hints, boolean twoPartIsTable) {
        String s = IdText.stripBackticks(raw);
        int colon = s.lastIndexOf(':');
        String domain = colon >= 0 ? s.substring(0, colon + 1) : "";
        String tail = colon >= 0 ? s.substring(colon + 1) : s;
        List<String> seg = IdText.split(tail, '.');
        if (seg.size() == 3) {
            return table(domain + seg.get(0), seg.get(1), seg.get(2));
        }
        if (seg.size() == 2 && domain.isEmpty()) {
            if (twoPartIsTable) {
                return withProject(hints, "dataset.table", p -> table(p, seg.get(0), seg.get(1)));
            }
            return dataset(seg.get(0), seg.get(1));
        }
        if (seg.size() == 2) {
            return dataset(domain + seg.get(0), seg.get(1));
        }
        return IdText.malformed("a dotted BigQuery name has two or three parts");
    }

    private static Optional<AssetIdParseResult> genericData(String v) {
        Matcher m = GENERIC_DATA.matcher(v);
        if (!m.matches()) {
            return IdText.malformed("unrecognised GenericData form");
        }
        Map<String, String> fields = new HashMap<>();
        for (String pair : m.group(1).split(", ")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                fields.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        String project = fields.get("projectId");
        if (project == null) {
            return IdText.malformed("GenericData reference without projectId");
        }
        if (fields.containsKey("jobId")) {
            return job(project, fields.get("jobId"), fields.get("location"));
        }
        String dataset = fields.get("datasetId");
        if (dataset == null) {
            return IdText.malformed("GenericData reference without datasetId");
        }
        String table = fields.get("tableId");
        return table == null ? dataset(project, dataset) : table(project, dataset, table);
    }

    private interface ProjectFn {
        Optional<AssetIdParseResult> apply(String project);
    }

    private static Optional<AssetIdParseResult> withProject(AssetIdHints hints, String what, ProjectFn fn) {
        if (hints.project() == null) {
            return IdText.missingHint(what + " needs the project hint");
        }
        return fn.apply(hints.project());
    }

    private static Optional<String> project(String raw) {
        String p = IdText.lower(raw);
        return PROJECT.matcher(p).matches() ? Optional.of(p) : Optional.empty();
    }

    private static Optional<CanonicalAssetId> datasetId(String rawProject, String dataset) {
        Optional<String> p = project(rawProject);
        if (p.isEmpty() || !DATASET.matcher(dataset).matches()) {
            return Optional.empty();
        }
        return Optional.of(new CanonicalAssetId(AssetProvider.GCP, AssetKind.BIGQUERY_DATASET, List.of(p.get(), dataset),
                "bq://" + p.get() + "/" + dataset, Map.of(), null));
    }

    private static Optional<AssetIdParseResult> dataset(String rawProject, String dataset) {
        return datasetId(rawProject, dataset).map(IdText::ok)
                .orElseGet(() -> IdText.malformed("invalid BigQuery project or dataset"));
    }

    private static Optional<AssetIdParseResult> table(String rawProject, String dataset, String rawTable) {
        Optional<CanonicalAssetId> parent = datasetId(rawProject, dataset);
        if (parent.isEmpty()) {
            return IdText.malformed("invalid BigQuery project or dataset");
        }
        String table = rawTable;
        String decorator = null;
        int cut = firstIndexOf(rawTable, '$', '@');
        if (cut >= 0) {
            table = rawTable.substring(0, cut);
            decorator = rawTable.substring(cut);
        }
        if (table.indexOf('*') >= 0) {
            return IdText.unsupported("a wildcard table reference names several tables");
        }
        if (!validTable(table)) {
            return IdText.malformed("invalid BigQuery table name");
        }
        Map<String, String> attrs = new HashMap<>();
        if (decorator != null) {
            attrs.put(Attribute.DECORATOR, decorator);
        }
        CanonicalAssetId ds = parent.get();
        String p = ds.parts().get(0);
        return IdText.ok(new CanonicalAssetId(AssetProvider.GCP, AssetKind.BIGQUERY_TABLE, List.of(p, dataset, table),
                "bq://" + p + "/" + dataset + "/" + table, attrs, ds));
    }

    private static int firstIndexOf(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        return ib < 0 ? ia : Math.min(ia, ib);
    }

    private static boolean validTable(String t) {
        if (t.isEmpty() || t.length() > 1024 || IdText.hasControl(t)) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (".`/:$@*\"'".indexOf(t.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static Optional<AssetIdParseResult> job(String rawProject, String jobId, String location) {
        Optional<String> p = project(rawProject);
        if (p.isEmpty() || jobId == null || !JOB_ID.matcher(jobId).matches()) {
            return IdText.malformed("invalid BigQuery project or job ID");
        }
        Map<String, String> attrs = new HashMap<>();
        if (location != null && !"_".equals(location)) {
            if (!LOCATION.matcher(location).matches()) {
                return IdText.malformed("invalid BigQuery job location");
            }
            attrs.put(Attribute.LOCATION, location);
        }
        return IdText.ok(new CanonicalAssetId(AssetProvider.GCP, AssetKind.BIGQUERY_JOB, List.of(p.get(), jobId),
                "bqjob://" + p.get() + "/" + jobId, attrs, null));
    }

    private static Optional<AssetIdParseResult> cloudSql(String rawProject, String rawInstance) {
        Optional<String> p = project(rawProject);
        String instance = IdText.lower(rawInstance);
        if (p.isEmpty() || !SQL_INSTANCE.matcher(instance).matches()) {
            return IdText.malformed("invalid Cloud SQL project or instance");
        }
        return IdText.ok(new CanonicalAssetId(AssetProvider.GCP, AssetKind.CLOUD_SQL_INSTANCE, List.of(p.get(), instance),
                CLOUD_SQL_PREFIX + "projects/" + p.get() + "/instances/" + instance, Map.of(), null));
    }
}
