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

package com.dlap.policyengine.filter;

import java.util.Objects;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

/**
 * A user-supplied regular expression with a bounded cost (SEC-04, ADR-0057).
 *
 * <p>Two limits make a pattern safe to accept from any caller:
 * <ul>
 *   <li>the pattern is at most {@link #MAX_PATTERN_LENGTH} characters;</li>
 *   <li>it runs on RE2/J, which matches in time linear in the input, so no pattern can
 *       backtrack catastrophically (ReDoS). RE2 syntax has no backreferences and no
 *       lookaround; such patterns are rejected, never approximated.</li>
 * </ul>
 *
 * <p>Instances are immutable and thread-safe. Matching uses {@code find} semantics: the
 * pattern may match anywhere in the input unless it is anchored with {@code ^} and {@code $}.
 */
public final class BoundedRegex {

    /** The longest pattern accepted, in characters. */
    public static final int MAX_PATTERN_LENGTH = 256;

    private final String source;
    private final Pattern pattern;

    private BoundedRegex(String source, Pattern pattern) {
        this.source = source;
        this.pattern = pattern;
    }

    /**
     * Compiles a user-supplied pattern after checking its length and syntax.
     *
     * @param pattern the pattern text
     * @return the compiled, bounded pattern
     * @throws InvalidRegexException when the pattern is null, blank, longer than
     *     {@link #MAX_PATTERN_LENGTH} characters, or not valid RE2 syntax
     */
    public static BoundedRegex compile(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new InvalidRegexException("A regular expression must not be blank.");
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new InvalidRegexException("A regular expression may have at most "
                    + MAX_PATTERN_LENGTH + " characters; this one has " + pattern.length() + ".");
        }
        try {
            return new BoundedRegex(pattern, Pattern.compile(pattern));
        } catch (PatternSyntaxException e) {
            throw new InvalidRegexException("Invalid regular expression (RE2 syntax: no "
                    + "backreferences or lookaround): " + e.getDescription());
        }
    }

    /**
     * Checks a user-supplied pattern without keeping the compiled form.
     *
     * @param pattern the pattern text
     * @throws InvalidRegexException under the same conditions as {@link #compile(String)}
     */
    public static void validate(String pattern) {
        compile(pattern);
    }

    /**
     * Tests whether the pattern matches anywhere in the input.
     *
     * @param input the text to search; {@code null} never matches
     * @return {@code true} when the pattern is found in the input
     */
    public boolean find(CharSequence input) {
        return input != null && pattern.matcher(input).find();
    }

    /**
     * The pattern text as supplied.
     *
     * @return the source pattern
     */
    public String source() {
        return source;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BoundedRegex other && source.equals(other.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source);
    }

    @Override
    public String toString() {
        return "BoundedRegex[" + source + "]";
    }

    /**
     * A pattern that is too long or not valid RE2 syntax. It extends
     * {@link IllegalArgumentException}, so the REST layer answers HTTP 400.
     */
    public static final class InvalidRegexException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message what is wrong with the pattern
         */
        public InvalidRegexException(String message) {
            super(message);
        }
    }
}
