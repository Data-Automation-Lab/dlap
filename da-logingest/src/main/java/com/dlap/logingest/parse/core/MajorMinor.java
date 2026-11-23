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

package com.dlap.logingest.parse.core;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code major.minor} schema version such as CloudTrail {@code eventVersion} {@code 1.08} or
 * Databricks {@code version} {@code 2.0}. A bare major ({@code 1}) reads as minor 0. Leading zeros
 * are insignificant ({@code 1.08} equals {@code 1.8}), so a pin and a detected value compare by
 * number, never by spelling.
 *
 * @param major the major version, at least 0
 * @param minor the minor version, at least 0
 */
public record MajorMinor(int major, int minor) implements Comparable<MajorMinor> {

    private static final Pattern SHAPE = Pattern.compile("(\\d{1,6})(?:\\.(\\d{1,6}))?");

    /**
     * Validates both numbers.
     *
     * @param major see the record description
     * @param minor see the record description
     */
    public MajorMinor {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("version numbers must not be negative");
        }
    }

    /**
     * Parses a version string.
     *
     * @param version for example {@code 1.11}, {@code 1.08} or {@code 2}; may be {@code null}
     * @return the version, or empty when the string is not {@code digits[.digits]}
     */
    public static Optional<MajorMinor> parse(String version) {
        if (version == null) {
            return Optional.empty();
        }
        Matcher m = SHAPE.matcher(version);
        if (!m.matches()) {
            return Optional.empty();
        }
        int major = Integer.parseInt(m.group(1));
        int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return Optional.of(new MajorMinor(major, minor));
    }

    @Override
    public int compareTo(MajorMinor other) {
        int c = Integer.compare(major, other.major);
        return c != 0 ? c : Integer.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
