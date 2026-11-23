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

package com.dlap.autodelete.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per asset that has entered the soft-delete window.
 *
 * <p>State machine (RET-02, Flyway V5):
 * <pre>
 * SOFT_DELETED -(window ends, HARD_DELETE approval requested)-&gt; HARD_DELETE_PENDING_APPROVAL
 * HARD_DELETE_PENDING_APPROVAL -(approved, every rule passes, dry run on)-&gt; DRY_RUN_COMPLETE
 * HARD_DELETE_PENDING_APPROVAL -(approved, every rule passes, dry run off)-&gt; AWAITING_PROOF
 * HARD_DELETE_PENDING_APPROVAL -(rejected, expired, or a rule refuses)-&gt; HARD_DELETE_REFUSED
 * HARD_DELETE_PENDING_APPROVAL -(the provider call fails)-&gt; HARD_DELETE_FAILED
 * AWAITING_PROOF -(the provider's own delete event is seen, object-level target)-&gt; HARD_DELETED
 * AWAITING_PROOF -(the provider's own delete event is seen, bucket, prefix or container rule)-&gt; RULE_IN_FORCE
 * SOFT_DELETED, HARD_DELETE_PENDING_APPROVAL, HARD_DELETE_REFUSED, DRY_RUN_COMPLETE -(restore)-&gt; RESTORED
 * RULE_IN_FORCE, AWAITING_PROOF of a container rule -(restore: the rule is removed)-&gt; RESTORED
 * RULE_IN_FORCE, AWAITING_PROOF of a container rule -(restore: DLAP's rule is not found)-&gt; RESTORE_FAILED
 * RULE_IN_FORCE -(the rule's maximum age passed: the executor removes the rule)-&gt; RULE_ENDED
 * RULE_IN_FORCE -(maximum age passed, DLAP's rule is not found)-&gt; RESTORE_FAILED
 * AWAITING_PROOF of a container rule -(maximum age passed, never proven: the rule is removed)-&gt; RULE_ENDED_UNPROVEN
 * AWAITING_PROOF of a container rule -(maximum age passed, DLAP's rule is not found)-&gt; RESTORE_FAILED
 * </pre>
 *
 * <p>{@code RULE_ENDED} and {@code RESTORE_FAILED} (Flyway V8, RET-06) and {@code RULE_ENDED_UNPROVEN}
 * (Flyway V9, RET-08) are end states.
 *
 * <p>{@code RULE_IN_FORCE} (Flyway V7, RET-04) is not a deletion of the bucket or container: it says
 * the provider's lifecycle rule is proven to fire (the first child delete was seen in the provider's
 * log), so objects expire on the provider's schedule until the rule is removed. {@code HARD_DELETED}
 * is kept for targets that are gone: an object, a blob, a table.
 *
 * <p>The id is assigned by the service (a name-based UUID of the fire or of the approval that
 * queued the row), so a redelivered fire maps to the same row and the same audit correlation id.
 * {@code assetUri} is the canonical asset URI (ADR-0047) on every row written since RET-02.
 */
@Entity
@Table(
    name = "soft_delete_records",
    indexes = {
        @Index(name = "idx_sdr_asset_id", columnList = "asset_id"),
        @Index(name = "idx_sdr_status", columnList = "status"),
        @Index(name = "idx_sdr_scheduled_hard_delete_at", columnList = "scheduled_hard_delete_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SoftDeleteRecordEntity {

    /** Inside the window; restorable. */
    public static final String SOFT_DELETED = "SOFT_DELETED";
    /** The window ended and a HARD_DELETE approval was requested; restorable. */
    public static final String HARD_DELETE_PENDING_APPROVAL = "HARD_DELETE_PENDING_APPROVAL";
    /** A rule refused the hard delete; nothing was touched; restorable. */
    public static final String HARD_DELETE_REFUSED = "HARD_DELETE_REFUSED";
    /** Every rule passed but dry-run-cloud-delete is on; nothing was touched; restorable. */
    public static final String DRY_RUN_COMPLETE = "DRY_RUN_COMPLETE";
    /** The native rule or the engine delete was applied; waiting for the provider's own event. */
    public static final String AWAITING_PROOF = "AWAITING_PROOF";
    /** The provider's own log event proved the deletion. */
    public static final String HARD_DELETED = "HARD_DELETED";
    /** The provider call failed. */
    public static final String HARD_DELETE_FAILED = "HARD_DELETE_FAILED";
    /**
     * A lifecycle rule on a whole bucket, prefix or container is proven to fire: the provider's log
     * showed a child delete by the rule. The container stays and other objects may not have expired
     * yet. Restorable: the restore removes the rule; objects it already expired are gone.
     */
    public static final String RULE_IN_FORCE = "RULE_IN_FORCE";
    /** An operator restored the asset. */
    public static final String RESTORED = "RESTORED";
    /**
     * The rule of a {@link #RULE_IN_FORCE} record reached its maximum age
     * ({@code dlap.autodelete.rule-in-force.max-age}) and the executor removed it (RET-06, Q-677). An end
     * state: objects the rule expired are gone; nothing deletes any more.
     */
    public static final String RULE_ENDED = "RULE_ENDED";
    /**
     * A bucket, prefix or container rule that DLAP applied and that no provider event ever proved
     * ({@link #AWAITING_PROOF}) reached its maximum age, and the executor removed it (RET-08, Q-695, decision
     * D38). An end state: whatever the rule expired is gone, nothing deletes any more, and nothing was proven.
     */
    public static final String RULE_ENDED_UNPROVEN = "RULE_ENDED_UNPROVEN";
    /**
     * DLAP tried to remove its own rule (a restore, or the end of the rule's maximum age) and found no rule
     * on the platform that matches the stored one (RET-06, Q-682). An end state that needs a person: the rule
     * was removed by someone else, or it is still there in another form and keeps deleting.
     */
    public static final String RESTORE_FAILED = "RESTORE_FAILED";

    /**
     * States in which nothing was touched in the cloud, so a restore is still possible, plus
     * {@link #RULE_IN_FORCE}, whose restore removes the rule (objects already expired are gone).
     */
    public static final Set<String> RESTORABLE = Set.of(SOFT_DELETED, HARD_DELETE_PENDING_APPROVAL,
            HARD_DELETE_REFUSED, DRY_RUN_COMPLETE, RULE_IN_FORCE);

    /** States that hold the asset: a second delete of the same asset joins the existing row. */
    public static final Set<String> ACTIVE = Set.of(SOFT_DELETED, HARD_DELETE_PENDING_APPROVAL, AWAITING_PROOF,
            RULE_IN_FORCE);

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "asset_id", columnDefinition = "UUID", nullable = false)
    private UUID assetId;

    @Column(name = "policy_id", length = 128)
    private String policyId;

    @Column(name = "policy_fire_id", columnDefinition = "UUID")
    private UUID policyFireId;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "scheduled_hard_delete_at", nullable = false)
    private Instant scheduledHardDeleteAt;

    /** One of the status constants of this class; enforced by the CHECK of Flyway V5. */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "audit_note", columnDefinition = "TEXT")
    private String auditNote;

    @Column(name = "cloud_provider", length = 20, nullable = false)
    private String cloudProvider;

    /** The canonical asset URI (ADR-0047, AssetIdNormalizer). */
    @Column(name = "asset_uri", length = 2048)
    private String assetUri;

    @Column(name = "service_type", length = 64)
    private String serviceType;

    /** {@code POLICY} for a policy fire, {@code TASK} for the task API. */
    @Column(name = "origin", length = 16)
    private String origin;

    @Column(name = "task_id", length = 255)
    private String taskId;

    /** Who asked: {@code policy:<policyDbId>}, or the JWT subject of the task submitter. */
    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    /** The window the policy or the task asked for, before the floor. */
    @Column(name = "requested_window_days")
    private Integer requestedWindowDays;

    /** The window applied: at least the configured floor. */
    @Column(name = "window_days")
    private Integer windowDays;

    /** The approval that released the soft delete. */
    @Column(name = "approval_id", columnDefinition = "UUID")
    private UUID approvalId;

    @Column(name = "approval_class", length = 16)
    private String approvalClass;

    /** JWT subjects of the approvers, comma separated, in decision order. */
    @Column(name = "approvers", columnDefinition = "TEXT")
    private String approvers;

    /** Read coverage the catalog reported when the row was written. */
    @Column(name = "read_coverage", length = 16)
    private String readCoverage;

    /** The HARD_DELETE approval the sweeper requested when the window ended. */
    @Column(name = "hard_delete_approval_id", columnDefinition = "UUID")
    private UUID hardDeleteApprovalId;

    @Column(name = "hard_delete_requested_at")
    private Instant hardDeleteRequestedAt;

    /** When the sweeper looks at the row next. */
    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    /**
     * When the rule-end job may try to remove the rule again after a refused or rolled-back attempt (Flyway V9,
     * RET-08); null when nothing waits. Kept apart from {@link #nextCheckAt}, which the sweeper owns.
     */
    @Column(name = "rule_end_retry_at")
    private Instant ruleEndRetryAt;

    @Column(name = "restored_at")
    private Instant restoredAt;

    /** Legacy column; the JWT subject of the restorer is in {@link #restoredBy}. */
    @Column(name = "restored_by_user_id", columnDefinition = "UUID")
    private UUID restoredByUserId;

    @Column(name = "restored_by", length = 255)
    private String restoredBy;

    /**
     * The asset's size in bytes as the catalog last said it (null when unknown). The storage-reclaimed
     * KPI sums it over the records the provider's log proved deleted ({@link #HARD_DELETED}).
     */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** The provider's event time of the proof (business time), not the time DLAP saw it. */
    @Column(name = "hard_deleted_at")
    private Instant hardDeletedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
