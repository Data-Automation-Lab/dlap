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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One hard delete the executor carried out, or would have carried out under dry run
 * (decision D20, Flyway V6).
 *
 * <p>{@code mode} is {@code NATIVE_RULE} when the platform's own lifecycle primitive does the
 * deletion ({@code ruleDocument} holds the rule in the provider's API shape) and {@code DIRECT}
 * when the engine deletes, for kinds with no exact native rule. A disposition is
 * {@code EXECUTED} only when the provider's own log event proves the deletion; the database
 * refuses an {@code EXECUTED} row without {@code proofEventId} and {@code proofEventTime}.
 */
@Entity
@Table(name = "retire_dispositions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetireDispositionEntity {

    /** Planned under dry run; nothing was applied. */
    public static final String DRY_RUN = "DRY_RUN";
    /** Applied; waiting for the provider's own event. */
    public static final String AWAITING_PROOF = "AWAITING_PROOF";
    /** Applied, and the expected delay passed without a proof; still listening. */
    public static final String PROOF_OVERDUE = "PROOF_OVERDUE";
    /** The provider's own event proved the deletion. */
    public static final String EXECUTED = "EXECUTED";
    /** Applying failed. */
    public static final String FAILED = "FAILED";

    /** The platform's own lifecycle primitive. */
    public static final String MODE_NATIVE_RULE = "NATIVE_RULE";
    /** The engine's own delete call. */
    public static final String MODE_DIRECT = "DIRECT";

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "soft_delete_record_id", columnDefinition = "UUID", nullable = false, unique = true)
    private UUID softDeleteRecordId;

    @Column(name = "asset_id", columnDefinition = "UUID", nullable = false)
    private UUID assetId;

    /** Canonical URI of what is deleted; a proof event on it or below it counts. */
    @Column(name = "target_uri", length = 2048, nullable = false)
    private String targetUri;

    @Column(name = "cloud_provider", length = 20, nullable = false)
    private String cloudProvider;

    @Column(name = "service_type", length = 64)
    private String serviceType;

    @Column(name = "mode", length = 16, nullable = false)
    private String mode;

    /** {@code S3_LIFECYCLE}, {@code GCS_LIFECYCLE}, {@code AZURE_BLOB_LIFECYCLE}, {@code BIGQUERY_TABLE_EXPIRATION}, or {@code ENGINE_<provider>}. */
    @Column(name = "platform", length = 40, nullable = false)
    private String platform;

    @Column(name = "rule_id", length = 128)
    private String ruleId;

    @Column(name = "rule_document", columnDefinition = "TEXT")
    private String ruleDocument;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    /** The HARD_DELETE approval that released this disposition. */
    @Column(name = "approval_id", columnDefinition = "UUID", nullable = false)
    private UUID approvalId;

    @Column(name = "approvers", columnDefinition = "TEXT")
    private String approvers;

    @Column(name = "planned_at", nullable = false)
    private Instant plannedAt;

    /** When the native rule takes effect (the S3 expiration date, the BigQuery expiration time). */
    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    /** After this instant without a proof, the disposition is PROOF_OVERDUE. */
    @Column(name = "proof_due_at")
    private Instant proofDueAt;

    @Column(name = "proof_event_id", length = 512)
    private String proofEventId;

    /** The provider's event time of the proof (business time). */
    @Column(name = "proof_event_time")
    private Instant proofEventTime;

    /** {@code source_format} or {@code source_service} of the proof event. */
    @Column(name = "proof_source", length = 128)
    private String proofSource;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * True when the provider call succeeded but the {@code retire.delete.apply} SUCCEEDED audit append
     * did not land (Q-633). The sweeper retries the append; it never applies the call a second time.
     */
    @Column(name = "success_audit_pending", nullable = false)
    private boolean successAuditPending;

    /** When a restore removed the native rule (a rule in force that was restored), else null. */
    @Column(name = "rule_removed_at")
    private Instant ruleRemovedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
