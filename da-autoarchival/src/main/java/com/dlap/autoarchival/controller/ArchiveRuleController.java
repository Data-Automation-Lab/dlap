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

package com.dlap.autoarchival.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;
import com.dlap.autoarchival.executor.ArchiveRuleResetService;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RulePlan;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.autoarchival.service.AssetLocation;
import com.dlap.autoarchival.service.AssetLocationResolver;
import com.dlap.autoarchival.service.IArchivalTaskService;
import com.dlap.common.audit.AuditActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The native archive rules (RET-03): a read-only dry run that shows what a rule would do and which
 * objects it would move, and the rule records with the proof of what happened.
 *
 * <p>The dry run takes a catalog asset id, not a bucket name, so a caller cannot make the service
 * list a bucket the catalog does not know. It reads the provider (rule count, a bounded object
 * listing) and writes nothing.
 */
@RestController
@RequestMapping("/api/v1/archival/rules")
public class ArchiveRuleController {

    private static final Set<String> VALID_STATUS = Set.of("PLANNED", "EXECUTING", "APPLIED", "RENDERED", "EXECUTED", "FAILED",
            "REFUSED", "REMOVED");

    private final RulePlanner planner;
    private final AssetLocationResolver resolver;
    private final ArchiveRuleRepository rules;
    private final IArchivalTaskService tasks;
    private final ArchiveRuleResetService resets;

    /**
     * Creates the controller.
     *
     * @param planner  plans rules
     * @param resolver resolves an asset id to its storage location
     * @param rules    the rule rows
     * @param tasks    the task service, to resume a waiting archive
     * @param resets   closes a row a dead worker left in {@code EXECUTING}
     */
    public ArchiveRuleController(RulePlanner planner, AssetLocationResolver resolver, ArchiveRuleRepository rules,
            IArchivalTaskService tasks, ArchiveRuleResetService resets) {
        this.planner = planner;
        this.resolver = resolver;
        this.rules = rules;
        this.tasks = tasks;
        this.resets = resets;
    }

    /**
     * Body of the dry run.
     *
     * @param assetId     the catalog asset id
     * @param tier        {@code COOL}, {@code COLD}, {@code ARCHIVE} or a provider class; default from the configuration
     * @param prefix      a key prefix below a bucket or folder asset
     * @param afterDays   the age in days; default the class minimum
     * @param listingKeys how many objects to list, 1 to 1000; default 50
     */
    public record PlanRequest(
            @NotBlank @Size(max = 64) String assetId,
            @Size(max = 32) String tier,
            @Size(max = 1024) String prefix,
            @Min(0) @Max(36500) Integer afterDays,
            @Min(1) @Max(1000) Integer listingKeys) {
    }

    /**
     * One rule record as the API shows it.
     *
     * @param ruleId           the rule id
     * @param approvalId       the approval it runs under
     * @param taskId           the archival task
     * @param assetId          the catalog asset id
     * @param assetUri         the asset's storage URI
     * @param provider         the provider
     * @param mode             {@code NATIVE_RULE} or {@code DIRECT_OBJECT}
     * @param bucket           the bucket or container
     * @param prefix           the prefix
     * @param nativeClass      the provider storage class
     * @param afterDays        the age in days
     * @param status           the status; {@code EXECUTING} means one worker has claimed the row and is running it
     * @param alreadyPresent   whether the provider already held the identical rule before DLAP applied it; a
     *                         restore leaves such a rule in place
     * @param mock             whether the provider call was a mock
     * @param appliedAt        when the apply began
     * @param executedAt       the provider's time of the proven transition
     * @param evidenceEventId  the provider event that proved it
     * @param evidenceOperation its operation
     * @param evidenceKind     {@code PROVIDER_LOG} or {@code STATE_OBSERVATION}
     * @param lastError        the last error, if any
     * @param createdAt        when the row was made
     */
    public record RuleView(String ruleId, String approvalId, String taskId, String assetId, String assetUri,
            String provider, String mode, String bucket, String prefix, String nativeClass, int afterDays,
            String status, boolean alreadyPresent, boolean mock, Instant appliedAt, Instant executedAt, String evidenceEventId,
            String evidenceOperation, String evidenceKind, String lastError, Instant createdAt) {

        static RuleView of(ArchiveRuleEntity r) {
            return new RuleView(r.getRuleId(), r.getApprovalId(), r.getTaskId(), r.getAssetId(), r.getAssetUri(),
                    r.getProvider(), r.getMode(), r.getScopeBucket(), r.getScopePrefix(), r.getNativeClass(),
                    r.getAfterDays(), r.getStatus(), r.isAlreadyPresent(), r.isMock(), r.getAppliedAt(), r.getExecutedAt(),
                    r.getEvidenceEventId(), r.getEvidenceOperation(), r.getEvidenceKind(), r.getLastError(),
                    r.getCreatedAt());
        }
    }

    /**
     * Dry run: plans the rule for an asset and lists the objects it would move. Nothing is written.
     *
     * @param request the asset and options
     * @return the plan, or the reason it cannot be planned
     */
    @PostMapping("/plan")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER')")
    public ResponseEntity<?> plan(@Valid @RequestBody PlanRequest request) {
        Optional<AssetLocation> location = resolver.resolve(request.assetId());
        if (location.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", "UNRESOLVED_LOCATION",
                    "message", "the catalog has no usable storage location for this asset"));
        }
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        if (request.tier() != null) {
            parameters.put("tier", request.tier());
        }
        if (request.prefix() != null) {
            parameters.put("prefix", request.prefix());
        }
        if (request.afterDays() != null) {
            parameters.put("afterDays", request.afterDays());
        }
        try {
            RulePlan plan = planner.plan(location.get().cloudProvider(), location.get().uri(), parameters, null, null,
                    request.listingKeys());
            return ResponseEntity.ok(plan);
        } catch (RuleException e) {
            HttpStatus status = e.reason() == RuleException.Reason.PROVIDER_ERROR ? HttpStatus.BAD_GATEWAY
                    : e.reason() == RuleException.Reason.RULE_LIMIT || e.reason() == RuleException.Reason.DUPLICATE_SCOPE
                    ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(Map.of("reason", e.reason().name(), "message", e.getMessage()));
        }
    }

    /**
     * Lists rule records, newest first.
     *
     * @param status an optional status filter
     * @param page   the page, from 0
     * @param size   the page size, 1 to 200
     * @return the page
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', "
            + "'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<?> list(@RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Set<String> wanted = status == null || status.isBlank() ? VALID_STATUS : Set.of(status.toUpperCase());
        if (!VALID_STATUS.containsAll(wanted)) {
            return ResponseEntity.badRequest().body(Map.of("reason", "INVALID_STATUS", "message",
                    "status must be one of " + new java.util.TreeSet<>(VALID_STATUS)));
        }
        Page<ArchiveRuleEntity> result = rules.findByStatusInOrderByCreatedAtDesc(wanted,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))));
        List<RuleView> content = result.getContent().stream().map(RuleView::of).toList();
        return ResponseEntity.ok(Map.of("content", content, "page", result.getNumber(), "size", result.getSize(),
                "totalElements", result.getTotalElements()));
    }

    /**
     * Reads one rule record.
     *
     * @param ruleId the rule id
     * @return the record, or 404
     */
    @GetMapping("/{ruleId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', "
            + "'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<RuleView> get(@PathVariable String ruleId) {
        return rules.findById(ruleId).map(r -> ResponseEntity.ok(RuleView.of(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Runs an archive that is planned and approved but did not start (for example the policy engine
     * could not be reached). The approval is checked again; an archive without an executable approval
     * does not run.
     *
     * @param ruleId the rule id
     * @return 202 when queued, 404 when unknown, 409 when the rule is not waiting
     */
    @PostMapping("/{ruleId}/retry")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, String>> retry(@PathVariable String ruleId) {
        Optional<ArchiveRuleEntity> row = rules.findById(ruleId);
        if (row.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!ArchiveRuleEntity.STATUS_PLANNED.equals(row.get().getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", "NOT_WAITING", "message",
                    "the rule is " + row.get().getStatus() + ", not PLANNED"));
        }
        tasks.executeApprovalAsync(row.get().getApprovalId());
        return ResponseEntity.accepted().body(Map.of("status", "QUEUED"));
    }

    /**
     * Closes a row that a dead worker left in {@code EXECUTING} (Q-669). Works only on an {@code EXECUTING} row. It
     * reads the provider first and never applies anything: a present rule makes the row {@code APPLIED}, an absent
     * one makes it {@code FAILED} with reason {@code RESET_RULE_ABSENT}. The intent and the outcome go to the
     * audit log.
     *
     * @param ruleId         the rule id
     * @param authentication the administrator
     * @return 200 with the new status; 404 unknown; 409 when the row is not {@code EXECUTING}, cannot be read or
     *         changed meanwhile; 502 when the provider read failed; 503 when the audit log is unavailable
     */
    @PostMapping("/{ruleId}/reset")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable String ruleId, Authentication authentication) {
        String subject = authentication == null ? null : authentication.getName();
        AuditActor actor = subject == null || subject.isBlank() ? AuditActor.service(ArchiveAudit.ACTOR_SERVICE)
                : AuditActor.user(subject);
        ArchiveRuleResetService.Result result = resets.reset(ruleId, actor);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ruleId", ruleId);
        body.put("outcome", result.outcome().name());
        if (result.row() != null) {
            body.put("status", result.row().getStatus());
        }
        body.put("message", result.message());
        HttpStatus status = switch (result.outcome()) {
            case RESET_TO_APPLIED, RESET_TO_FAILED -> HttpStatus.OK;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NOT_EXECUTING, UNREADABLE, ROW_CHANGED -> HttpStatus.CONFLICT;
            case PROVIDER_ERROR -> HttpStatus.BAD_GATEWAY;
            case AUDIT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(body);
    }
}
