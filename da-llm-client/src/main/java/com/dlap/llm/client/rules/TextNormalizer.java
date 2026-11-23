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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns asset names, paths and metadata into a canonical token string so that
 * {@code Customer_PII-export.csv}, {@code customerPII} and {@code customer pii}
 * all present the same tokens to a keyword rule.
 *
 * <p>The transform is: split camel-case and acronym boundaries, lower-case with
 * {@link Locale#ROOT}, replace every run of non-alphanumeric characters with one
 * space, and pad the result with a leading and trailing space so a keyword can be
 * matched as {@code " keyword "}. It is pure and locale independent.
 */
public final class TextNormalizer {

    private static final Pattern LOWER_TO_UPPER = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    private static final Pattern ACRONYM_TO_WORD = Pattern.compile("(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private TextNormalizer() {
    }

    /**
     * Normalizes text for keyword matching.
     *
     * @param text raw text; {@code null} is treated as empty
     * @return the tokens separated by single spaces, with one leading and one
     *     trailing space (a single space for empty input)
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return " ";
        }
        String split = LOWER_TO_UPPER.matcher(text).replaceAll(" ");
        split = ACRONYM_TO_WORD.matcher(split).replaceAll(" ");
        String tokens = NON_ALPHANUMERIC.matcher(split.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        return tokens.isEmpty() ? " " : " " + tokens + " ";
    }
}
