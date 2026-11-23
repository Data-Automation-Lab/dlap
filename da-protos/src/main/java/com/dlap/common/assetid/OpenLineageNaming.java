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

import com.dlap.common.assetid.AssetIdParseResult.Reason;

import java.util.List;
import java.util.Optional;

/**
 * Maps canonical IDs to and from OpenLineage dataset names (namespace plus name), following the
 * OpenLineage naming conventions (https://openlineage.io/docs/spec/naming, page version 1.54.0,
 * read 2026-10-18).
 *
 * <table>
 *   <caption>Mapping</caption>
 *   <tr><th>Kind</th><th>Namespace</th><th>Name</th></tr>
 *   <tr><td>S3 bucket / object</td><td>{@code s3://{bucket}}</td><td>{@code /} / {@code {key}}</td></tr>
 *   <tr><td>GCS bucket / object</td><td>{@code gs://{bucket}}</td><td>{@code /} / {@code {object}}</td></tr>
 *   <tr><td>Blob container / path</td><td>{@code abfss://{container}@{account}.dfs.core.windows.net}</td><td>{@code /} / {@code {path}}</td></tr>
 *   <tr><td>BigQuery table</td><td>{@code bigquery}</td><td>{@code {project}.{dataset}.{table}}</td></tr>
 *   <tr><td>Glue table</td><td>{@code arn:{partition}:glue:{region}:{account}}</td><td>{@code table/{database}/{table}}</td></tr>
 *   <tr><td>Snowflake table</td><td>{@code snowflake://{account}}</td><td>{@code {database}.{schema}.{table}}</td></tr>
 *   <tr><td>Unity Catalog table</td><td>{@code unitycatalog://{workspace host}}</td><td>{@code {catalog}.{schema}.{table}}</td></tr>
 * </table>
 *
 * <p>The naming page does not define a bucket-level name; DLAP uses {@code /}. OpenLineage names
 * Unity Catalog tables by workspace host while DLAP names them by metastore, so mapping out needs
 * the host and mapping in needs the metastore hint. Kinds without an OpenLineage dataset form
 * (jobs, accounts, datasets, instances) map to empty.
 */
public final class OpenLineageNaming {

    /** The name DLAP uses for a bucket or container root. */
    public static final String ROOT_NAME = "/";

    private OpenLineageNaming() {
    }

    /**
     * The OpenLineage dataset for a canonical ID.
     *
     * @param id the canonical ID
     * @return the dataset, or empty when the kind has no OpenLineage dataset form
     */
    public static Optional<OpenLineageDataset> toDataset(CanonicalAssetId id) {
        return toDataset(id, null);
    }

    /**
     * The OpenLineage dataset for a canonical ID.
     *
     * @param id        the canonical ID
     * @param unityHost the Databricks workspace host, needed only for a Unity Catalog table
     * @return the dataset, or empty when the kind has no OpenLineage dataset form
     */
    public static Optional<OpenLineageDataset> toDataset(CanonicalAssetId id, String unityHost) {
        List<String> p = id.parts();
        return Optional.ofNullable(switch (id.kind()) {
            case S3_BUCKET -> new OpenLineageDataset("s3://" + p.get(0), ROOT_NAME);
            case S3_OBJECT -> new OpenLineageDataset("s3://" + p.get(0), p.get(1));
            case GCS_BUCKET -> new OpenLineageDataset("gs://" + p.get(0), ROOT_NAME);
            case GCS_OBJECT -> new OpenLineageDataset("gs://" + p.get(0), p.get(1));
            case BLOB_CONTAINER -> new OpenLineageDataset(abfss(p.get(0), p.get(1)), ROOT_NAME);
            case BLOB_OBJECT -> new OpenLineageDataset(abfss(p.get(0), p.get(1)), p.get(2));
            case BIGQUERY_TABLE -> new OpenLineageDataset("bigquery", p.get(0) + "." + p.get(1) + "." + p.get(2));
            case GLUE_TABLE -> new OpenLineageDataset("arn:" + p.get(0) + ":glue:" + p.get(2) + ":" + p.get(3), p.get(4));
            case SNOWFLAKE_TABLE -> new OpenLineageDataset("snowflake://" + p.get(0), p.get(1) + "." + p.get(2) + "." + p.get(3));
            case UNITY_TABLE -> unityHost == null || unityHost.isBlank() ? null
                    : new OpenLineageDataset("unitycatalog://" + IdText.lower(unityHost.strip()),
                            p.get(1) + "." + p.get(2) + "." + p.get(3));
            default -> null;
        });
    }

    /**
     * The canonical ID for an OpenLineage dataset.
     *
     * @param namespace the dataset namespace
     * @param name      the dataset name
     * @param hints     hints; a Unity Catalog dataset needs the metastore hint
     * @return the canonical ID or a rejection
     */
    public static AssetIdParseResult fromDataset(String namespace, String name, AssetIdHints hints) {
        AssetIdHints h = hints == null ? AssetIdHints.none() : hints;
        if (namespace == null || namespace.isBlank() || name == null) {
            return new AssetIdParseResult.Rejected(Reason.BLANK, "namespace and name are required", null);
        }
        String ns = namespace.strip();
        boolean root = name.isEmpty() || ROOT_NAME.equals(name);
        String rest = IdText.afterPrefix(ns, "s3://");
        if (rest != null) {
            return root ? AssetIdNormalizer.build(AssetKind.S3_BUCKET, List.of(rest))
                    : AssetIdNormalizer.build(AssetKind.S3_OBJECT, List.of(rest, name));
        }
        rest = IdText.afterPrefix(ns, "gs://");
        if (rest != null) {
            return root ? AssetIdNormalizer.build(AssetKind.GCS_BUCKET, List.of(rest))
                    : AssetIdNormalizer.build(AssetKind.GCS_OBJECT, List.of(rest, name));
        }
        if (IdText.startsWithIgnoreCase(ns, "abfss://") || IdText.startsWithIgnoreCase(ns, "wasbs://")) {
            Optional<CanonicalAssetId> container = AssetIdNormalizer.normalize(ns);
            if (container.isEmpty() || container.get().kind() != AssetKind.BLOB_CONTAINER) {
                return new AssetIdParseResult.Rejected(Reason.MALFORMED, "the namespace is not an Azure container", ns);
            }
            List<String> c = container.get().parts();
            if (root) {
                return new AssetIdParseResult.Normalized(container.get(), ns);
            }
            return AssetIdNormalizer.build(AssetKind.BLOB_OBJECT, List.of(c.get(0), c.get(1), name));
        }
        if ("bigquery".equalsIgnoreCase(ns)) {
            return AssetIdNormalizer.parse(name, AssetIdHints.of(AssetKind.BIGQUERY_TABLE));
        }
        if (IdText.startsWithIgnoreCase(ns, "arn:")) {
            AssetIdParseResult r = AssetIdNormalizer.parse(ns + ":" + name, AssetIdHints.none());
            if (r instanceof AssetIdParseResult.Normalized n && n.canonical().kind() != AssetKind.GLUE_TABLE) {
                return new AssetIdParseResult.Rejected(Reason.UNSUPPORTED_KIND, "only Glue table ARNs are OpenLineage datasets", ns);
            }
            return r;
        }
        rest = IdText.afterPrefix(ns, "snowflake://");
        if (rest != null) {
            List<String> seg = name.indexOf('"') >= 0
                    ? SnowflakeAssetIdScheme.resolveIdentifiers(name).orElse(List.of())
                    : IdText.split(name, '.');
            if (seg.size() != 3 || IdText.anyEmpty(seg)) {
                return new AssetIdParseResult.Rejected(Reason.MALFORMED, "a Snowflake dataset name is database.schema.table", name);
            }
            return AssetIdNormalizer.build(AssetKind.SNOWFLAKE_TABLE, List.of(rest, seg.get(0), seg.get(1), seg.get(2)));
        }
        if (IdText.startsWithIgnoreCase(ns, "unitycatalog://")) {
            if (h.metastoreId() == null) {
                return new AssetIdParseResult.Rejected(Reason.MISSING_HINT,
                        "an OpenLineage Unity Catalog dataset is named by host; pass the metastore hint", name);
            }
            return AssetIdNormalizer.parse(name, h.toBuilder().provider(AssetProvider.UNITY).kind(AssetKind.UNITY_TABLE).build());
        }
        return new AssetIdParseResult.Rejected(Reason.UNRECOGNIZED, "the OpenLineage namespace has no DLAP mapping", ns);
    }

    private static String abfss(String account, String container) {
        return "abfss://" + container + "@" + account + ".dfs.core.windows.net";
    }
}
