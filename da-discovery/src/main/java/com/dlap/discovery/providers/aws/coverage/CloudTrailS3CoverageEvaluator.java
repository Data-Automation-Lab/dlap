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

package com.dlap.discovery.providers.aws.coverage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.dlap.discovery.coverage.CoverageVerdict;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Decides from CloudTrail trails and their event selectors whether S3 object reads of one bucket
 * are recorded (COV-01). Pure: it reads API responses as JSON trees in the shapes of the CloudTrail
 * API reference (DescribeTrails, GetTrailStatus, GetEventSelectors) and calls nothing.
 *
 * <p>A trail counts for a bucket when it is logging ({@code IsLogging}) and is multi-region or has
 * its home region in the bucket's region. Its selectors are then read:
 * <ul>
 *   <li><b>Basic selectors</b> ({@code EventSelectors}): a {@code DataResources} entry of type
 *       {@code AWS::S3::Object} with {@code ReadWriteType} {@code ReadOnly} or {@code All}. Values are
 *       ARN prefixes (API reference, DataResource): {@code arn:aws:s3} or {@code arn:aws:s3:::bucket/}
 *       cover the bucket, {@code arn:aws:s3:::bucket/prefix} covers a prefix subset.</li>
 *   <li><b>Advanced selectors</b> ({@code AdvancedEventSelectors}): field selectors are ANDed
 *       within a selector; values of one operator are ORed (API reference,
 *       AdvancedFieldSelector). A selector counts when {@code eventCategory} equals {@code Data},
 *       {@code resources.type} equals {@code AWS::S3::Object} and {@code readOnly} does not limit it
 *       to writes. {@code resources.ARN} is matched as a string against the bucket's object ARN
 *       prefix; a filter on any other field ({@code eventName}, {@code userIdentity.arn},
 *       {@code eventType}, {@code sessionCredentialFromConsole}, an event source other than S3)
 *       records only some reads, so it gives at most {@code PARTIAL}.</li>
 * </ul>
 *
 * <p>Selectors and trails are ORed: one that covers the bucket fully makes it {@code FULL}. Several
 * partial ones stay {@code PARTIAL} even when they might add up: proving a union of prefixes and
 * filters complete is not attempted. A trail whose status or selectors could not be read makes the
 * result unknown unless another trail proves {@code FULL}.
 */
public final class CloudTrailS3CoverageEvaluator {

    /** The spec's wording for a selector that covers part of the bucket's keys. */
    public static final String PREFIX_SUBSET = "CloudTrail data events cover a prefix subset";

    private static final String S3_OBJECT = "AWS::S3::Object";

    private CloudTrailS3CoverageEvaluator() {
    }

    /**
     * One trail as the collector read it.
     *
     * @param arn           the trail ARN
     * @param homeRegion    the trail's home region
     * @param multiRegion   {@code IsMultiRegionTrail}
     * @param logging       {@code IsLogging} from GetTrailStatus; {@code null} when it could not be read
     * @param selectors     the GetEventSelectors response; {@code null} when it could not be read
     * @param unknownReason why the status or the selectors are missing, when one is
     */
    public record TrailState(String arn, String homeRegion, boolean multiRegion, Boolean logging,
                             JsonNode selectors, String unknownReason) {
    }

    /** How much of the bucket one selector or trail covers. */
    enum Cover { NONE, PARTIAL, FULL }

    /** A coverage with the gaps that explain a PARTIAL. */
    record Result(Cover cover, List<String> gaps) {
        static Result none() {
            return new Result(Cover.NONE, List.of());
        }

        static Result full() {
            return new Result(Cover.FULL, List.of());
        }

        static Result partial(String gap) {
            return new Result(Cover.PARTIAL, List.of(gap));
        }
    }

    /**
     * Reads the trail list of a DescribeTrails response.
     *
     * @param describeTrails the response
     * @return the trails: ARN, home region, multi-region flag
     */
    public static List<TrailState> trails(JsonNode describeTrails) {
        List<TrailState> out = new ArrayList<>();
        for (JsonNode t : describeTrails.path("trailList")) {
            String arn = t.path("TrailARN").asText("");
            if (arn.isBlank()) {
                continue;
            }
            out.add(new TrailState(arn, t.path("HomeRegion").asText(""), t.path("IsMultiRegionTrail").asBoolean(false),
                    null, null, null));
        }
        return out;
    }

    /**
     * The verdict for one bucket.
     *
     * @param partition    the ARN partition ({@code aws}, {@code aws-cn}, {@code aws-us-gov})
     * @param bucket       the bucket name
     * @param bucketRegion the bucket's region
     * @param trails       every trail that may apply, with status and selectors
     * @return the verdict
     */
    public static CoverageVerdict evaluate(String partition, String bucket, String bucketRegion, List<TrailState> trails) {
        String objectPrefix = "arn:" + partition + ":s3:::" + bucket + "/";
        List<String> gaps = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        boolean partial = false;
        for (TrailState t : trails) {
            if (!t.multiRegion() && !t.homeRegion().equalsIgnoreCase(bucketRegion)) {
                continue;
            }
            if (Boolean.FALSE.equals(t.logging())) {
                gaps.add("trail " + t.arn() + " is not logging");
                continue;
            }
            if (t.logging() == null || t.selectors() == null) {
                unknown.add(t.unknownReason() == null ? "trail " + t.arn() + " could not be read" : t.unknownReason());
                continue;
            }
            Result r = trail(t.selectors(), objectPrefix);
            if (r.cover() == Cover.FULL) {
                return CoverageVerdict.full(List.of("trail " + t.arn() + " records S3 data read events for the bucket"));
            }
            if (r.cover() == Cover.PARTIAL) {
                partial = true;
                for (String gap : r.gaps()) {
                    gaps.add(gap + " (trail " + t.arn() + ")");
                }
            }
        }
        if (!unknown.isEmpty()) {
            List<String> reasons = new ArrayList<>(unknown);
            reasons.addAll(gaps);
            return CoverageVerdict.unknown(reasons);
        }
        if (partial) {
            return CoverageVerdict.partial(gaps);
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("no logging CloudTrail trail records S3 data read events for the bucket");
        reasons.addAll(gaps);
        return CoverageVerdict.none(reasons);
    }

    /** The best cover of a trail's selectors: they are ORed. */
    static Result trail(JsonNode response, String objectPrefix) {
        List<Result> results = new ArrayList<>();
        for (JsonNode s : response.path("EventSelectors")) {
            results.add(basic(s, objectPrefix));
        }
        for (JsonNode s : response.path("AdvancedEventSelectors")) {
            results.add(advanced(s, objectPrefix));
        }
        return best(results);
    }

    static Result basic(JsonNode selector, String objectPrefix) {
        String rw = selector.path("ReadWriteType").asText("All");
        if ("WriteOnly".equalsIgnoreCase(rw)) {
            return Result.none();
        }
        List<Result> results = new ArrayList<>();
        for (JsonNode resource : selector.path("DataResources")) {
            if (!S3_OBJECT.equals(resource.path("Type").asText())) {
                continue;
            }
            for (JsonNode value : resource.path("Values")) {
                results.add(prefixValue(value.asText(""), objectPrefix));
            }
        }
        return best(results);
    }

    /** A basic-selector value or an advanced {@code StartsWith} value: an ARN prefix. */
    static Result prefixValue(String value, String objectPrefix) {
        if (value.isEmpty()) {
            return Result.none();
        }
        if (objectPrefix.startsWith(value)) {
            return Result.full();
        }
        if (value.startsWith(objectPrefix)) {
            return Result.partial(PREFIX_SUBSET);
        }
        return Result.none();
    }

    static Result advanced(JsonNode selector, String objectPrefix) {
        boolean data = false;
        boolean s3Objects = false;
        boolean reads = true;
        Result arn = Result.full();
        List<String> limits = new ArrayList<>();
        for (JsonNode fs : selector.path("FieldSelectors")) {
            String field = fs.path("Field").asText("");
            switch (field) {
                case "eventCategory" -> data = values(fs, "Equals").contains("Data");
                case "resources.type" -> s3Objects = values(fs, "Equals").contains(S3_OBJECT);
                case "readOnly" -> reads = readsAllowed(fs);
                case "resources.ARN" -> arn = and(arn, arnCover(fs, objectPrefix));
                case "eventSource" -> {
                    if (!onlyS3Source(fs)) {
                        limits.add("an advanced selector filters on eventSource");
                    }
                }
                default -> limits.add("an advanced selector filters on " + (field.isEmpty() ? "an unnamed field" : field));
            }
        }
        if (!data || !s3Objects || !reads || arn.cover() == Cover.NONE) {
            return Result.none();
        }
        if (limits.isEmpty()) {
            return arn;
        }
        List<String> gaps = new ArrayList<>(arn.gaps());
        gaps.addAll(limits);
        return new Result(Cover.PARTIAL, gaps);
    }

    /** {@code readOnly}: only {@code Equals} is valid; {@code false} alone means writes only. */
    private static boolean readsAllowed(JsonNode fs) {
        List<String> eq = values(fs, "Equals");
        if (!eq.isEmpty()) {
            return eq.stream().anyMatch(v -> v.equalsIgnoreCase("true"));
        }
        List<String> ne = values(fs, "NotEquals");
        return ne.stream().noneMatch(v -> v.equalsIgnoreCase("true"));
    }

    private static boolean onlyS3Source(JsonNode fs) {
        Iterator<Map.Entry<String, JsonNode>> it = fs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if ("Field".equals(e.getKey())) {
                continue;
            }
            if (!"Equals".equals(e.getKey())) {
                return false;
            }
            List<String> eq = values(fs, "Equals");
            if (!eq.contains("s3.amazonaws.com")) {
                return false;
            }
        }
        return true;
    }

    /** {@code resources.ARN}: positive operators are ANDed with each other, then the exclusions apply. */
    static Result arnCover(JsonNode fs, String objectPrefix) {
        Result cover = Result.full();
        boolean anyPositive = false;
        List<String> starts = values(fs, "StartsWith");
        if (!starts.isEmpty()) {
            anyPositive = true;
            cover = and(cover, best(starts.stream().map(v -> prefixValue(v, objectPrefix)).toList()));
        }
        List<String> equals = values(fs, "Equals");
        if (!equals.isEmpty()) {
            anyPositive = true;
            // Equals names exact object ARNs: at best some objects of the bucket.
            boolean some = equals.stream().anyMatch(v -> v.startsWith(objectPrefix));
            cover = and(cover, some ? Result.partial("an advanced selector names single objects") : Result.none());
        }
        if (!values(fs, "EndsWith").isEmpty()) {
            anyPositive = true;
            cover = and(cover, Result.partial("an advanced selector matches resources.ARN by suffix"));
        }
        if (!anyPositive && !hasAnyOperator(fs)) {
            return Result.none();
        }
        for (String v : values(fs, "NotStartsWith")) {
            if (objectPrefix.startsWith(v)) {
                return Result.none();
            }
            if (v.startsWith(objectPrefix)) {
                cover = and(cover, Result.partial("an advanced selector excludes a key prefix of the bucket"));
            }
        }
        for (String v : values(fs, "NotEquals")) {
            if (v.startsWith(objectPrefix)) {
                cover = and(cover, Result.partial("an advanced selector excludes single objects of the bucket"));
            }
        }
        if (!values(fs, "NotEndsWith").isEmpty()) {
            cover = and(cover, Result.partial("an advanced selector excludes resources.ARN by suffix"));
        }
        return cover;
    }

    private static boolean hasAnyOperator(JsonNode fs) {
        for (String op : List.of("NotStartsWith", "NotEquals", "NotEndsWith")) {
            if (!values(fs, op).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> values(JsonNode fs, String operator) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : fs.path(operator)) {
            out.add(v.asText(""));
        }
        return out;
    }

    /** AND: the smaller cover, with both sets of gaps. */
    static Result and(Result a, Result b) {
        Cover c = a.cover().ordinal() <= b.cover().ordinal() ? a.cover() : b.cover();
        if (c == Cover.NONE) {
            return Result.none();
        }
        List<String> gaps = new ArrayList<>(a.gaps());
        for (String g : b.gaps()) {
            if (!gaps.contains(g)) {
                gaps.add(g);
            }
        }
        return new Result(c, c == Cover.FULL ? List.of() : gaps);
    }

    /** OR: the larger cover; the gaps of every partial when nothing is full. */
    static Result best(List<Result> results) {
        List<String> gaps = new ArrayList<>();
        boolean partial = false;
        for (Result r : results) {
            if (r.cover() == Cover.FULL) {
                return Result.full();
            }
            if (r.cover() == Cover.PARTIAL) {
                partial = true;
                for (String g : r.gaps()) {
                    if (!gaps.contains(g)) {
                        gaps.add(g);
                    }
                }
            }
        }
        return partial ? new Result(Cover.PARTIAL, gaps) : Result.none();
    }

    /**
     * The ARN partition of a region.
     *
     * @param region an AWS region
     * @return {@code aws-cn}, {@code aws-us-gov} or {@code aws}
     */
    public static String partition(String region) {
        String r = region == null ? "" : region.toLowerCase(Locale.ROOT);
        if (r.startsWith("cn-")) {
            return "aws-cn";
        }
        if (r.startsWith("us-gov-")) {
            return "aws-us-gov";
        }
        return "aws";
    }
}
