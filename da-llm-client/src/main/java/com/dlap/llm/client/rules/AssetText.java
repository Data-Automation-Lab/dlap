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

package com.dlap.llm.client.rules;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dlap.llm.client.rules.LabelRule.Field;

/**
 * An asset description split into the {@link Field fields} the rules inspect.
 *
 * <p>The auto-label pipeline hands a provider one text block of {@code Key: value}
 * lines, for example:
 *
 * <pre>
 * Asset name: customer_profiles
 * Cloud provider: GCP
 * Asset type: BIGQUERY_TABLE
 * Region: us-central1
 * Existing labels: ANALYTICS
 * Business metadata: {owner=data-team}
 * </pre>
 *
 * <p>Parsing rules:
 * <ul>
 *   <li>name keys ({@code Asset name}, {@code Name}) fill {@link Field#NAME};</li>
 *   <li>path keys ({@code Path}, {@code Asset path}, {@code Location}, {@code URI},
 *       {@code Qualified name}) fill {@link Field#PATH}; when none is present and
 *       the name contains a path separator or a dot, the name is also the path;</li>
 *   <li>{@code Cloud provider}, {@code Provider}, {@code Asset type}, {@code Type},
 *       {@code Region}, {@code Existing labels} and {@code Labels} are ignored. They
 *       are not content signals, and re-reading existing labels would make a rule
 *       match its own earlier output;</li>
 *   <li>{@code Business metadata} and {@code Metadata} start the metadata block: that
 *       line and every line after it fill {@link Field#METADATA}, whatever keys they
 *       contain. Any other unrecognized line also fills {@link Field#METADATA};</li>
 *   <li>text containing none of the keys above is treated as a bare asset name and
 *       fills every field, so {@code suggestLabels("gs://bucket/customer_pii.csv", cfg)}
 *       works.</li>
 * </ul>
 *
 * <p>Each field is truncated to {@value #MAX_FIELD_CHARS} characters to bound the
 * work a hostile or oversized metadata blob can cause.
 */
public final class AssetText {

    /** Upper bound on the characters kept per field. */
    public static final int MAX_FIELD_CHARS = 20_000;

    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([A-Za-z][A-Za-z _-]{0,40}?)\\s*:\\s*(.*)$");
    private static final Set<String> NAME_KEYS = Set.of("asset name", "name", "asset", "resource name");
    private static final Set<String> PATH_KEYS = Set.of(
            "path", "asset path", "location", "uri", "asset uri", "qualified name", "full name",
            "storage path", "resource");
    private static final Set<String> METADATA_KEYS = Set.of("business metadata", "metadata");
    private static final Set<String> IGNORED_KEYS = Set.of(
            "cloud provider", "provider", "asset type", "type", "region", "existing labels", "labels");

    private final Map<Field, String> raw;
    private final Map<Field, String> normalized;

    private AssetText(Map<Field, String> raw) {
        this.raw = raw;
        this.normalized = new EnumMap<>(Field.class);
        for (Field field : Field.values()) {
            normalized.put(field, TextNormalizer.normalize(raw.get(field)));
        }
    }

    /**
     * Parses an asset description.
     *
     * @param content the description; {@code null} is treated as empty
     * @return the parsed fields; never {@code null}
     */
    public static AssetText parse(String content) {
        String text = content == null ? "" : content;
        List<String> names = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> metadata = new ArrayList<>();
        boolean structured = false;
        boolean inMetadata = false;

        for (String line : text.split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            if (inMetadata) {
                metadata.add(line);
                continue;
            }
            Matcher m = KEY_VALUE.matcher(line);
            String key = m.matches() ? m.group(1).strip().toLowerCase(Locale.ROOT) : null;
            if (key != null && NAME_KEYS.contains(key)) {
                names.add(m.group(2));
                structured = true;
            } else if (key != null && PATH_KEYS.contains(key)) {
                paths.add(m.group(2));
                structured = true;
            } else if (key != null && IGNORED_KEYS.contains(key)) {
                structured = true;
            } else if (key != null && METADATA_KEYS.contains(key)) {
                metadata.add(m.group(2));
                structured = true;
                inMetadata = true;
            } else {
                metadata.add(line);
            }
        }

        Map<Field, String> fields = new EnumMap<>(Field.class);
        if (!structured) {
            String bare = truncate(text);
            fields.put(Field.NAME, bare);
            fields.put(Field.PATH, bare);
            fields.put(Field.METADATA, bare);
            return new AssetText(fields);
        }

        String name = String.join(" ", names);
        String path = String.join(" ", paths);
        if (path.isEmpty() && looksLikePath(name)) {
            path = name;
        }
        fields.put(Field.NAME, truncate(name));
        fields.put(Field.PATH, truncate(path));
        fields.put(Field.METADATA, truncate(String.join("\n", metadata)));
        return new AssetText(fields);
    }

    /**
     * Returns the raw text of one field.
     *
     * @param field the field
     * @return the text, possibly empty; never {@code null}
     */
    public String raw(Field field) {
        String value = raw.get(field);
        return value == null ? "" : value;
    }

    /**
     * Returns the {@linkplain TextNormalizer normalized} text of one field.
     *
     * @param field the field
     * @return the normalized text; never {@code null}
     */
    public String normalized(Field field) {
        return normalized.get(field);
    }

    private static boolean looksLikePath(String name) {
        return name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.indexOf('.') >= 0;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_FIELD_CHARS ? value : value.substring(0, MAX_FIELD_CHARS);
    }
}
