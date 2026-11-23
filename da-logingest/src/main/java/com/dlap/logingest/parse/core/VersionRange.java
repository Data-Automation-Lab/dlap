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

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The versions one {@link LogRecordParser} reads (design B1 "supports()").
 *
 * <p>Two shapes, one per {@link VersionScheme}:
 * <ul>
 *   <li>{@link MajorMinorRange}: one major, from a floor minor up to the highest known minor, for
 *       example CloudTrail {@code [1.0, 1.11]}. A minor above the highest known is a
 *       {@link VersionMatch.Status#NEWER_MINOR}; another major or a minor below the floor is
 *       unsupported.</li>
 *   <li>{@link NamedVersions}: an unordered set of names, for example GCP
 *       {@code {auditlog, bq-auditmetadata}}. Only listed names are supported.</li>
 * </ul>
 * A parser that reads two majors registers as two beans with two ranges.
 */
public sealed interface VersionRange permits VersionRange.MajorMinorRange, VersionRange.NamedVersions {

    /**
     * Returns the scheme.
     *
     * @return the scheme of the envelope this range belongs to
     */
    VersionScheme scheme();

    /**
     * Answers whether a version is read by this range.
     *
     * @param version the detected or pinned version; may be {@code null}
     * @return the match
     */
    VersionMatch match(String version);

    /**
     * Whether two version strings name the same version under this scheme ({@code 1.08} and
     * {@code 1.8} are the same major.minor version).
     *
     * @param a one version
     * @param b another version
     * @return true when equal under the scheme
     */
    boolean sameVersion(String a, String b);

    /**
     * Whether two ranges could both claim one version, which the registry refuses.
     *
     * @param other another range of the same envelope
     * @return true when they overlap, or when their schemes differ
     */
    boolean overlaps(VersionRange other);

    /**
     * A short description for logs and error messages.
     *
     * @return for example {@code [1.0, 1.11]} or {@code {auditlog, bq-auditmetadata}}
     */
    String describe();

    /**
     * A major.minor range.
     *
     * @param floor        the lowest supported version, for example {@code 1.0}
     * @param highestKnown the highest known version of the same major, for example {@code 1.11}
     * @return the range
     */
    static VersionRange majorMinor(String floor, String highestKnown) {
        return new MajorMinorRange(floor, highestKnown);
    }

    /**
     * A set of named versions.
     *
     * @param names the names; at least one
     * @return the range
     */
    static VersionRange named(Collection<String> names) {
        return new NamedVersions(new TreeSet<>(names));
    }

    /**
     * A set of named versions.
     *
     * @param names the names; at least one
     * @return the range
     */
    static VersionRange named(String... names) {
        return named(java.util.List.of(names));
    }

    /**
     * One major, from {@code floor} to {@code highestKnown}.
     *
     * @param floor        lowest supported version of the major
     * @param highestKnown highest known version of the major; the effective version of a newer minor
     */
    record MajorMinorRange(String floor, String highestKnown) implements VersionRange {

        /**
         * Validates the bounds.
         *
         * @param floor        see the record description
         * @param highestKnown see the record description
         */
        public MajorMinorRange {
            FormatNames.requireVersion(floor);
            FormatNames.requireVersion(highestKnown);
            MajorMinor lo = MajorMinor.parse(floor)
                    .orElseThrow(() -> new IllegalArgumentException("floor is not a major.minor version"));
            MajorMinor hi = MajorMinor.parse(highestKnown)
                    .orElseThrow(() -> new IllegalArgumentException("highestKnown is not a major.minor version"));
            if (lo.major() != hi.major()) {
                throw new IllegalArgumentException("a major.minor range covers one major; register one parser per major");
            }
            if (lo.compareTo(hi) > 0) {
                throw new IllegalArgumentException("floor " + floor + " is above highestKnown " + highestKnown);
            }
        }

        /**
         * Returns the major this range covers.
         *
         * @return the major
         */
        public int major() {
            return floorVersion().major();
        }

        /**
         * Returns the floor as numbers.
         *
         * @return the floor
         */
        public MajorMinor floorVersion() {
            return MajorMinor.parse(floor).orElseThrow();
        }

        /**
         * Returns the highest known version as numbers.
         *
         * @return the highest known version
         */
        public MajorMinor highestVersion() {
            return MajorMinor.parse(highestKnown).orElseThrow();
        }

        @Override
        public VersionScheme scheme() {
            return VersionScheme.MAJOR_MINOR;
        }

        @Override
        public VersionMatch match(String version) {
            if (!FormatNames.isVersion(version)) {
                return VersionMatch.unsupported("version is missing or malformed");
            }
            MajorMinor v = MajorMinor.parse(version).orElse(null);
            if (v == null) {
                return VersionMatch.unsupported("version is not major.minor");
            }
            if (v.major() != major()) {
                return VersionMatch.unsupported("unknown major; supported major " + major());
            }
            if (v.compareTo(floorVersion()) < 0) {
                return VersionMatch.unsupported("minor below the supported floor " + floor);
            }
            if (v.compareTo(highestVersion()) > 0) {
                return VersionMatch.newerMinor(highestKnown);
            }
            return VersionMatch.supported(version);
        }

        @Override
        public boolean sameVersion(String a, String b) {
            if (Objects.equals(a, b)) {
                return true;
            }
            var pa = MajorMinor.parse(a);
            var pb = MajorMinor.parse(b);
            return pa.isPresent() && pa.equals(pb);
        }

        @Override
        public boolean overlaps(VersionRange other) {
            // Two parsers for one major would make the newer-minor answer ambiguous.
            return !(other instanceof MajorMinorRange mm) || mm.major() == major();
        }

        @Override
        public String describe() {
            return "[" + floor + ", " + highestKnown + "]";
        }
    }

    /**
     * Named versions.
     *
     * @param names the supported names, sorted
     */
    record NamedVersions(SortedSet<String> names) implements VersionRange {

        /**
         * Validates and freezes the names.
         *
         * @param names see the record description
         */
        public NamedVersions {
            Objects.requireNonNull(names, "names");
            if (names.isEmpty()) {
                throw new IllegalArgumentException("a named range needs at least one version");
            }
            names.forEach(FormatNames::requireVersion);
            names = Collections.unmodifiableSortedSet(new TreeSet<>(names));
        }

        @Override
        public VersionScheme scheme() {
            return VersionScheme.NAMED;
        }

        @Override
        public VersionMatch match(String version) {
            if (!FormatNames.isVersion(version)) {
                return VersionMatch.unsupported("version is missing or malformed");
            }
            return names.contains(version)
                    ? VersionMatch.supported(version)
                    : VersionMatch.unsupported("unknown named version");
        }

        @Override
        public boolean sameVersion(String a, String b) {
            return Objects.equals(a, b);
        }

        @Override
        public boolean overlaps(VersionRange other) {
            if (!(other instanceof NamedVersions n)) {
                return true;
            }
            return n.names().stream().anyMatch(names::contains);
        }

        @Override
        public String describe() {
            return names.toString().replace('[', '{').replace(']', '}');
        }
    }
}
