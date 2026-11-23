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

package com.dlap.policyengine.approval;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.dlap.common.constants.DlapConstants;

/**
 * Settings of the approval service, bound from {@code dlap.policyengine.approvals.*}
 * (docs/specs/approvals.md, "Configuration"). A value that would weaken dual control below its
 * floor stops the start-up with the property's name.
 *
 * @param ttl              how long a request waits before it expires; PT1H to P90D
 * @param required         distinct approvals each action class needs
 * @param approverRoles    the roles whose holders may approve or reject; a subset of ADMIN, CDO,
 *                         DIRECTOR and POLICY_MANAGER, with or without the {@code ROLE_} prefix
 * @param requireMfaMarker when true, a decision needs the realm role {@code mfaMarkerRole} in the
 *                         token, which the production realm grants only through MFA-enforced
 *                         roles (IAM-02); set it true in production
 * @param mfaMarkerRole    the marker role, {@code dlap-mfa-required} in the production realm
 * @param breakGlass       break-glass settings
 * @param maxCommentLength longest comment or justification accepted, 1 to 10000 characters
 * @param relayBatchSize   outbox messages one maintenance tick sends, 1 to 500
 * @param sendTimeout      how long the relay waits for the broker to acknowledge one message
 */
@ConfigurationProperties(prefix = "dlap.policyengine.approvals")
public record ApprovalProperties(
        @DefaultValue("P7D") Duration ttl,
        @DefaultValue Required required,
        @DefaultValue({"ADMIN", "CDO", "DIRECTOR", "POLICY_MANAGER"}) List<String> approverRoles,
        @DefaultValue("false") boolean requireMfaMarker,
        @DefaultValue("dlap-mfa-required") String mfaMarkerRole,
        @DefaultValue BreakGlass breakGlass,
        @DefaultValue("2000") int maxCommentLength,
        @DefaultValue("50") int relayBatchSize,
        @DefaultValue("PT5S") Duration sendTimeout) {

    /** The fewest approvals a hard delete may ever need: two people (dual control). */
    public static final int HARD_DELETE_FLOOR = 2;

    /** The fewest approvals any gated action may need. */
    public static final int FLOOR = 1;

    /** The most approvals any request may need. */
    public static final int CEILING = 10;

    /** The roles that may ever approve: the Executive and Leadership tiers of the access model. */
    public static final Set<String> APPROVER_ROLE_CEILING = Set.of(DlapConstants.UserRole.ADMIN,
            DlapConstants.UserRole.CDO, DlapConstants.UserRole.DIRECTOR, DlapConstants.UserRole.POLICY_MANAGER);

    private static final Duration MIN_TTL = Duration.ofHours(1);
    private static final Duration MAX_TTL = Duration.ofDays(90);

    /**
     * Validates the settings and normalises the role names to {@code ROLE_*} authorities.
     */
    public ApprovalProperties {
        if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("dlap.policyengine.approvals.ttl must be PT1H to P90D, was " + ttl);
        }
        required = required == null ? new Required(1, 1, HARD_DELETE_FLOOR, 1) : required;
        breakGlass = breakGlass == null ? new BreakGlass(true, 20) : breakGlass;
        Set<String> roles = new LinkedHashSet<>();
        for (String role : approverRoles == null ? List.<String>of() : approverRoles) {
            String name = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
            name = name.startsWith("ROLE_") ? name.substring("ROLE_".length()) : name;
            if (!APPROVER_ROLE_CEILING.contains(name)) {
                throw new IllegalArgumentException("dlap.policyengine.approvals.approver-roles may name only "
                        + APPROVER_ROLE_CEILING + ", was " + role);
            }
            roles.add("ROLE_" + name);
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("dlap.policyengine.approvals.approver-roles must name at least one role");
        }
        approverRoles = List.copyOf(roles);
        if (mfaMarkerRole == null || mfaMarkerRole.isBlank()) {
            throw new IllegalArgumentException("dlap.policyengine.approvals.mfa-marker-role must not be blank");
        }
        mfaMarkerRole = mfaMarkerRole.strip();
        if (maxCommentLength < 1 || maxCommentLength > 10_000) {
            throw new IllegalArgumentException("dlap.policyengine.approvals.max-comment-length must be 1 to 10000, was "
                    + maxCommentLength);
        }
        if (relayBatchSize < 1 || relayBatchSize > 500) {
            throw new IllegalArgumentException("dlap.policyengine.approvals.relay-batch-size must be 1 to 500, was "
                    + relayBatchSize);
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("dlap.policyengine.approvals.send-timeout must be positive, was "
                    + sendTimeout);
        }
        if (breakGlass.minJustificationLength() > maxCommentLength) {
            throw new IllegalArgumentException("dlap.policyengine.approvals.break-glass.min-justification-length "
                    + "must not exceed max-comment-length");
        }
    }

    /**
     * The defaults, as shipped.
     *
     * @return the default settings
     */
    public static ApprovalProperties defaults() {
        return new ApprovalProperties(Duration.ofDays(7), null, List.copyOf(APPROVER_ROLE_CEILING), false,
                "dlap-mfa-required", null, 2000, 50, Duration.ofSeconds(5));
    }

    /**
     * The {@code ROLE_*} authority of the MFA marker role.
     *
     * @return the authority
     */
    public String mfaMarkerAuthority() {
        return mfaMarkerRole.startsWith("ROLE_") ? mfaMarkerRole : "ROLE_" + mfaMarkerRole;
    }

    /**
     * The approvals a class needs by configuration.
     *
     * @param actionClass the class
     * @return the configured count
     */
    public int requiredFor(ActionClass actionClass) {
        return switch (actionClass) {
            case ARCHIVE -> required.archive();
            case SOFT_DELETE -> required.softDelete();
            case HARD_DELETE -> required.hardDelete();
            case OTHER -> required.other();
        };
    }

    /**
     * The fewest approvals a class may ever need, whatever the configuration says.
     *
     * @param actionClass the class
     * @return 2 for a hard delete, 1 otherwise
     */
    public static int floorFor(ActionClass actionClass) {
        return actionClass == ActionClass.HARD_DELETE ? HARD_DELETE_FLOOR : FLOOR;
    }

    /**
     * Distinct approvals per action class, bound from {@code dlap.policyengine.approvals.required.*}.
     *
     * @param archive    archive; at least 1
     * @param softDelete soft delete; at least 1
     * @param hardDelete hard delete; at least 2 (the dual-control floor)
     * @param other      other approval-gated actions; at least 1
     */
    public record Required(
            @DefaultValue("1") int archive,
            @DefaultValue("1") int softDelete,
            @DefaultValue("2") int hardDelete,
            @DefaultValue("1") int other) {

        /**
         * Rejects a count below its floor or above the ceiling.
         */
        public Required {
            check("archive", archive, FLOOR);
            check("soft-delete", softDelete, FLOOR);
            check("hard-delete", hardDelete, HARD_DELETE_FLOOR);
            check("other", other, FLOOR);
        }

        private static void check(String name, int value, int floor) {
            if (value < floor || value > CEILING) {
                throw new IllegalArgumentException("dlap.policyengine.approvals.required." + name + " must be "
                        + floor + " to " + CEILING + ", was " + value);
            }
        }
    }

    /**
     * Break-glass settings, bound from {@code dlap.policyengine.approvals.break-glass.*}.
     *
     * @param enabled                false refuses every break-glass request
     * @param minJustificationLength shortest justification accepted, at least 20 characters
     */
    public record BreakGlass(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") int minJustificationLength) {

        /**
         * Rejects a justification floor below 20 characters.
         */
        public BreakGlass {
            if (minJustificationLength < 20) {
                throw new IllegalArgumentException("dlap.policyengine.approvals.break-glass.min-justification-length "
                        + "must be at least 20, was " + minJustificationLength);
            }
        }
    }
}
