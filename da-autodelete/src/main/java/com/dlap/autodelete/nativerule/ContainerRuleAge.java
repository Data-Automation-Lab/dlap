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

package com.dlap.autodelete.nativerule;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks that a container rule is age-based (RET-06, Q-677). A rule on a whole bucket, prefix or
 * container keeps deleting matching objects for as long as it is on the platform, objects written after
 * the delete included. A date-based rule (S3 {@code Expiration.Date}, GCS {@code createdBefore}) or a
 * zero-day rule (Azure {@code daysAfter...GreaterThan: 0}) deletes every matching object at the next
 * lifecycle run, a new one the same day. An age-based rule of at least one day deletes only objects at
 * least a day old, and the executor removes it after {@code dlap.autodelete.rule-in-force.max-age}.
 *
 * <p>Accepted per platform, every day count at least 1:
 * <ul>
 *   <li>S3: {@code Expiration.Days}; no {@code Expiration.Date}, no transition dates;
 *       {@code NoncurrentDays} and {@code DaysAfterInitiation} at least 1 when present.</li>
 *   <li>GCS: every Delete rule has {@code age} or, for noncurrent versions,
 *       {@code daysSinceNoncurrentTime}; no date condition ({@code createdBefore},
 *       {@code noncurrentTimeBefore}, {@code customTimeBefore}).</li>
 *   <li>Azure: every delete action has only {@code daysAfter...GreaterThan} counts of at least 1, and
 *       the base blob uses {@code daysAfterModificationGreaterThan}.</li>
 * </ul>
 * A rule of a platform that is not a container rule (BigQuery table expiration) is not checked.
 */
public final class ContainerRuleAge {

    private static final Set<String> GCS_DATE_CONDITIONS = Set.of("createdBefore", "noncurrentTimeBefore",
            "customTimeBefore");

    private static final String[] GCS_DAY_COUNTS = {"age", "daysSinceNoncurrentTime", "daysSinceCustomTime"};

    private ContainerRuleAge() {
    }

    /**
     * Why a rule is not an age-based container rule of at least one day.
     *
     * @param rule the generated rule
     * @return the reason, or empty when the rule is acceptable (or not a container rule)
     */
    public static Optional<String> violation(NativeRule rule) {
        if (rule == null || !rule.platform().containerRule()) {
            return Optional.empty();
        }
        JsonNode doc;
        try {
            doc = RuleDocuments.read(rule.document());
        } catch (IllegalArgumentException e) {
            return Optional.of("the rule document is not JSON");
        }
        return switch (rule.platform()) {
            case S3_LIFECYCLE -> s3(doc);
            case GCS_LIFECYCLE -> gcs(doc);
            case AZURE_BLOB_LIFECYCLE -> azure(doc);
            case BIGQUERY_TABLE_EXPIRATION -> Optional.empty();
        };
    }

    private static Optional<String> s3(JsonNode doc) {
        JsonNode rules = doc.path("rules");
        if (!rules.isArray() || rules.isEmpty()) {
            return Optional.of("the S3 document has no rules");
        }
        for (JsonNode r : rules) {
            String id = r.path("ID").asText();
            JsonNode exp = r.path("Expiration");
            if (exp.has("Date")) {
                return Optional.of("S3 rule " + id + " expires at a date (" + exp.path("Date").asText()
                        + "); a container rule must use Expiration.Days");
            }
            if (!atLeastOne(exp.path("Days"))) {
                return Optional.of("S3 rule " + id + " needs Expiration.Days of at least 1, has "
                        + text(exp.path("Days")));
            }
            for (JsonNode t : r.path("Transitions")) {
                if (t.has("Date")) {
                    return Optional.of("S3 rule " + id + " has a dated transition");
                }
            }
            if (r.has("NoncurrentVersionExpiration")
                    && !atLeastOne(r.path("NoncurrentVersionExpiration").path("NoncurrentDays"))) {
                return Optional.of("S3 rule " + id + " needs NoncurrentDays of at least 1");
            }
            if (r.has("AbortIncompleteMultipartUpload")
                    && !atLeastOne(r.path("AbortIncompleteMultipartUpload").path("DaysAfterInitiation"))) {
                return Optional.of("S3 rule " + id + " needs DaysAfterInitiation of at least 1");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> gcs(JsonNode doc) {
        JsonNode rules = doc.path("lifecycle").path("rule");
        if (!rules.isArray() || rules.isEmpty()) {
            return Optional.of("the GCS document has no rules");
        }
        for (JsonNode r : rules) {
            JsonNode c = r.path("condition");
            for (String dated : GCS_DATE_CONDITIONS) {
                if (c.has(dated)) {
                    return Optional.of("a GCS rule uses the date condition " + dated + " (" + c.path(dated).asText()
                            + "); a container rule must use age");
                }
            }
            boolean live = !c.has("isLive") || c.path("isLive").asBoolean(true);
            JsonNode days = live ? c.path("age") : c.path("daysSinceNoncurrentTime");
            if (!atLeastOne(days)) {
                return Optional.of("a GCS " + (live ? "rule needs age" : "noncurrent rule needs daysSinceNoncurrentTime")
                        + " of at least 1, has " + text(days));
            }
            for (String counted : GCS_DAY_COUNTS) {
                if (c.has(counted) && !atLeastOne(c.path(counted))) {
                    return Optional.of("a GCS rule has " + counted + " " + text(c.path(counted)));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> azure(JsonNode doc) {
        JsonNode rules = doc.path("rules");
        if (!rules.isArray() || rules.isEmpty()) {
            return Optional.of("the Azure document has no rules");
        }
        for (JsonNode r : rules) {
            String name = r.path("name").asText();
            JsonNode actions = r.path("definition").path("actions");
            if (!actions.isObject() || actions.isEmpty()) {
                return Optional.of("Azure rule " + name + " has no actions");
            }
            if (!actions.path("baseBlob").path("delete").has("daysAfterModificationGreaterThan")) {
                return Optional.of("Azure rule " + name + " must delete base blobs by daysAfterModificationGreaterThan");
            }
            for (Iterator<Map.Entry<String, JsonNode>> a = actions.fields(); a.hasNext(); ) {
                Map.Entry<String, JsonNode> action = a.next();
                JsonNode delete = action.getValue().path("delete");
                for (Iterator<Map.Entry<String, JsonNode>> f = delete.fields(); f.hasNext(); ) {
                    Map.Entry<String, JsonNode> field = f.next();
                    if (!field.getKey().startsWith("daysAfter") || !atLeastOne(field.getValue())) {
                        return Optional.of("Azure rule " + name + " deletes " + action.getKey() + " by "
                                + field.getKey() + " " + text(field.getValue())
                                + "; a container rule needs a day count of at least 1");
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean atLeastOne(JsonNode n) {
        return n != null && n.isIntegralNumber() && n.asLong() >= 1;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() ? "none" : n.toString();
    }
}
