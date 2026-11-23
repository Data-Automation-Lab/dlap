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

package com.dlap.autolabel.service.impl;

import com.dlap.autolabel.client.dto.AssetLabelsPostFeignRequest;
import com.dlap.autolabel.client.dto.CatalogAssetPageDTO;
import com.dlap.autolabel.client.dto.CatalogAssetSummaryDTO;
import com.dlap.autolabel.client.dto.LabelAssignmentFeignDTO;
import com.dlap.autolabel.client.feign.AssetCatalogClient;
import com.dlap.autolabel.entity.LabelingJobEntity;
import com.dlap.autolabel.repository.LabelingJobRepository;
import com.dlap.autolabel.service.IAutoLabelOrchestrator;
import com.dlap.autolabel.service.ILabelingRunService;
import com.dlap.autolabel.service.PolicyFireId;
import com.dlap.common.constants.DlapConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link ILabelingRunService} implementation.
 *
 * <p>Both entry points persist a durable {@link LabelingJobEntity} so the
 * dashboard KPIs (which read {@code LabelingJobRepository}) reflect real
 * activity - never fabricated numbers. An empty catalog yields an honest
 * {@code COMPLETED} job with zero counts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LabelingRunServiceImpl implements ILabelingRunService {

    private final AssetCatalogClient catalogClient;
    private final IAutoLabelOrchestrator orchestrator;
    private final LabelingJobRepository labelingJobRepository;

    /** Page size when enumerating the catalog for a full labeling pass. */
    @Value("${dlap.autolabel.batch-page-size:500}")
    private int batchPageSize;

    /** Hard cap on pages walked, so a runaway catalog cannot spin forever. */
    @Value("${dlap.autolabel.batch-max-pages:100}")
    private int batchMaxPages;

    @Override
    @Transactional
    public String runManualLabeling(String requestId, String triggeredBy) {
        String jobId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.now();
        log.info("Starting manual labeling run job {} (requestId={}, triggeredBy={})",
                jobId, requestId, triggeredBy);

        LabelingJobEntity job = LabelingJobEntity.builder()
                .jobId(jobId)
                .jobName("Manual labeling run" + (requestId != null ? " " + requestId : ""))
                .status(LabelingJobEntity.JobStatus.PREPARING_DATA)
                .submittedAt(startedAt)
                .startedAt(startedAt)
                .lastUpdatedAt(startedAt)
                .totalAssetsToProcess(0)
                .assetsProcessed(0)
                .assetsSuccessfullyLabeled(0)
                .assetsFailed(0)
                .progressPercentage(0.0)
                .errorMessages(new ArrayList<>())
                .labelSummary(new LinkedHashMap<>())
                .triggeredByUserId(triggeredBy)
                .build();
        labelingJobRepository.save(job);

        List<UUID> assetIds = enumerateCatalogAssetIds(job);
        job.setTotalAssetsToProcess(assetIds.size());
        job.setStatus(LabelingJobEntity.JobStatus.CALLING_LLM);

        int processed = 0;
        int succeeded = 0;
        int failed = 0;
        Map<String, Integer> labelSummary = new LinkedHashMap<>();

        for (UUID assetId : assetIds) {
            try {
                List<String> applied = orchestrator.labelAssetDetailed(assetId);
                if (!applied.isEmpty()) {
                    succeeded++;
                    for (String label : applied) {
                        labelSummary.merge(label, 1, Integer::sum);
                    }
                }
            } catch (Exception e) {
                failed++;
                job.getErrorMessages().add("Asset " + assetId + ": " + e.getMessage());
                log.warn("Manual labeling run {} failed for asset {}: {}", jobId, assetId, e.getMessage());
            }
            processed++;
        }

        LabelingJobEntity.JobStatus finalStatus =
                failed == 0 ? LabelingJobEntity.JobStatus.COMPLETED
                        : (succeeded > 0 ? LabelingJobEntity.JobStatus.PARTIALLY_COMPLETED
                        : LabelingJobEntity.JobStatus.FAILED);

        LocalDateTime completedAt = LocalDateTime.now();
        job.setAssetsProcessed(processed);
        job.setAssetsSuccessfullyLabeled(succeeded);
        job.setAssetsFailed(failed);
        job.setLabelSummary(labelSummary);
        job.setStatus(finalStatus);
        job.setProgressPercentage(1.0);
        job.setCurrentStageDescription(String.format(
                "Processed %d assets, %d labeled, %d failed.", processed, succeeded, failed));
        job.setCompletedAt(completedAt);
        job.setLastUpdatedAt(completedAt);
        labelingJobRepository.save(job);

        log.info("Manual labeling run {} finished: status={}, processed={}, labeled={}, failed={}",
                jobId, finalStatus, processed, succeeded, failed);
        return jobId;
    }

    @Override
    @Transactional
    public String applyPolicyLabel(UUID assetId, String labelName, String policyId, String policyName,
            String receivedFireId) {
        // The same normalisation as the consumer, so a caller other than the consumer cannot key a job on a
        // blank value (Q-890).
        String fireId = PolicyFireId.normalize(receivedFireId);
        // Delivery is at least once and a redelivered source event republishes the same fire (log-event.md 5.5):
        // a fire already applied adds no second job, so the dashboard counts it once (Q-856).
        if (fireId != null) {
            Optional<LabelingJobEntity> applied = labelingJobRepository.findByFireId(fireId);
            if (applied.isPresent()) {
                log.info("Policy fire {} was already applied as job {}; not applying label '{}' to asset {} again",
                        fireId, applied.get().getJobId(), labelName, assetId);
                return applied.get().getJobId();
            }
        }
        String jobId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String jobName = "Policy label" + (policyName != null ? " [" + policyName + "]" : "");
        log.info("Applying policy label '{}' to asset {} (policyId={}) as job {}",
                labelName, assetId, policyId, jobId);

        LabelingJobEntity job = LabelingJobEntity.builder()
                .jobId(jobId)
                .jobName(jobName)
                .status(LabelingJobEntity.JobStatus.APPLYING_LABELS)
                .submittedAt(now)
                .startedAt(now)
                .lastUpdatedAt(now)
                .totalAssetsToProcess(1)
                .assetsProcessed(0)
                .assetsSuccessfullyLabeled(0)
                .assetsFailed(0)
                .progressPercentage(0.0)
                .errorMessages(new ArrayList<>())
                .labelSummary(new LinkedHashMap<>())
                .triggeredByUserId(policyId != null ? "policy:" + policyId : "policy-engine")
                .fireId(fireId)
                .build();
        if (fireId != null) {
            // Claim the fire first: a concurrent copy waits on the unique index of fire_id until this
            // transaction ends, then fails and is retried as a duplicate. A failure below rolls the claim back.
            labelingJobRepository.saveAndFlush(job);
        }

        boolean applied = false;
        try {
            catalogClient.addLabelsToAsset(assetId, AssetLabelsPostFeignRequest.builder()
                    .labels(List.of(LabelAssignmentFeignDTO.builder()
                            .labelName(labelName)
                            .source(DlapConstants.AssignmentSource.POLICY_ENGINE)
                            .build()))
                    .build());
            applied = true;
        } catch (Exception e) {
            job.getErrorMessages().add("Failed to apply label '" + labelName + "' to asset "
                    + assetId + ": " + e.getMessage());
            log.warn("Policy label apply failed for asset {} label '{}': {}",
                    assetId, labelName, e.getMessage());
        }

        LocalDateTime completedAt = LocalDateTime.now();
        job.setAssetsProcessed(1);
        job.setAssetsSuccessfullyLabeled(applied ? 1 : 0);
        job.setAssetsFailed(applied ? 0 : 1);
        job.setProgressPercentage(1.0);
        job.setStatus(applied ? LabelingJobEntity.JobStatus.COMPLETED : LabelingJobEntity.JobStatus.FAILED);
        job.setCurrentStageDescription(applied
                ? "Applied policy label '" + labelName + "'."
                : "Failed to apply policy label '" + labelName + "'.");
        if (applied) {
            job.getLabelSummary().put(labelName, 1);
        }
        job.setCompletedAt(completedAt);
        job.setLastUpdatedAt(completedAt);
        labelingJobRepository.save(job);

        if (!applied) {
            // Surface to the caller so the Kafka consumer can NACK -> retry/DLQ.
            throw new IllegalStateException(
                    "Could not apply policy label '" + labelName + "' to asset " + assetId);
        }
        return jobId;
    }

    /**
     * Walk the catalog {@code /assets} endpoint page by page and collect every
     * asset id. Stops at {@link #batchMaxPages} to bound the work. Any page
     * fetch failure is recorded on the job and terminates enumeration with
     * whatever was gathered so far (honest partial result, never fabricated).
     */
    private List<UUID> enumerateCatalogAssetIds(LabelingJobEntity job) {
        List<UUID> ids = new ArrayList<>();
        for (int page = 0; page < batchMaxPages; page++) {
            CatalogAssetPageDTO pageDto;
            try {
                pageDto = catalogClient.getAllAssets(page, batchPageSize);
            } catch (Exception e) {
                job.getErrorMessages().add("Failed to fetch catalog assets page " + page + ": " + e.getMessage());
                log.warn("Manual labeling run {} could not fetch catalog page {}: {}",
                        job.getJobId(), page, e.getMessage());
                break;
            }
            List<CatalogAssetSummaryDTO> content = pageDto != null ? pageDto.getContent() : null;
            if (content == null || content.isEmpty()) {
                break;
            }
            for (CatalogAssetSummaryDTO asset : content) {
                if (asset.getAssetId() != null) {
                    ids.add(asset.getAssetId());
                }
            }
            if (content.size() < batchPageSize) {
                break; // last page
            }
        }
        return ids;
    }
}
