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

import com.dlap.autodelete.dto.ApprovalViewDTO;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Applies the executor rules of docs/specs/approvals.md section 9 to one approval, fail closed.
 *
 * <p>The re-read request ({@code GET /approvals/{id}}) is the authority; the Kafka block, when
 * there is one, must agree with it, so write access to {@code dlap.policies.actions} cannot forge
 * an approval. The rules:
 * <ol>
 *   <li>the request is executable: {@code APPROVED}, or {@code APPROVED_BREAK_GLASS};</li>
 *   <li>it is the request the action names, for the same asset (and fire);</li>
 *   <li>its class covers the action; a hard delete accepts only {@code HARD_DELETE};</li>
 *   <li>the approvers are distinct people, at least {@code max(requiredApprovals, floor)} of them,
 *       where the floor is 2 for a hard delete and 1 otherwise; a break-glass release instead needs
 *       one administrator's BREAK_GLASS decision;</li>
 *   <li>no approver is the requester, the submitter, an excluded subject of the request, or a
 *       subject the executor excludes itself (the task submitter).</li>
 * </ol>
 */
public final class ApprovalVerifier {

    /** A soft delete. */
    public static final String SOFT_DELETE = "SOFT_DELETE";
    /** A hard delete. */
    public static final String HARD_DELETE = "HARD_DELETE";

    private static final String APPROVED = "APPROVED";
    private static final String APPROVED_BREAK_GLASS = "APPROVED_BREAK_GLASS";
    private static final String APPROVE = "APPROVE";
    private static final String BREAK_GLASS = "BREAK_GLASS";

    private ApprovalVerifier() {
    }

    /**
     * What the executor expects of the approval.
     *
     * @param approvalId     the request the action names
     * @param assetId        the asset the action acts on
     * @param allowedClasses the classes that cover the action
     * @param fireId         the fire of a policy action, or null
     * @param extraExcluded  subjects that may not have approved, besides the request's own exclusions
     */
    public record Expectation(UUID approvalId, UUID assetId, Set<String> allowedClasses, String fireId,
            Set<String> extraExcluded) {

        /** Copies the sets. */
        public Expectation {
            Objects.requireNonNull(approvalId, "approvalId");
            Objects.requireNonNull(assetId, "assetId");
            allowedClasses = Set.copyOf(allowedClasses);
            extraExcluded = extraExcluded == null ? Set.of() : Set.copyOf(extraExcluded);
        }
    }

    /**
     * The outcome.
     *
     * @param allowed     whether the approval releases the action
     * @param reason      why not, or null
     * @param detail      a sentence for the audit record, or null
     * @param approvers   the distinct approvers in decision order (the administrator on a break-glass release)
     * @param actionClass the approval's class
     * @param breakGlass  whether it was a break-glass release
     */
    public record Verdict(boolean allowed, RefusalReason reason, String detail, List<String> approvers,
            String actionClass, boolean breakGlass) {

        /** Copies the approvers. */
        public Verdict {
            approvers = approvers == null ? List.of() : List.copyOf(approvers);
        }

        static Verdict refuse(RefusalReason reason, String detail) {
            return new Verdict(false, reason, detail, List.of(), null, false);
        }
    }

    /**
     * Checks an approval.
     *
     * @param expected what the action needs
     * @param evidence the Kafka block of a released policy action, or null for an approval the
     *                 executor asked for itself
     * @param view     the re-read request; null when the policy engine does not know it
     * @return the verdict
     */
    public static Verdict verify(Expectation expected, ApprovalEvidence evidence, ApprovalViewDTO view) {
        if (view == null) {
            return Verdict.refuse(RefusalReason.APPROVAL_UNKNOWN, "the policy engine does not know approval "
                    + expected.approvalId());
        }
        String status = upper(view.status());
        if ("REJECTED".equals(status)) {
            return Verdict.refuse(RefusalReason.APPROVAL_REJECTED, "approval " + view.id() + " was rejected");
        }
        if ("EXPIRED".equals(status)) {
            return Verdict.refuse(RefusalReason.APPROVAL_EXPIRED, "approval " + view.id() + " expired");
        }
        if (!view.executable() || !(APPROVED.equals(status) || APPROVED_BREAK_GLASS.equals(status))) {
            return Verdict.refuse(RefusalReason.APPROVAL_NOT_EXECUTABLE, "approval " + view.id() + " is " + status);
        }
        if (!expected.approvalId().equals(view.id()) || !expected.assetId().equals(view.assetId())) {
            return Verdict.refuse(RefusalReason.APPROVAL_MISMATCH, "approval " + view.id() + " is for asset "
                    + view.assetId() + ", not " + expected.assetId());
        }
        if (expected.fireId() != null && !expected.fireId().equalsIgnoreCase(String.valueOf(view.fireId()))) {
            return Verdict.refuse(RefusalReason.APPROVAL_MISMATCH, "approval " + view.id() + " is for fire "
                    + view.fireId() + ", not " + expected.fireId());
        }
        String actionClass = upper(view.actionClass());
        if (!expected.allowedClasses().contains(actionClass)) {
            return Verdict.refuse(RefusalReason.APPROVAL_CLASS, "approval class " + actionClass
                    + " does not cover this action (expected one of " + expected.allowedClasses() + ")");
        }

        boolean breakGlass = APPROVED_BREAK_GLASS.equals(status);
        List<String> deciders = new ArrayList<>();
        List<String> approveSubs = new ArrayList<>();
        for (ApprovalViewDTO.Decision d : view.decisions() == null ? List.<ApprovalViewDTO.Decision>of()
                : view.decisions()) {
            if (d == null || d.approverSub() == null || d.approverSub().isBlank()) {
                continue;
            }
            String kind = upper(d.decision());
            if (APPROVE.equals(kind)) {
                approveSubs.add(d.approverSub());
                deciders.add(d.approverSub());
            } else if (BREAK_GLASS.equals(kind)) {
                deciders.add(d.approverSub());
            }
        }

        Set<String> excluded = new HashSet<>(expected.extraExcluded());
        if (view.excludedSubjects() != null) {
            excluded.addAll(view.excludedSubjects());
        }
        addIfText(excluded, view.requestedBy());
        addIfText(excluded, view.submittedBy());
        for (String sub : deciders) {
            if (excluded.contains(sub)) {
                return Verdict.refuse(RefusalReason.REQUESTER_IS_APPROVER, "approver " + sub
                        + " is the requester, the submitter or an excluded party of approval " + view.id());
            }
        }

        Set<String> distinctApprovers = new LinkedHashSet<>(approveSubs);
        if (distinctApprovers.size() != approveSubs.size()) {
            return Verdict.refuse(RefusalReason.TOO_FEW_APPROVERS, "approval " + view.id()
                    + " counts one person more than once");
        }
        int floor = HARD_DELETE.equals(actionClass) ? RetireProperties.HARD_DELETE_APPROVAL_FLOOR : 1;
        int needed = Math.max(floor, view.requiredApprovals());
        List<String> released;
        if (breakGlass) {
            List<String> glass = view.decisions() == null ? List.of() : view.decisions().stream()
                    .filter(d -> d != null && BREAK_GLASS.equals(upper(d.decision())) && d.approverSub() != null)
                    .map(ApprovalViewDTO.Decision::approverSub).toList();
            if (glass.isEmpty()) {
                return Verdict.refuse(RefusalReason.TOO_FEW_APPROVERS, "break-glass approval " + view.id()
                        + " has no BREAK_GLASS decision");
            }
            released = new ArrayList<>(distinctApprovers);
            released.addAll(glass);
        } else {
            if (distinctApprovers.size() < needed) {
                return Verdict.refuse(RefusalReason.TOO_FEW_APPROVERS, "approval " + view.id() + " has "
                        + distinctApprovers.size() + " distinct approver(s); " + needed + " needed");
            }
            released = new ArrayList<>(distinctApprovers);
        }

        if (evidence != null) {
            Verdict mismatch = checkEvidence(evidence, view, status, actionClass, new HashSet<>(released), floor);
            if (mismatch != null) {
                return mismatch;
            }
        }
        return new Verdict(true, null, null, released, actionClass, breakGlass);
    }

    /** The Kafka block must name the same request, status and class, and only people who decided it. */
    private static Verdict checkEvidence(ApprovalEvidence evidence, ApprovalViewDTO view, String status,
            String actionClass, Set<String> deciders, int floor) {
        if (!view.id().equals(evidence.approvalId()) || !status.equals(upper(evidence.status()))
                || !actionClass.equals(upper(evidence.actionClass()))) {
            return Verdict.refuse(RefusalReason.APPROVAL_MISMATCH, "the approval block on the action ("
                    + evidence.approvalId() + ", " + evidence.status() + ", " + evidence.actionClass()
                    + ") does not match the policy engine's record");
        }
        if (!deciders.containsAll(evidence.approvers())) {
            return Verdict.refuse(RefusalReason.APPROVAL_MISMATCH, "the approval block on the action names approvers"
                    + " the policy engine did not record");
        }
        if (!evidence.breakGlass() && new HashSet<>(evidence.approvers()).size() < floor) {
            return Verdict.refuse(RefusalReason.TOO_FEW_APPROVERS, "the approval block names fewer than " + floor
                    + " distinct approvers");
        }
        return null;
    }

    private static void addIfText(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value);
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
