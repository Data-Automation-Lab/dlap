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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Unity Catalog rules. Canonical forms: {@code uc://{metastore}/{catalog}[/{schema}[/{table}]]} and
 * {@code uc://{metastore}/{catalog}/{schema}/volumes/{volume}}.
 *
 * <p>A three-part name is unique only inside a metastore, so the metastore is part of identity and
 * a bare {@code catalog.schema.table} needs the metastore hint. Names are lower-cased: Unity Catalog
 * stores object names in lower case and forbids periods, spaces and forward slashes in them.
 */
final class UnityAssetIdScheme implements AssetIdScheme {

    static final UnityAssetIdScheme INSTANCE = new UnityAssetIdScheme();

    private static final Pattern METASTORE = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final String VOLUMES = "volumes";

    private UnityAssetIdScheme() {
    }

    @Override
    public Optional<AssetIdParseResult> parseSelfDescribing(String v, AssetIdHints hints) {
        String rest = IdText.afterPrefix(v, "uc://");
        if (rest != null) {
            List<String> seg = IdText.split(rest, '/');
            if (IdText.anyEmpty(seg)) {
                return IdText.malformed("empty segment in a uc:// URI");
            }
            String m = seg.get(0);
            return switch (seg.size()) {
                case 2 -> catalog(m, seg.get(1));
                case 3 -> schema(m, seg.get(1), seg.get(2));
                case 4 -> leaf(AssetKind.UNITY_TABLE, m, seg.get(1), seg.get(2), seg.get(3));
                case 5 -> VOLUMES.equals(IdText.lower(seg.get(3)))
                        ? leaf(AssetKind.UNITY_VOLUME, m, seg.get(1), seg.get(2), seg.get(4))
                        : IdText.malformed("a five-segment uc:// URI is {metastore}/{catalog}/{schema}/volumes/{volume}");
                default -> IdText.malformed("uc:// takes {metastore}/{catalog}[/{schema}[/{table}]]");
            };
        }
        rest = IdText.afterPrefix(v, "dbfs:/Volumes/");
        if (rest != null) {
            return volumePath(rest, hints);
        }
        return Optional.empty();
    }

    @Override
    public Optional<AssetIdParseResult> parseBare(String v, AssetIdHints hints) {
        String rest = IdText.afterPrefix(v, "/Volumes/");
        if (rest != null) {
            return volumePath(rest, hints);
        }
        AssetKind kind = hints.kind();
        if (kind != null && kind.defaultProvider().orElse(null) != AssetProvider.UNITY) {
            return Optional.empty();
        }
        List<String> seg = IdText.split(IdText.stripBackticks(v), '.');
        if (IdText.anyEmpty(seg) || seg.size() > 3) {
            return Optional.empty();
        }
        if (seg.size() == 1 && kind != AssetKind.UNITY_CATALOG && hints.provider() != AssetProvider.UNITY) {
            return Optional.empty();
        }
        String m = hints.metastoreId();
        if (m == null) {
            return IdText.missingHint("a Unity Catalog name needs the metastore hint; three-part names are unique only inside a metastore");
        }
        return switch (seg.size()) {
            case 1 -> catalog(m, seg.get(0));
            case 2 -> schema(m, seg.get(0), seg.get(1));
            default -> leaf(kind == AssetKind.UNITY_VOLUME ? AssetKind.UNITY_VOLUME : AssetKind.UNITY_TABLE,
                    m, seg.get(0), seg.get(1), seg.get(2));
        };
    }

    @Override
    public Optional<AssetIdParseResult> build(AssetKind kind, List<String> parts, AssetIdHints hints) {
        return switch (kind) {
            case UNITY_CATALOG -> parts.size() != 2 ? IdText.malformed("UNITY_CATALOG takes [metastore, catalog]")
                    : catalog(parts.get(0), parts.get(1));
            case UNITY_SCHEMA -> parts.size() != 3 ? IdText.malformed("UNITY_SCHEMA takes [metastore, catalog, schema]")
                    : schema(parts.get(0), parts.get(1), parts.get(2));
            case UNITY_TABLE, UNITY_VOLUME -> parts.size() != 4
                    ? IdText.malformed(kind + " takes [metastore, catalog, schema, name]")
                    : leaf(kind, parts.get(0), parts.get(1), parts.get(2), parts.get(3));
            default -> Optional.empty();
        };
    }

    private static Optional<AssetIdParseResult> volumePath(String rest, AssetIdHints hints) {
        List<String> seg = IdText.split(rest, '/');
        while (seg.size() > 3 && seg.get(seg.size() - 1).isEmpty()) {
            seg = seg.subList(0, seg.size() - 1);
        }
        if (seg.size() < 3 || IdText.anyEmpty(seg.subList(0, 3))) {
            return IdText.malformed("a volume path is /Volumes/{catalog}/{schema}/{volume}");
        }
        if (seg.size() > 3) {
            return IdText.unsupported("a file inside a Unity Catalog volume is not a DLAP asset; the volume is");
        }
        if (hints.metastoreId() == null) {
            return IdText.missingHint("a /Volumes path needs the metastore hint");
        }
        return leaf(AssetKind.UNITY_VOLUME, hints.metastoreId(), seg.get(0), seg.get(1), seg.get(2));
    }

    private static Optional<String> name(String raw) {
        String n = IdText.lower(raw);
        if (n.isEmpty() || n.length() > 255 || IdText.hasControl(n)) {
            return Optional.empty();
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '.' || c == '/' || c == '`' || Character.isWhitespace(c)) {
                return Optional.empty();
            }
        }
        return Optional.of(n);
    }

    private static Optional<CanonicalAssetId> catalogId(String rawMetastore, String rawCatalog) {
        String m = IdText.lower(rawMetastore);
        Optional<String> c = name(rawCatalog);
        if (!METASTORE.matcher(m).matches() || c.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CanonicalAssetId(AssetProvider.UNITY, AssetKind.UNITY_CATALOG, List.of(m, c.get()),
                "uc://" + m + "/" + c.get(), Map.of(), null));
    }

    private static Optional<CanonicalAssetId> schemaId(String m, String c, String s) {
        Optional<CanonicalAssetId> cat = catalogId(m, c);
        Optional<String> sn = name(s);
        if (cat.isEmpty() || sn.isEmpty()) {
            return Optional.empty();
        }
        List<String> p = cat.get().parts();
        return Optional.of(new CanonicalAssetId(AssetProvider.UNITY, AssetKind.UNITY_SCHEMA,
                List.of(p.get(0), p.get(1), sn.get()), cat.get().uri() + "/" + sn.get(), Map.of(), cat.get()));
    }

    private static Optional<AssetIdParseResult> catalog(String m, String c) {
        return catalogId(m, c).map(IdText::ok).orElseGet(() -> IdText.malformed("invalid Unity Catalog metastore or catalog name"));
    }

    private static Optional<AssetIdParseResult> schema(String m, String c, String s) {
        return schemaId(m, c, s).map(IdText::ok).orElseGet(() -> IdText.malformed("invalid Unity Catalog name"));
    }

    private static Optional<AssetIdParseResult> leaf(AssetKind kind, String m, String c, String s, String leaf) {
        Optional<CanonicalAssetId> sch = schemaId(m, c, s);
        Optional<String> ln = name(leaf);
        if (sch.isEmpty() || ln.isEmpty()) {
            return IdText.malformed("invalid Unity Catalog name");
        }
        List<String> p = sch.get().parts();
        String uri = sch.get().uri() + (kind == AssetKind.UNITY_VOLUME ? "/" + VOLUMES + "/" : "/") + ln.get();
        return IdText.ok(new CanonicalAssetId(AssetProvider.UNITY, kind, List.of(p.get(0), p.get(1), p.get(2), ln.get()),
                uri, Map.of(), sch.get()));
    }
}
