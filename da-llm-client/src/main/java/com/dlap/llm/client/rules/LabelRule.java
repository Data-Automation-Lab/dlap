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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One deterministic labeling rule: a keyword list or a regular expression,
 * evaluated against selected {@link Field fields} of an asset description, that
 * proposes one label at a fixed confidence.
 *
 * <p>A rule is pure data. The same rule over the same text always yields the
 * same verdict, which is what lets the {@code RULES} provider stamp its
 * assignments {@code SMART_LABEL_RULE} (a deterministic match) rather than
 * {@code SMART_LABEL_ML} (a model inference).
 *
 * <p>Exactly one of {@link #keywords()} and {@link #pattern()} is non-null.
 * Keyword rules match whole tokens or whole token sequences after
 * {@linkplain TextNormalizer normalization}, with an optional plural
 * {@code s}. Regex rules run against the raw field text, so a pattern may rely
 * on punctuation such as path separators or a file extension.
 *
 * @param id stable rule identifier, unique inside a rule set (for example
 *     {@code pii.government-id}); reported on every suggestion the rule produces
 * @param description one-line human explanation of what the rule detects
 * @param label the label proposed when the rule matches
 * @param category the label category reported with the suggestion
 * @param confidence fixed confidence in {@code [0.0, 1.0]}
 * @param fields the asset fields the rule inspects; never empty
 * @param keywords normalized keyword list, or {@code null} for a regex rule
 * @param pattern compiled regular expression, or {@code null} for a keyword rule
 */
public record LabelRule(
        String id,
        String description,
        String label,
        String category,
        double confidence,
        Set<Field> fields,
        List<String> keywords,
        Pattern pattern) {

    /** The parts of an asset description a rule can inspect. */
    public enum Field {
        /** The asset name. */
        NAME,
        /** The asset path or qualified name. */
        PATH,
        /** Business metadata and any other descriptive attributes. */
        METADATA
    }

    /** Fields for rules that read naming only (name and path, not metadata). */
    public static final Set<Field> NAME_AND_PATH = Set.copyOf(EnumSet.of(Field.NAME, Field.PATH));

    /** Fields for rules that read the whole description. */
    public static final Set<Field> ANY_FIELD = Set.copyOf(EnumSet.allOf(Field.class));

    /** Fields for rules that read metadata only. */
    public static final Set<Field> METADATA_ONLY = Set.copyOf(EnumSet.of(Field.METADATA));

    /**
     * Validates and defensively copies the components.
     *
     * @throws IllegalArgumentException if an identifier, label or category is blank,
     *     the confidence is outside {@code [0, 1]}, no field is selected, or not
     *     exactly one of keywords and pattern is supplied
     */
    public LabelRule {
        requireText(id, "id");
        requireText(label, "label");
        requireText(category, "category");
        if (description == null) {
            description = "";
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Rule " + id + ": confidence must be within [0, 1]");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Rule " + id + ": at least one field is required");
        }
        fields = Set.copyOf(fields);
        if ((keywords == null) == (pattern == null)) {
            throw new IllegalArgumentException("Rule " + id + ": exactly one of keywords and pattern is required");
        }
        if (keywords != null) {
            keywords = List.copyOf(keywords);
            if (keywords.isEmpty()) {
                throw new IllegalArgumentException("Rule " + id + ": keywords must not be empty");
            }
        }
    }

    /**
     * Builds a keyword rule. Keywords are normalized here, so callers may write
     * them naturally ({@code "Social Security"}, {@code "e-mail"}).
     *
     * @param id unique rule identifier
     * @param label label proposed on a match
     * @param category label category reported with the suggestion
     * @param confidence fixed confidence in {@code [0, 1]}
     * @param fields asset fields to inspect
     * @param description one-line explanation of the rule
     * @param keywords one or more keywords or phrases
     * @return the rule
     */
    public static LabelRule keywords(String id, String label, String category, double confidence,
            Set<Field> fields, String description, String... keywords) {
        List<String> normalized = new ArrayList<>(keywords.length);
        for (String keyword : keywords) {
            String n = TextNormalizer.normalize(keyword).strip();
            if (n.isEmpty()) {
                throw new IllegalArgumentException("Rule " + id + ": blank keyword");
            }
            normalized.add(n);
        }
        return new LabelRule(id, description, label, category, confidence, fields, normalized, null);
    }

    /**
     * Builds a regular-expression rule. The expression is compiled once, here.
     *
     * @param id unique rule identifier
     * @param label label proposed on a match
     * @param category label category reported with the suggestion
     * @param confidence fixed confidence in {@code [0, 1]}
     * @param fields asset fields to inspect
     * @param description one-line explanation of the rule
     * @param regex the expression, searched (not fully matched) in the raw field text
     * @return the rule
     */
    public static LabelRule regex(String id, String label, String category, double confidence,
            Set<Field> fields, String description, String regex) {
        return new LabelRule(id, description, label, category, confidence, fields, null,
                Pattern.compile(regex));
    }

    /**
     * Tests this rule against one field of an asset description.
     *
     * @param normalizedText the field text after {@link TextNormalizer#normalize(String)}
     *     (used by keyword rules)
     * @param rawText the field text exactly as supplied (used by regex rules)
     * @return a short, content-free description of what matched, or {@code null}
     *     when the rule does not match. A keyword match returns the keyword (which
     *     comes from the rule, not from the asset); a regex match returns the
     *     literal {@code "pattern"}, so no asset content is ever echoed.
     */
    public String match(String normalizedText, String rawText) {
        if (keywords != null) {
            for (String keyword : keywords) {
                if (normalizedText.contains(" " + keyword + " ")
                        || normalizedText.contains(" " + keyword + "s ")) {
                    return "keyword '" + keyword + "'";
                }
            }
            return null;
        }
        return pattern.matcher(rawText).find() ? "pattern" : null;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LabelRule " + name + " must not be blank");
        }
    }
}
