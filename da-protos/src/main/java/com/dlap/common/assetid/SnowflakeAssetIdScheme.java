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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Snowflake rules. Canonical form: {@code snowflake://{account}/{database}/{schema}/{table}}, where
 * the account is lower-cased and the three names are the stored (resolved) names.
 *
 * <p>A bare {@code db.schema.table} is read with Snowflake's identifier rules: an unquoted name is
 * stored and resolved in upper case, a double-quoted name keeps its exact case. A source that reports
 * stored names (ACCESS_HISTORY) must therefore quote any name that is not all upper case, or build
 * the ID from parts.
 */
final class SnowflakeAssetIdScheme implements AssetIdScheme {

    static final SnowflakeAssetIdScheme INSTANCE = new SnowflakeAssetIdScheme();

    private static final Pattern ACCOUNT = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,254}");

    private SnowflakeAssetIdScheme() {
    }

    @Override
    public Optional<AssetIdParseResult> parseSelfDescribing(String v, AssetIdHints hints) {
        String rest = IdText.afterPrefix(v, "snowflake://");
        if (rest == null) {
            return Optional.empty();
        }
        List<String> seg = IdText.split(rest, '/');
        if (seg.size() != 4 || IdText.anyEmpty(seg)) {
            return IdText.malformed("snowflake:// takes {account}/{database}/{schema}/{table}");
        }
        return table(seg.get(0), seg.get(1), seg.get(2), seg.get(3));
    }

    @Override
    public Optional<AssetIdParseResult> parseBare(String v, AssetIdHints hints) {
        if (hints.kind() != null && hints.kind() != AssetKind.SNOWFLAKE_TABLE) {
            return Optional.empty();
        }
        Optional<List<String>> names = resolveIdentifiers(v);
        if (names.isEmpty()) {
            return IdText.malformed("invalid Snowflake identifier chain");
        }
        if (names.get().size() != 3) {
            return IdText.malformed("a bare Snowflake table is database.schema.table");
        }
        if (hints.account() == null) {
            return IdText.missingHint("a Snowflake table name needs the account hint");
        }
        List<String> n = names.get();
        return table(hints.account(), n.get(0), n.get(1), n.get(2));
    }

    @Override
    public Optional<AssetIdParseResult> build(AssetKind kind, List<String> parts, AssetIdHints hints) {
        if (kind != AssetKind.SNOWFLAKE_TABLE) {
            return Optional.empty();
        }
        if (parts.size() != 4) {
            return IdText.malformed("SNOWFLAKE_TABLE takes [account, database, schema, table]");
        }
        return table(parts.get(0), parts.get(1), parts.get(2), parts.get(3));
    }

    static Optional<AssetIdParseResult> table(String rawAccount, String db, String schema, String table) {
        String account = IdText.lower(rawAccount);
        if (!ACCOUNT.matcher(account).matches()) {
            return IdText.malformed("invalid Snowflake account identifier");
        }
        for (String n : List.of(db, schema, table)) {
            if (n.isEmpty() || n.length() > 255 || IdText.hasControl(n)) {
                return IdText.malformed("invalid Snowflake object name");
            }
            if (n.indexOf('/') >= 0) {
                return IdText.unsupported("a Snowflake name containing '/' has no canonical form");
            }
        }
        return IdText.ok(new CanonicalAssetId(AssetProvider.SNOWFLAKE, AssetKind.SNOWFLAKE_TABLE,
                List.of(account, db, schema, table), "snowflake://" + account + "/" + db + "/" + schema + "/" + table,
                Map.of(), null));
    }

    /**
     * Splits a dotted identifier chain and resolves each name: unquoted names are upper-cased,
     * double-quoted names keep their case with {@code ""} unescaped.
     */
    static Optional<List<String>> resolveIdentifiers(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i <= n) {
            if (i == n) {
                return Optional.empty();
            }
            StringBuilder name = new StringBuilder();
            if (s.charAt(i) == '"') {
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '"') {
                        if (i + 1 < n && s.charAt(i + 1) == '"') {
                            name.append('"');
                            i += 2;
                            continue;
                        }
                        closed = true;
                        i++;
                        break;
                    }
                    name.append(c);
                    i++;
                }
                if (!closed || name.length() == 0) {
                    return Optional.empty();
                }
                out.add(name.toString());
            } else {
                while (i < n && s.charAt(i) != '.') {
                    char c = s.charAt(i);
                    if (c == '"' || Character.isWhitespace(c)) {
                        return Optional.empty();
                    }
                    name.append(c);
                    i++;
                }
                if (name.length() == 0) {
                    return Optional.empty();
                }
                out.add(IdText.upper(name.toString()));
            }
            if (i == n) {
                return Optional.of(out);
            }
            if (s.charAt(i) != '.') {
                return Optional.empty();
            }
            i++;
        }
        return Optional.empty();
    }
}
