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
 * Databricks workspace objects. Canonical forms: {@code dbx://{workspaceId}/jobs/{jobId}},
 * {@code dbx://{workspaceId}/runs/{runId}} and {@code dbx://{workspaceId}/queries/{queryId}}.
 *
 * <p>A run is identified without its job because the Jobs API documents {@code run_id} as unique
 * across all runs of all jobs, and the crawler and audit forms often carry only the run ID. When the
 * job ID is known it is kept as the {@code jobId} attribute and becomes {@link CanonicalAssetId#parent()}.
 * The workspace is the numeric workspace ID; an Azure {@code adb-{id}.{n}.azuredatabricks.net} host
 * yields it, any other host needs the {@code workspaceId} hint.
 */
final class DatabricksAssetIdScheme implements AssetIdScheme {

    static final DatabricksAssetIdScheme INSTANCE = new DatabricksAssetIdScheme();

    private static final Pattern AZURE_HOST = Pattern.compile("adb-(\\d+)\\.\\d+\\.azuredatabricks\\.net");
    private static final Pattern QUERY_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");

    private DatabricksAssetIdScheme() {
    }

    @Override
    public Optional<AssetIdParseResult> parseSelfDescribing(String v, AssetIdHints hints) {
        String rest = IdText.afterPrefix(v, "dbx://");
        if (rest != null) {
            List<String> seg = IdText.split(rest, '/');
            if (IdText.anyEmpty(seg)) {
                return IdText.malformed("empty segment in a dbx:// URI");
            }
            if (seg.size() == 3 && "jobs".equals(seg.get(1))) {
                return object(seg.get(0), "jobs", seg.get(2), null, hints);
            }
            if (seg.size() == 3 && "runs".equals(seg.get(1))) {
                return object(seg.get(0), "runs", seg.get(2), null, hints);
            }
            if (seg.size() == 3 && "queries".equals(seg.get(1))) {
                return object(seg.get(0), "queries", seg.get(2), null, hints);
            }
            if (seg.size() == 5 && "jobs".equals(seg.get(1)) && "runs".equals(seg.get(3))) {
                return object(seg.get(0), "runs", seg.get(4), seg.get(2), hints);
            }
            return IdText.malformed("dbx:// takes {workspaceId}/jobs/{id}, /runs/{id} or /queries/{id}");
        }
        rest = IdText.afterPrefix(v, "databricks://");
        if (rest != null) {
            List<String> seg = IdText.split(rest, '/');
            if (IdText.anyEmpty(seg)) {
                return IdText.malformed("empty segment in a databricks:// URI");
            }
            if (seg.size() == 4 && "jobs".equals(seg.get(1)) && "runs".equals(seg.get(2))) {
                return object(seg.get(0), "runs", seg.get(3), null, hints);
            }
            if (seg.size() == 4 && "sql".equals(seg.get(1)) && "queries".equals(seg.get(2))) {
                return object(seg.get(0), "queries", seg.get(3), null, hints);
            }
            if (seg.size() == 3 && "jobs".equals(seg.get(1))) {
                return object(seg.get(0), "jobs", seg.get(2), null, hints);
            }
            return IdText.malformed("databricks:// takes {workspace}/jobs/runs/{id}, /sql/queries/{id} or /jobs/{id}");
        }
        return Optional.empty();
    }

    @Override
    public Optional<AssetIdParseResult> parseBare(String v, AssetIdHints hints) {
        return Optional.empty();
    }

    @Override
    public Optional<AssetIdParseResult> build(AssetKind kind, List<String> parts, AssetIdHints hints) {
        if (kind != AssetKind.DATABRICKS_JOB) {
            return Optional.empty();
        }
        if (parts.size() != 3 || !List.of("jobs", "runs", "queries").contains(parts.get(1))) {
            return IdText.malformed("DATABRICKS_JOB takes [workspaceId, jobs|runs|queries, id]");
        }
        return object(parts.get(0), parts.get(1), parts.get(2), null, AssetIdHints.none());
    }

    private static Optional<String> workspace(String raw, AssetIdHints hints) {
        String w = IdText.lower(raw);
        if (IdText.isAllDigits(w)) {
            return Optional.of(w);
        }
        Matcher m = AZURE_HOST.matcher(w);
        if (m.matches()) {
            return Optional.of(m.group(1));
        }
        if (IdText.isAllDigits(hints.workspaceId())) {
            return Optional.of(hints.workspaceId());
        }
        return Optional.empty();
    }

    private static Optional<AssetIdParseResult> object(String rawWorkspace, String collection, String id,
                                                       String jobId, AssetIdHints hints) {
        Optional<String> ws = workspace(rawWorkspace, hints);
        if (ws.isEmpty()) {
            return IdText.missingHint("the Databricks workspace is not a numeric workspace ID; pass the workspaceId hint");
        }
        boolean numeric = !"queries".equals(collection);
        if (numeric ? !IdText.isAllDigits(id) : !QUERY_ID.matcher(id).matches()) {
            return IdText.malformed("invalid Databricks " + collection + " ID");
        }
        if (jobId != null && !IdText.isAllDigits(jobId)) {
            return IdText.malformed("invalid Databricks job ID");
        }
        Map<String, String> attrs = new HashMap<>();
        CanonicalAssetId parent = null;
        if (jobId != null) {
            attrs.put(Attribute.JOB_ID, jobId);
            parent = id(ws.get(), "jobs", jobId, Map.of(), null);
        }
        return IdText.ok(id(ws.get(), collection, id, attrs, parent));
    }

    private static CanonicalAssetId id(String ws, String collection, String id, Map<String, String> attrs,
                                       CanonicalAssetId parent) {
        return new CanonicalAssetId(AssetProvider.DATABRICKS, AssetKind.DATABRICKS_JOB, List.of(ws, collection, id),
                "dbx://" + ws + "/" + collection + "/" + id, attrs, parent);
    }
}
