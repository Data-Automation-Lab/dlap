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

package com.dlap.autodelete.retire;

/**
 * Why the delete executor refused an action. The code is the machine reason written into the
 * audit record ({@code details.reason}) and shown in the would-delete list.
 */
public enum RefusalReason {
    /** A destructive action arrived without an approval (approvals.md section 9, rule 1). */
    APPROVAL_MISSING("approval-missing"),
    /** The approval is not APPROVED or APPROVED_BREAK_GLASS, or not executable on re-read. */
    APPROVAL_NOT_EXECUTABLE("approval-not-executable"),
    /** The policy engine does not know the approval. */
    APPROVAL_UNKNOWN("approval-unknown"),
    /** The approval names another request, asset, fire or status than the action. */
    APPROVAL_MISMATCH("approval-mismatch"),
    /** The approval's class does not cover the action (rule 2). */
    APPROVAL_CLASS("approval-class"),
    /** Fewer distinct approvers than required; a hard delete needs at least 2 (rule 2). */
    TOO_FEW_APPROVERS("too-few-approvers"),
    /** An approver is the requester, the submitter or another excluded party (separation of duties). */
    REQUESTER_IS_APPROVER("requester-is-approver"),
    /** An approver rejected the request. */
    APPROVAL_REJECTED("approval-rejected"),
    /** Nobody decided the request in time. */
    APPROVAL_EXPIRED("approval-expired"),
    /** The asset is on legal hold, or the catalog did not say it is not. */
    LEGAL_HOLD("legal-hold"),
    /** The asset's read coverage is not FULL (COV-01: PARTIAL counts as unknown). */
    COVERAGE_NOT_FULL("coverage-not-full"),
    /** The catalog does not know the asset. */
    ASSET_UNKNOWN("asset-unknown"),
    /** No canonical asset URI could be derived (ADR-0047). */
    URI_UNRESOLVABLE("uri-unresolvable"),
    /** The action's URI and the catalog's URI name different assets. */
    URI_MISMATCH("uri-mismatch"),
    /** No native rule and no engine delete exists for the asset kind. */
    NO_EXECUTOR("no-executor"),
    /** Another service refused the executor's own call (a 4xx answer). */
    REMOTE_REFUSED("remote-refused"),
    /** The request is malformed (no asset id, a non-UUID asset id on the task API). */
    INVALID("invalid"),
    /** A restore asked for a record that does not exist or is past the point of restore. */
    NOT_RESTORABLE("not-restorable"),
    /** A restore could not remove the native rule of a rule in force; the record stays as it was. */
    RULE_REMOVAL_FAILED("rule-removal-failed"),
    /**
     * A bucket, prefix or container rule is date-based or zero-day; only an age-based rule of at least one
     * day is applied (RET-06, Q-677).
     */
    DATE_RULE_UNSUPPORTED("date-rule-unsupported"),
    /**
     * The bucket has versioning Enabled or Suspended; a native delete rule there only adds delete markers,
     * which prove nothing, so it is refused until a live capture settles the proof (RET-06, Q-656).
     */
    VERSIONED_BUCKET_UNSUPPORTED("versioned-bucket-unsupported"),
    /**
     * Removing DLAP's rule found no matching rule on the platform: the rule was removed by someone else, or
     * it is there in a form that no longer matches and may still be in force (RET-06, Q-682).
     */
    RULE_NOT_FOUND("rule-not-found"),
    /** The disposition was applied before; an applied disposition is never applied again (Q-683). */
    ALREADY_APPLIED("already-applied");

    private final String code;

    RefusalReason(String code) {
        this.code = code;
    }

    /** @return the machine code, for example {@code legal-hold} */
    public String code() {
        return code;
    }
}
