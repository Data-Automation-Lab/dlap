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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure rules. Blob Storage and ADLS Gen2 share one form, {@code az://{account}[/{container}[/{path}]]},
 * because the blob and dfs endpoints address the same data. Every other Azure kind uses the ARM
 * resource ID, lower-cased (Azure compares ARM IDs case-insensitively and exports them in mixed case).
 *
 * <p>Account and container names are lower-cased (Azure allows only lower case in both). Blob
 * paths keep their case. Query strings, including SAS tokens, are dropped before parsing.
 */
final class AzureAssetIdScheme implements AssetIdScheme {

    static final AzureAssetIdScheme INSTANCE = new AzureAssetIdScheme();

    private static final Pattern ACCOUNT = Pattern.compile("[a-z0-9]{3,24}");
    private static final Pattern CONTAINER =
            Pattern.compile("\\$root|\\$web|\\$logs|[a-z0-9](?:[a-z0-9]|-(?!-)){1,61}[a-z0-9]");
    private static final Pattern STORAGE_HOST = Pattern.compile("([a-z0-9]{3,24})\\.(?:blob|dfs)\\.core\\.[a-z0-9.-]+");
    private static final Pattern GUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern ARM = Pattern.compile("/subscriptions/([^/]+)/resourcegroups/([^/]+)/providers/(.+)");
    /**
     * One ARM name segment: 1 to 260 characters, no slash or control character, no leading or
     * trailing whitespace. Inner spaces are allowed: Data Factory pipeline names may contain them.
     */
    private static final Pattern ARM_NAME =
            Pattern.compile("[^\\s/\\p{Cntrl}](?:[^/\\p{Cntrl}]{0,258}[^\\s/\\p{Cntrl}])?");

    private AzureAssetIdScheme() {
    }

    @Override
    public Optional<AssetIdParseResult> parseSelfDescribing(String v, AssetIdHints hints) {
        String rest = IdText.afterPrefix(v, "az://");
        if (rest != null) {
            return blobPath(rest, hints, false);
        }
        rest = IdText.afterPrefix(v, "azure://");
        if (rest != null) {
            return blobPath(rest, hints, true);
        }
        for (String scheme : new String[] {"abfss://", "abfs://", "wasbs://", "wasb://"}) {
            rest = IdText.afterPrefix(v, scheme);
            if (rest != null) {
                return fileSystemUri(rest);
            }
        }
        rest = IdText.afterPrefix(v, "https://");
        if (rest == null) {
            rest = IdText.afterPrefix(v, "http://");
        }
        if (rest != null) {
            return blobUrl(rest);
        }
        if (IdText.startsWithIgnoreCase(v, "/subscriptions/")) {
            return arm(v);
        }
        rest = IdText.afterPrefix(v, "azuresql://");
        if (rest != null) {
            List<String> seg = IdText.split(rest, '/');
            if (seg.size() != 4 || IdText.anyEmpty(seg)) {
                return IdText.malformed("azuresql:// takes {subscription}/{resourceGroup}/{server}/{database}");
            }
            return arm("/subscriptions/" + seg.get(0) + "/resourcegroups/" + seg.get(1)
                    + "/providers/microsoft.sql/servers/" + seg.get(2) + "/databases/" + seg.get(3));
        }
        rest = IdText.afterPrefix(v, "azurecosmos://");
        if (rest != null) {
            return cosmosUri(rest, hints);
        }
        return Optional.empty();
    }

    @Override
    public Optional<AssetIdParseResult> parseBare(String v, AssetIdHints hints) {
        AssetKind kind = hints.kind();
        if (kind == AssetKind.BLOB_STORAGE) {
            return v.indexOf('/') >= 0 ? IdText.malformed("a bare storage account name cannot contain '/'")
                    : blobPath(v, hints, false);
        }
        if (kind == AssetKind.BLOB_CONTAINER && v.indexOf('/') < 0) {
            if (hints.account() == null) {
                return IdText.missingHint("a bare container name needs the storage account hint");
            }
            return blobPath(hints.account() + "/" + v, hints, false);
        }
        if (kind == null || kind == AssetKind.BLOB_CONTAINER || kind == AssetKind.BLOB_OBJECT) {
            // StorageBlobLogs ObjectKey: /account/container/path, with or without the leading slash.
            String key = v.startsWith("/") ? v.substring(1) : v;
            if (key.indexOf('/') <= 0) {
                return Optional.empty();
            }
            return blobPath(key, hints, false);
        }
        return Optional.empty();
    }

    @Override
    public Optional<AssetIdParseResult> build(AssetKind kind, List<String> parts, AssetIdHints hints) {
        switch (kind) {
            case BLOB_STORAGE, BLOB_CONTAINER, BLOB_OBJECT -> {
                int expected = kind == AssetKind.BLOB_STORAGE ? 1 : kind == AssetKind.BLOB_CONTAINER ? 2 : 3;
                if (parts.size() != expected || IdText.anyEmpty(parts)
                        || parts.subList(0, Math.min(2, parts.size())).stream().anyMatch(p -> p.indexOf('/') >= 0)) {
                    return IdText.malformed(kind + " takes " + (expected == 1 ? "[account]"
                            : expected == 2 ? "[account, container]" : "[account, container, path]"));
                }
                return blobPath(String.join("/", parts), hints, false);
            }
            case SQL_DATABASE -> {
                if (!armParts(parts, 4)) {
                    return IdText.malformed("SQL_DATABASE takes [subscription, resourceGroup, server, database]");
                }
                return arm(prefix(parts) + "microsoft.sql/servers/" + parts.get(2) + "/databases/" + parts.get(3));
            }
            case COSMOS_DB -> {
                if (!(armParts(parts, 3) || armParts(parts, 4) || armParts(parts, 5))) {
                    return IdText.malformed("COSMOS_DB takes [subscription, resourceGroup, account(, database(, container))]");
                }
                StringBuilder path = new StringBuilder(prefix(parts)).append("microsoft.documentdb/databaseaccounts/")
                        .append(parts.get(2));
                if (parts.size() > 3) {
                    path.append("/sqldatabases/").append(parts.get(3));
                }
                if (parts.size() > 4) {
                    path.append("/containers/").append(parts.get(4));
                }
                return arm(path.toString());
            }
            case DATAFLOW_JOB -> {
                if (!armParts(parts, 4)) {
                    return IdText.malformed("DATAFLOW_JOB takes [subscription, resourceGroup, factory, pipeline]");
                }
                return arm(prefix(parts) + "microsoft.datafactory/factories/" + parts.get(2) + "/pipelines/" + parts.get(3));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private static boolean armParts(List<String> parts, int size) {
        return parts.size() == size && !IdText.anyEmpty(parts) && parts.stream().noneMatch(p -> p.indexOf('/') >= 0);
    }

    private static String prefix(List<String> parts) {
        return "/subscriptions/" + parts.get(0) + "/resourcegroups/" + parts.get(1) + "/providers/";
    }

    // ---- Blob Storage and ADLS Gen2 ---------------------------------------------------------

    private static Optional<AssetIdParseResult> blobPath(String rest, AssetIdHints hints, boolean legacyAzureScheme) {
        int s1 = rest.indexOf('/');
        String account = IdText.lower(s1 < 0 ? rest : rest.substring(0, s1));
        if (legacyAzureScheme && hints.kind() != null && hints.kind().isJob()) {
            return IdText.unsupported("an azure://{service}/... job form from the stub crawler is not canonical; emit the ARM resource ID");
        }
        if (legacyAzureScheme && "synapse".equals(account) && !isBlobKind(hints.kind()) && isSynapseJobForm(rest)) {
            // azure://synapse/{subscription}/sparkjobs/{id} (AzureProcessingJobResourceCrawler) also parses as a blob
            // in an account named "synapse"; never guess, so only an explicit blob kind hint reads it as a blob.
            return IdText.unsupported("azure://synapse/{subscription}/sparkjobs/... is the stub crawler job form; emit the Spark job's ARM resource ID");
        }
        if (!ACCOUNT.matcher(account).matches()) {
            if (legacyAzureScheme && "data-factory".equals(account)) {
                return IdText.unsupported("azure://data-factory/... is the stub crawler job form; emit the pipeline's ARM resource ID");
            }
            return IdText.malformed("invalid Azure storage account name");
        }
        String after = s1 < 0 ? "" : rest.substring(s1 + 1);
        if (after.isEmpty()) {
            return IdText.ok(accountId(account, Map.of()));
        }
        int s2 = after.indexOf('/');
        String container = IdText.lower(s2 < 0 ? after : after.substring(0, s2));
        String path = s2 < 0 ? "" : after.substring(s2 + 1);
        return path.isEmpty() ? container(account, container, Map.of()) : blob(account, container, path);
    }

    private static boolean isBlobKind(AssetKind kind) {
        return kind == AssetKind.BLOB_STORAGE || kind == AssetKind.BLOB_CONTAINER || kind == AssetKind.BLOB_OBJECT;
    }

    /** True for {@code synapse/{subscription GUID}/sparkjobs/{id...}}. */
    private static boolean isSynapseJobForm(String rest) {
        List<String> seg = IdText.split(rest, '/');
        return seg.size() >= 4 && GUID.matcher(IdText.lower(seg.get(1))).matches()
                && "sparkjobs".equals(IdText.lower(seg.get(2)));
    }

    private static Optional<AssetIdParseResult> fileSystemUri(String rest) {
        int at = rest.indexOf('@');
        if (at <= 0) {
            return IdText.malformed("an abfss:// or wasbs:// URI is {container}@{account}.{blob|dfs}.core...");
        }
        String container = IdText.lower(rest.substring(0, at));
        String afterAt = rest.substring(at + 1);
        int slash = afterAt.indexOf('/');
        String host = IdText.lower(slash < 0 ? afterAt : afterAt.substring(0, slash));
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        String path = slash < 0 ? "" : afterAt.substring(slash + 1);
        Matcher m = STORAGE_HOST.matcher(host);
        if (!m.matches()) {
            return IdText.malformed("the host is not an Azure storage endpoint");
        }
        return path.isEmpty() ? container(m.group(1), container, Map.of()) : blob(m.group(1), container, path);
    }

    private static Optional<AssetIdParseResult> blobUrl(String afterScheme) {
        String[] hostAndPath = IdText.hostAndPath(afterScheme);
        Matcher m = STORAGE_HOST.matcher(hostAndPath[0]);
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<String> decoded = IdText.percentDecode(hostAndPath[1]);
        if (decoded.isEmpty()) {
            return IdText.malformed("invalid percent-encoding in an Azure storage URL");
        }
        String path = decoded.get().startsWith("/") ? decoded.get().substring(1) : decoded.get();
        return blobPath(path.isEmpty() ? m.group(1) : m.group(1) + "/" + path, AssetIdHints.none(), false);
    }

    private static CanonicalAssetId accountId(String account, Map<String, String> attrs) {
        return new CanonicalAssetId(AssetProvider.AZURE, AssetKind.BLOB_STORAGE, List.of(account), "az://" + account,
                attrs, null);
    }

    private static Optional<AssetIdParseResult> container(String account, String container, Map<String, String> attrs) {
        if (!CONTAINER.matcher(container).matches()) {
            return IdText.malformed("invalid Azure container name");
        }
        return IdText.ok(containerId(account, container, attrs));
    }

    private static CanonicalAssetId containerId(String account, String container, Map<String, String> attrs) {
        return new CanonicalAssetId(AssetProvider.AZURE, AssetKind.BLOB_CONTAINER, List.of(account, container),
                "az://" + account + "/" + container, attrs, accountId(account, attrs));
    }

    private static Optional<AssetIdParseResult> blob(String account, String container, String path) {
        if (!CONTAINER.matcher(container).matches()) {
            return IdText.malformed("invalid Azure container name");
        }
        CanonicalAssetId parent = containerId(account, container, Map.of());
        return IdText.ok(new CanonicalAssetId(AssetProvider.AZURE, AssetKind.BLOB_OBJECT, List.of(account, container, path),
                "az://" + account + "/" + container + "/" + path, Map.of(), parent));
    }

    // ---- ARM -------------------------------------------------------------------------------

    private static Optional<AssetIdParseResult> arm(String raw) {
        String s = IdText.lower(raw);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int first = s.indexOf("/providers/");
        if (first >= 0 && s.indexOf("/providers/", first + 1) >= 0) {
            return IdText.unsupported("an ARM extension resource (role assignment, lock, diagnostic setting) is not an asset; normalize its scope");
        }
        Matcher m = ARM.matcher(s);
        if (!m.matches()) {
            return IdText.unsupported("an ARM ID without a resource provider (subscription, resource group) is not a DLAP asset kind");
        }
        String sub = m.group(1);
        String rg = m.group(2);
        if (!GUID.matcher(sub).matches()) {
            return IdText.malformed("invalid Azure subscription ID");
        }
        List<String> seg = IdText.split(m.group(3), '/');
        if (IdText.anyEmpty(seg) || !ARM_NAME.matcher(rg).matches() || seg.stream().anyMatch(x -> !ARM_NAME.matcher(x).matches())) {
            return IdText.malformed("invalid segment in an ARM resource ID");
        }
        Map<String, String> attrs = new HashMap<>();
        attrs.put(Attribute.SUBSCRIPTION, sub);
        attrs.put(Attribute.RESOURCE_GROUP, rg);
        String ns = seg.get(0);
        int n = seg.size();
        if ("microsoft.storage".equals(ns) && n >= 3 && "storageaccounts".equals(seg.get(1))) {
            String account = seg.get(2);
            if (!ACCOUNT.matcher(account).matches()) {
                return IdText.malformed("invalid Azure storage account name");
            }
            if (n == 3 || (n == 5 && "blobservices".equals(seg.get(3)))) {
                return IdText.ok(accountId(account, attrs));
            }
            if (n == 7 && "blobservices".equals(seg.get(3)) && "containers".equals(seg.get(5))) {
                return container(account, seg.get(6), attrs);
            }
            return IdText.unsupported("the storage sub-resource is not a DLAP asset kind");
        }
        if ("microsoft.sql".equals(ns) && n == 5 && "servers".equals(seg.get(1)) && "databases".equals(seg.get(3))) {
            return IdText.ok(armId(AssetKind.SQL_DATABASE, sub, rg, s, List.of(seg.get(2), seg.get(4)), attrs));
        }
        if ("microsoft.documentdb".equals(ns) && "databaseaccounts".equals(seg.get(Math.min(1, n - 1)))
                && (n == 3 || (n == 5 && "sqldatabases".equals(seg.get(3)))
                    || (n == 7 && "sqldatabases".equals(seg.get(3)) && "containers".equals(seg.get(5))))) {
            List<String> names = new ArrayList<>();
            for (int i = 2; i < n; i += 2) {
                names.add(seg.get(i));
            }
            return IdText.ok(armId(AssetKind.COSMOS_DB, sub, rg, s, names, attrs));
        }
        if ("microsoft.datafactory".equals(ns) && n == 5 && "factories".equals(seg.get(1)) && "pipelines".equals(seg.get(3))) {
            return IdText.ok(armId(AssetKind.DATAFLOW_JOB, sub, rg, s, List.of(seg.get(2), seg.get(4)), attrs));
        }
        return IdText.unsupported("the ARM resource type is not a DLAP asset kind");
    }

    private static CanonicalAssetId armId(AssetKind kind, String sub, String rg, String uri, List<String> names,
                                          Map<String, String> attrs) {
        List<String> parts = new ArrayList<>();
        parts.add(sub);
        parts.add(rg);
        parts.addAll(names);
        return new CanonicalAssetId(AssetProvider.AZURE, kind, parts, uri, attrs, null);
    }

    private static Optional<AssetIdParseResult> cosmosUri(String rest, AssetIdHints hints) {
        List<String> seg = IdText.split(rest, '/');
        if (seg.size() != 3 || IdText.anyEmpty(seg)) {
            return IdText.malformed("azurecosmos:// takes {account}/{database}/{container}");
        }
        if (hints.subscription() == null || hints.resourceGroup() == null) {
            return IdText.missingHint("azurecosmos:// needs the subscription and resource group hints to form the ARM ID");
        }
        return arm("/subscriptions/" + hints.subscription() + "/resourcegroups/" + hints.resourceGroup()
                + "/providers/microsoft.documentdb/databaseaccounts/" + seg.get(0) + "/sqldatabases/" + seg.get(1)
                + "/containers/" + seg.get(2));
    }
}
