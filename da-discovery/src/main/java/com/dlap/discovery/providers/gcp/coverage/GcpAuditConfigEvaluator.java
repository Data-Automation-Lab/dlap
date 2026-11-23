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

package com.dlap.discovery.providers.gcp.coverage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.dlap.discovery.coverage.CoverageLevel;
import com.dlap.discovery.coverage.CoverageVerdict;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Decides from IAM-policy audit configs and bucket settings whether GCP data reads are recorded
 * (COV-01). Pure: it reads API responses as JSON trees (Resource Manager v3 {@code getIamPolicy},
 * Cloud Storage JSON API bucket resource and bucket IAM policy) and calls nothing.
 *
 * <p>Rules, with their sources:
 * <ul>
 *   <li>A service's {@code DATA_READ} audit logs are on when an {@code auditConfigs} entry for the
 *       service or for {@code allServices} lists {@code DATA_READ}, at the project or any folder or
 *       organization above it. Levels combine as a union, so a lower level cannot switch off what a
 *       higher one enabled, and exempted principals add up across levels and across the two
 *       entries (google/iam/v1/policy.proto, AuditConfig; Cloud Audit Logs, "Configure Data Access
 *       audit logs").</li>
 *   <li>Cloud Storage {@code DATA_READ} is off by default; BigQuery data access logs are on by
 *       default and cannot be switched off (same page).</li>
 *   <li>Any exempted principal leaves reads by that principal unrecorded: {@code PARTIAL}. The
 *       reason gives the count, never the principals.</li>
 *   <li>Cloud Audit Logs does not track access to public objects (Cloud Storage, "Cloud Audit Logs
 *       with Cloud Storage"): a bucket that grants a role to {@code allUsers} or
 *       {@code allAuthenticatedUsers}, or that keeps fine-grained ACLs without enforced public
 *       access prevention (so an object may be public), is at most {@code PARTIAL}.</li>
 *   <li>BigQuery records table reads, including {@code tabledata.list} and Storage Read API read
 *       sessions, as {@code TableDataRead} in BigQueryAuditMetadata (reasons
 *       {@code TABLEDATA_LIST_REQUEST}, {@code CREATE_READ_SESSION}; vendored
 *       {@code bigquery_audit_metadata.proto}). Whether reads by jobs that run in another project
 *       land in the table's project is not yet verified live; until an operator confirms it, a
 *       BigQuery project is at most {@code PARTIAL}.</li>
 *   <li>A level whose policy could not be read could hold exemptions or the enablement itself, so
 *       a result that depends on it is unknown, naming the permission.</li>
 * </ul>
 */
public final class GcpAuditConfigEvaluator {

    /** Cloud Storage service name in audit configs. */
    public static final String STORAGE = "storage.googleapis.com";
    /** BigQuery service name in audit configs. */
    public static final String BIGQUERY = "bigquery.googleapis.com";
    /** The audit-config wildcard service. */
    public static final String ALL_SERVICES = "allServices";

    /** Reason of a BigQuery project while cross-project read logging is not confirmed. */
    public static final String BIGQUERY_CROSS_PROJECT_UNVERIFIED = "reads by jobs that run in other projects: "
            + "logging in the table's project not verified (dlap.discovery.coverage.gcp.bigquery-cross-project-reads-verified)";

    private static final Set<String> PUBLIC_MEMBERS = Set.of("allusers", "allauthenticatedusers");

    private GcpAuditConfigEvaluator() {
    }

    /**
     * The IAM policy of one level of the resource hierarchy.
     *
     * @param resource {@code projects/<id>}, {@code folders/<n>} or {@code organizations/<n>}
     * @param policy   the getIamPolicy response; {@code null} when it could not be read
     * @param denied   the refusal, when the policy is missing
     */
    public record PolicyAt(String resource, JsonNode policy, MissingPermissionException denied) {
    }

    /**
     * Whether {@code DATA_READ} audit logs of a service are recorded across a hierarchy.
     *
     * @param service   the service, for example {@value #STORAGE}
     * @param levels    the project first, then its ancestors; an incomplete ancestry ends with a
     *                  level whose policy is {@code null}
     * @param defaultOn whether the service records data reads without any audit config (BigQuery)
     * @return the verdict
     */
    public static CoverageVerdict dataRead(String service, List<PolicyAt> levels, boolean defaultOn) {
        boolean enabled = defaultOn;
        String enabledAt = defaultOn ? "on by default" : null;
        int exempted = 0;
        List<String> exemptedAt = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (PolicyAt level : levels) {
            if (level.policy() == null) {
                unknown.add(level.denied() == null ? "the IAM policy of " + level.resource() + " could not be read"
                        : level.denied().reason());
                continue;
            }
            for (JsonNode config : level.policy().path("auditConfigs")) {
                String s = config.path("service").asText("");
                if (!s.equals(service) && !s.equals(ALL_SERVICES)) {
                    continue;
                }
                for (JsonNode logConfig : config.path("auditLogConfigs")) {
                    if (!"DATA_READ".equals(logConfig.path("logType").asText(""))) {
                        continue;
                    }
                    if (!enabled) {
                        enabled = true;
                        enabledAt = "enabled at " + level.resource() + " (" + s + ")";
                    }
                    int n = logConfig.path("exemptedMembers").size();
                    if (n > 0) {
                        exempted += n;
                        exemptedAt.add(level.resource() + " (" + s + ")");
                    }
                }
            }
        }
        if (enabled && exempted > 0) {
            // Provable without the unknown levels: logging is on, and some principals are exempt.
            return CoverageVerdict.partial(List.of(exempted + " principal(s) exempted from " + service
                    + " DATA_READ audit logs at " + String.join(", ", exemptedAt)));
        }
        if (!unknown.isEmpty()) {
            return CoverageVerdict.unknown(unknown);
        }
        if (!enabled) {
            return CoverageVerdict.none(List.of(service + " DATA_READ audit logs are not enabled for the project "
                    + "or its folders and organization"));
        }
        return CoverageVerdict.full(List.of(service + " DATA_READ audit logs are recorded (" + enabledAt
                + ") with no exempted principals"));
    }

    /**
     * The verdict for one Cloud Storage bucket.
     *
     * @param storage   the project's {@value #STORAGE} verdict
     * @param bucket    the bucket resource (Cloud Storage JSON API)
     * @param bucketIam the bucket's IAM policy; {@code null} when not needed or not readable
     * @param iamDenied the refusal of the bucket IAM read, when there was one
     * @return the verdict
     */
    public static CoverageVerdict gcsBucket(CoverageVerdict storage, JsonNode bucket, JsonNode bucketIam,
                                            MissingPermissionException iamDenied) {
        return gcsBucketWithReason(storage, bucket, bucketIam, iamDenied == null ? null : iamDenied.reason());
    }

    /**
     * The verdict for one Cloud Storage bucket whose IAM policy may be unreadable for a reason other
     * than a refusal (a 404 for a bucket deleted during the run, a 429, a 5xx). When the policy is
     * needed to call the bucket {@code FULL} and cannot be read, the verdict is unknown and carries
     * {@code iamUnreadable} as its reason (Q-474).
     *
     * @param storage        the project's {@value #STORAGE} verdict
     * @param bucket         the bucket resource (Cloud Storage JSON API)
     * @param bucketIam      the bucket's IAM policy; {@code null} when not needed or not readable
     * @param iamUnreadable  why the bucket IAM read failed, when it did; a resource name and a status,
     *                       never a credential
     * @return the verdict
     */
    public static CoverageVerdict gcsBucketWithReason(CoverageVerdict storage, JsonNode bucket, JsonNode bucketIam,
                                                      String iamUnreadable) {
        if (!storage.known() || storage.level() == CoverageLevel.NONE) {
            return storage;
        }
        JsonNode iamConfig = bucket.path("iamConfiguration");
        boolean preventionEnforced = "enforced".equalsIgnoreCase(iamConfig.path("publicAccessPrevention").asText(""));
        if (preventionEnforced) {
            return storage;
        }
        List<String> gaps = new ArrayList<>();
        if (!iamConfig.path("uniformBucketLevelAccess").path("enabled").asBoolean(false)) {
            gaps.add("fine-grained object ACLs without enforced public access prevention: "
                    + "reads of objects made public are not logged");
        }
        if (bucketIam == null) {
            if (storage.level() == CoverageLevel.FULL && gaps.isEmpty()) {
                return CoverageVerdict.unknown(List.of(iamUnreadable == null
                        ? "the bucket IAM policy could not be read" : iamUnreadable));
            }
        } else if (grantsPublicAccess(bucketIam)) {
            gaps.add("the bucket grants access to allUsers or allAuthenticatedUsers: reads of public objects are not logged");
        }
        return storage.limitedBy(gaps);
    }

    /**
     * The verdict for a project's BigQuery datasets and tables.
     *
     * @param levels                    the project and its ancestors
     * @param crossProjectReadsVerified whether an operator confirmed cross-project read logging
     * @return the verdict
     */
    public static CoverageVerdict bigQuery(List<PolicyAt> levels, boolean crossProjectReadsVerified) {
        CoverageVerdict v = dataRead(BIGQUERY, levels, true);
        if (crossProjectReadsVerified) {
            return v;
        }
        return v.limitedBy(List.of(BIGQUERY_CROSS_PROJECT_UNVERIFIED));
    }

    /**
     * Whether a bucket IAM policy grants any role to a public member.
     *
     * @param policy the policy
     * @return true when {@code allUsers} or {@code allAuthenticatedUsers} is a member of a binding
     */
    static boolean grantsPublicAccess(JsonNode policy) {
        for (JsonNode binding : policy.path("bindings")) {
            for (JsonNode member : binding.path("members")) {
                if (PUBLIC_MEMBERS.contains(member.asText("").toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
