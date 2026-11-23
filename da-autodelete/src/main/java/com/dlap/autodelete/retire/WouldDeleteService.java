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

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.dto.WouldDeleteRowDTO;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.common.assetid.CanonicalAssetId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The would-delete list: every asset the executor holds for deletion and has not deleted, soonest
 * first, with what it would do when the window ends (RET-02). It calls no remote service, so it
 * always answers; the approval, legal hold and coverage checks run again at execution time.
 *
 * <p>Rows: {@code SOFT_DELETED} (window running), {@code HARD_DELETE_PENDING_APPROVAL} (waiting for
 * two approvers), {@code DRY_RUN_COMPLETE} (every rule passed; dry run kept the data) and
 * {@code AWAITING_PROOF} (applied, waiting for the provider's own event) and {@code RULE_IN_FORCE}
 * (a bucket, prefix or container rule proven to fire; the target stays and other objects expire on
 * the provider's schedule until the rule is removed by a restore). Each row carries the plan:
 * the native rule document DLAP would apply (generated now, as of the end of the window) or the
 * engine delete, or {@code none} when nothing can delete the asset.
 */
@Service
public class WouldDeleteService {

    /** States the list shows. */
    public static final List<String> STATES = List.of(SoftDeleteRecordEntity.SOFT_DELETED,
            SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, SoftDeleteRecordEntity.DRY_RUN_COMPLETE,
            SoftDeleteRecordEntity.AWAITING_PROOF, SoftDeleteRecordEntity.RULE_IN_FORCE);

    private final SoftDeleteRecordRepository records;
    private final RetireDispositionRepository dispositions;
    private final DispositionPlanner planner;
    private final RetireProperties properties;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param records      the soft-delete rows
     * @param dispositions the dispositions
     * @param planner      the planner
     * @param properties   the settings
     * @param clock        the clock
     */
    public WouldDeleteService(SoftDeleteRecordRepository records, RetireDispositionRepository dispositions,
            DispositionPlanner planner, RetireProperties properties, Clock clock) {
        this.records = records;
        this.dispositions = dispositions;
        this.planner = planner;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * One page of the list.
     *
     * @param page the page, from 0
     * @param size the size, 1 to 200
     * @return the rows
     */
    @Transactional(readOnly = true)
    public Page<WouldDeleteRowDTO> list(int page, int size) {
        Instant now = clock.instant();
        Page<SoftDeleteRecordEntity> rows = records.findByStatusInOrderByScheduledHardDeleteAtAsc(STATES,
                PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))));
        return rows.map(r -> toRow(r, now));
    }

    private WouldDeleteRowDTO toRow(SoftDeleteRecordEntity r, Instant now) {
        Optional<RetireDispositionEntity> d = dispositions.findBySoftDeleteRecordId(r.getId());
        String mode;
        String platform;
        String ruleId;
        String document;
        Instant effective;
        if (d.isPresent()) {
            mode = d.get().getMode();
            platform = d.get().getPlatform();
            ruleId = d.get().getRuleId();
            document = d.get().getRuleDocument();
            effective = d.get().getEffectiveAt();
        } else {
            Instant at = r.getScheduledHardDeleteAt() != null && r.getScheduledHardDeleteAt().isAfter(now)
                    ? r.getScheduledHardDeleteAt() : now;
            Optional<CanonicalAssetId> canonical = CanonicalTarget.parse(r.getAssetUri());
            Optional<DispositionPlanner.Plan> plan = canonical.flatMap(c ->
                    planner.plan(c, r.getCloudProvider(), r.getId(), at));
            mode = plan.map(DispositionPlanner.Plan::mode).orElse("NONE");
            platform = plan.map(DispositionPlanner.Plan::platform).orElse(null);
            ruleId = plan.map(DispositionPlanner.Plan::ruleId).orElse(null);
            document = plan.map(p -> p.nativeRule() ? p.ruleDocument() : "engine delete of " + p.engineLocation())
                    .orElse(null);
            effective = plan.map(DispositionPlanner.Plan::effectiveAt).orElse(null);
        }
        return new WouldDeleteRowDTO(r.getId(), r.getAssetId(), r.getAssetUri(), r.getCloudProvider(),
                r.getServiceType(), r.getStatus(), r.getOrigin(), r.getPolicyId(), r.getQueuedAt(),
                r.getScheduledHardDeleteAt(), r.getWindowDays(), r.getApprovalId(), r.getHardDeleteApprovalId(),
                r.getReadCoverage(), mode, platform, ruleId, document, effective,
                d.map(RetireDispositionEntity::getStatus).orElse(null), properties.dryRunCloudDelete(),
                r.getLastError());
    }
}
