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

package com.dlap.policyengine.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dlap.policyengine.common.ResourceNotFoundException;
import com.dlap.policyengine.dsl.AssetSnapshots;
import com.dlap.policyengine.dsl.MatchResult;
import com.dlap.policyengine.dsl.PolicyActionRequests;
import com.dlap.policyengine.dsl.PolicyDsl;
import com.dlap.policyengine.dsl.PolicyDslEvaluator;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dto.PolicyEvaluationOutputDTO;
import com.dlap.policyengine.dto.PolicyEvaluationRequestDTO;
import com.dlap.policyengine.dto.PolicyEvaluationSummaryDTO;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyEvaluation;
import com.dlap.policyengine.model.PolicyEvaluationStatus;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyEvaluationRepository;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.criteria.Predicate;

/**
 * On-demand (REST) evaluation of one policy against one asset.
 *
 * <p>SEC-04 (ADR-0057): a policy is evaluated only through its dlap/v1 DSL document, with
 * {@link PolicyDslEvaluator}, the same evaluator the Kafka consumer and the AuthZEN PDP use.
 * The legacy MVEL rules are retired and nothing here compiles or runs caller-supplied code.
 * A policy without a DSL document is recorded as {@code NOT_APPLICABLE}; a document that no
 * longer parses is recorded as {@code ERROR}. Neither fires an action.
 *
 * <p>POL-01: the match is three-valued. Only {@code TRUE} is a {@code PASS}; {@code UNKNOWN} is
 * recorded as {@code FAIL} with {@code outcome: UNKNOWN} and the unknown clauses in the details.
 * The catalog snapshot carries the ADR-0046 lifecycle fields and read coverage, and a
 * destructive action (in the document or in the legacy actions map) requires full read coverage
 * before a recorded last read proves staleness. Every published action carries the asset
 * identity ({@link PolicyActionRequests#forManual}).
 *
 * <p>APPR-01 (Q-569): every fire goes through {@link ApprovalGate}, as on the event and schedule
 * paths. An archive, a delete or a {@code requireApproval} action that is not a dry run waits as an
 * approval request for the approvers of docs/specs/approvals.md and is published only when the
 * request is released; the caller of the endpoint, an ADMIN, does not skip dual control and, being
 * the person who triggered the evaluation, cannot approve the request it creates (the JWT
 * {@code sub} is passed to the gate as an excluded approver). A dry run and any other action still
 * publish directly.
 */
@Service
@Transactional
public class PolicyEvaluationService implements IPolicyEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationService.class);

    /** Evaluation-details key naming the engine that produced the result. */
    static final String DETAIL_ENGINE = "engine";

    /** The only evaluation engine since SEC-04. */
    static final String ENGINE_DSL = "dlap/v1";

    private final PolicyRepository policyRepository;
    private final PolicyEvaluationRepository policyEvaluationRepository;
    private final PolicyMapper policyMapper;
    private final ObjectMapper objectMapper; // For converting the asset snapshot to plain JSON
    private final ApprovalGate approvalGate;
    private final ResilientCatalogAssetClient catalogAssetClient;
    private final PolicyDslParser dslParser;
    private final PolicyDslEvaluator dslEvaluator;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param policyRepository           policy store
     * @param policyEvaluationRepository evaluation record store
     * @param policyMapper               entity to DTO mapper
     * @param objectMapper               JSON mapper for the stored snapshot
     * @param approvalGate               publishes the actions of a pass, or holds them for approval
     * @param catalogAssetClient         resolves an asset by catalog id
     * @param dslParser                  parses the policy's DSL document
     * @param dslEvaluator               evaluates the DSL match block
     * @param clock                      business clock
     */
    @Autowired
    public PolicyEvaluationService(PolicyRepository policyRepository,
                                 PolicyEvaluationRepository policyEvaluationRepository,
                                 PolicyMapper policyMapper,
                                 ObjectMapper objectMapper,
                                 ApprovalGate approvalGate,
                                 ResilientCatalogAssetClient catalogAssetClient,
                                 PolicyDslParser dslParser,
                                 PolicyDslEvaluator dslEvaluator,
                                 Clock clock) {
        this.policyRepository = policyRepository;
        this.policyEvaluationRepository = policyEvaluationRepository;
        this.policyMapper = policyMapper;
        this.objectMapper = objectMapper;
        this.approvalGate = approvalGate;
        this.catalogAssetClient = catalogAssetClient;
        this.dslParser = dslParser;
        this.dslEvaluator = dslEvaluator;
        this.clock = clock;
    }

    @Override
    public PolicyEvaluationOutputDTO evaluatePolicyForAsset(UUID policyId, PolicyEvaluationRequestDTO evaluationRequest, UUID triggeredByUserId, String triggeredBySubject) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", "id", policyId.toString()));

        if (policy.getStatus() == PolicyStatus.DISABLED) {
            log.warn("Policy {} is disabled. Evaluation skipped for asset {}.", policy.getName(), evaluationRequest.getTargetAssetId());
            PolicyEvaluation evaluation = createEvaluationRecord(policy, evaluationRequest.getTargetAssetId(), PolicyEvaluationStatus.NOT_APPLICABLE, triggeredByUserId, Collections.singletonMap("reason", "Policy disabled"), null);
            return policyMapper.toPolicyEvaluationOutputDTO(evaluation, policy.getName());
        }

        // SEC-04: the DSL document is the only evaluated form of a policy.
        String document = policy.getPolicyDocument();
        if (!StringUtils.hasText(document)) {
            log.warn("Policy {} has no DSL document; evaluation not applicable for asset {}.",
                    policy.getName(), evaluationRequest.getTargetAssetId());
            Map<String, Object> details = new HashMap<>();
            details.put(DETAIL_ENGINE, ENGINE_DSL);
            details.put("reason", "Policy has no dlap/v1 DSL document (policyDocument); "
                    + "legacy MVEL rules were retired (ADR-0057)");
            PolicyEvaluation evaluation = createEvaluationRecord(policy, evaluationRequest.getTargetAssetId(), PolicyEvaluationStatus.NOT_APPLICABLE, triggeredByUserId, details, null);
            return policyMapper.toPolicyEvaluationOutputDTO(evaluation, policy.getName());
        }
        PolicyDsl dsl;
        try {
            dsl = dslParser.parse(document);
        } catch (PolicyDslParser.PolicyDslParseException e) {
            log.warn("Policy {} has an invalid DSL document: {}", policy.getName(), e.getMessage());
            Map<String, Object> details = new HashMap<>();
            details.put(DETAIL_ENGINE, ENGINE_DSL);
            details.put("reason", "Invalid policy DSL: " + e.getMessage());
            PolicyEvaluation evaluation = createEvaluationRecord(policy, evaluationRequest.getTargetAssetId(), PolicyEvaluationStatus.ERROR, triggeredByUserId, details, null);
            return policyMapper.toPolicyEvaluationOutputDTO(evaluation, policy.getName());
        }

        AssetDataResult assetDataResult = fetchAssetData(
                evaluationRequest.getTargetAssetId(),
                evaluationRequest.getAssetSnapshotFromEvent());
        if (assetDataResult.data() == null || assetDataResult.data().isEmpty()) {
            String reason = assetDataResult.skipReason() != null
                    ? assetDataResult.skipReason()
                    : "Asset data not found";
            log.warn("Policy evaluation skipped for assetId {}: {}", evaluationRequest.getTargetAssetId(), reason);
            Map<String, Object> details = new HashMap<>();
            details.put("status", "SKIPPED");
            details.put("reason", reason);
            PolicyEvaluation evaluation = createEvaluationRecord(policy, evaluationRequest.getTargetAssetId(), PolicyEvaluationStatus.NOT_APPLICABLE, triggeredByUserId, details, null);
            return policyMapper.toPolicyEvaluationOutputDTO(evaluation, policy.getName());
        }
        Map<String, Object> assetData = assetDataResult.data();
        if (evaluationRequest.getEvaluationContext() != null && !evaluationRequest.getEvaluationContext().isEmpty()) {
            // The DSL reads only the asset snapshot; caller context is not evaluated.
            log.debug("Ignoring evaluationContext keys {} for policy {}: the DSL evaluates the asset snapshot only",
                    evaluationRequest.getEvaluationContext().keySet(), policy.getName());
        }

        boolean fullCoverage = PolicyDslEvaluator.requiresFullReadCoverage(dsl) || hasDestructiveLegacyAction(policy);
        MatchResult result = dslEvaluator.evaluate(dsl, assetData,
                dslEvaluator.options(clock.instant(), fullCoverage, new java.util.HashSet<>()));
        boolean matched = result.isTrue();
        PolicyEvaluationStatus finalStatus = matched ? PolicyEvaluationStatus.PASS : PolicyEvaluationStatus.FAIL;
        Map<String, Object> triggeredPolicyActionsSummary = null;

        Map<String, Object> details = new HashMap<>();
        details.put(DETAIL_ENGINE, ENGINE_DSL);
        details.put("policyKind", dsl.getKind());
        details.put("matched", matched);
        details.put("outcome", result.outcome().name());
        if (!result.unknownClauses().isEmpty()) {
            details.put("unknownClauses", result.unknownClauses());
        }
        Map<String, Object> snapshotJson = objectMapper.convertValue(assetData, new TypeReference<Map<String, Object>>() {});
        details.put("assetSnapshot", snapshotJson != null ? snapshotJson : Map.of());
        PolicyEvaluation evaluation = createEvaluationRecord(policy, evaluationRequest.getTargetAssetId(), finalStatus, triggeredByUserId,
            details, null);

        if (finalStatus == PolicyEvaluationStatus.PASS) {
            log.info("Policy '{}' PASSED for asset '{}'", policy.getName(), evaluationRequest.getTargetAssetId());
            Map<String, Object> routes = new LinkedHashMap<>();
            triggeredPolicyActionsSummary = executePolicyActions(policy, dsl, evaluationRequest.getTargetAssetId(),
                    assetData, evaluation.getId(), routes, triggeredBySubject);
            evaluation.setTriggeredActions(triggeredPolicyActionsSummary);
            if (!routes.isEmpty()) {
                // Where each action went: PUBLISHED, or HELD while it waits for approval.
                Map<String, Object> recorded = new HashMap<>(evaluation.getEvaluationDetails());
                recorded.put("actionRoutes", routes);
                evaluation.setEvaluationDetails(recorded);
            }
            policyEvaluationRepository.save(evaluation); 
        } else {
            log.info("Policy '{}' FAILED for asset '{}'", policy.getName(), evaluationRequest.getTargetAssetId());
        }
        
        return policyMapper.toPolicyEvaluationOutputDTO(evaluation, policy.getName());
    }

    @Override
    public PolicyEvaluationOutputDTO triggerPolicyEvaluation(UUID policyId, PolicyEvaluationRequestDTO evaluationRequest, UUID triggeredByUserId, String triggeredBySubject) {
        // Delegate to the main evaluation method
        return evaluatePolicyForAsset(policyId, evaluationRequest, triggeredByUserId, triggeredBySubject);
    }

    private PolicyEvaluation createEvaluationRecord(Policy policy, String targetAssetId, PolicyEvaluationStatus status, UUID triggeredByUserId, Map<String, Object> details, Map<String, Object> triggeredActions) {
        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setPolicyId(policy.getId());
        evaluation.setTargetAssetId(targetAssetId);
        evaluation.setStatus(status);
        evaluation.setEvaluationDetails(details);
        evaluation.setTriggeredActions(triggeredActions);
        evaluation.setEvaluatedAt(clock.instant());
        evaluation.setEvaluationTriggeredByUserId(triggeredByUserId);
        return policyEvaluationRepository.save(evaluation);
    }

    /**
     * Result carrier for asset data lookup. When {@code data} is empty, {@code skipReason}
     * explains why so the caller can emit a SKIPPED evaluation record.
     */
    private record AssetDataResult(Map<String, Object> data, String skipReason) {
        static AssetDataResult ok(Map<String, Object> data) {
            return new AssetDataResult(data, null);
        }
        static AssetDataResult skipped(String reason) {
            return new AssetDataResult(Collections.emptyMap(), reason);
        }
    }

    private AssetDataResult fetchAssetData(String assetId, Map<String, Object> assetSnapshotFromEvent) {
        log.debug("Fetching asset data for assetId: {}", assetId);
        if (assetSnapshotFromEvent != null && !assetSnapshotFromEvent.isEmpty()) {
            Map<String, Object> data = new HashMap<>(assetSnapshotFromEvent);
            data.putIfAbsent("id", assetId);
            return AssetDataResult.ok(data);
        }
        if (!StringUtils.hasText(assetId)) {
            return AssetDataResult.skipped("assetId is blank");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(assetId);
        } catch (IllegalArgumentException e) {
            log.warn("assetId is not a UUID, skipping catalog lookup: {}", assetId);
            return AssetDataResult.skipped("assetId is not a valid UUID: " + assetId);
        }
        // The resilient client answers empty for a 404 and for an unavailable catalog; it never throws.
        Optional<CatalogAssetDTO> maybeDto = catalogAssetClient.getAssetById(uuid);
        if (maybeDto.isPresent()) {
            return AssetDataResult.ok(catalogAssetToFactsMap(maybeDto.get(), assetId));
        }
        log.warn("Catalog returned no asset for assetId {} (not found or unavailable)", assetId);
        return AssetDataResult.skipped("catalog unavailable or asset not found for id " + assetId);
    }

    /**
     * Builds the snapshot {@link PolicyDslEvaluator} reads: {@link AssetSnapshots#fromCatalogAsset}
     * (identity, labels, the ADR-0046 event-time fields, read coverage, legal hold, retention),
     * then the catalog's discovery details for any other key. A details key never stands in for a
     * lifecycle field: an event time the catalog does not know stays unknown.
     */
    private static Map<String, Object> catalogAssetToFactsMap(CatalogAssetDTO dto, String assetIdStr) {
        Map<String, Object> data = AssetSnapshots.fromCatalogAsset(dto);
        data.put("id", dto.getAssetId() != null ? dto.getAssetId().toString() : assetIdStr);
        data.putIfAbsent(AssetSnapshots.ASSET_ID, data.get("id"));
        if (dto.getDetailsJson() != null) {
            dto.getDetailsJson().forEach((k, v) -> {
                if (!AssetSnapshots.LIFECYCLE_KEYS.contains(k)) {
                    data.putIfAbsent(k, v);
                }
            });
        }
        if (dto.getBusinessMetadataJson() != null) {
            data.put("businessMetadata", dto.getBusinessMetadataJson());
        }
        return data;
    }

    /**
     * Whether the legacy actions map names an action that needs full read coverage: archive,
     * delete or a retention-shortening {@code setRetention} (Q-472).
     */
    private static boolean hasDestructiveLegacyAction(Policy policy) {
        return policy.getActions() != null
                && policy.getActions().keySet().stream().anyMatch(PolicyDslEvaluator::needsFullReadCoverage);
    }

    /**
     * Fires the actions of a passing policy: one per key of the legacy actions map when it has
     * any (the shape the lifecycle harnesses drive), otherwise the document's {@code spec.action}.
     * Each carries the asset identity and the document's safety flags, and goes through the
     * {@link ApprovalGate}: published, or held for approval (APPR-01, Q-569).
     *
     * @param routes             receives the gate's route of each action type
     * @param triggeredBySubject the JWT {@code sub} of the caller; it joins the excluded approvers of
     *                           every request a destructive action creates (Q-569), null when none
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executePolicyActions(Policy policy, PolicyDsl dsl, String assetId,
                                                     Map<String, Object> assetData, UUID evaluationId,
                                                     Map<String, Object> routes, String triggeredBySubject) {
        Map<String, Object> actions = new LinkedHashMap<>();
        PolicyDsl.Action dslAction = dsl.getSpec() != null ? dsl.getSpec().getAction() : null;
        if (policy.getActions() != null && !policy.getActions().isEmpty()) {
            actions.putAll(policy.getActions());
        } else if (dslAction != null && StringUtils.hasText(dslAction.getType())) {
            actions.put(dslAction.getType(), dslAction.getParameters());
        }
        if (actions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(assetData);
        Object id = assetData.get("id") != null ? assetData.get("id") : assetId;
        snapshot.put(AssetSnapshots.ASSET_ID, id != null ? id.toString() : null);
        log.info("Executing actions for policy '{}' (id={}) on asset '{}': {}",
                policy.getName(), policy.getId(), id, actions.keySet());

        Instant firedAt = clock.instant();
        Map<String, Object> executedActionsSummary = new HashMap<>();
        actions.forEach((actionType, actionConfig) -> {
            Map<String, Object> parameters = actionConfig instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : actionConfig != null ? Map.of("value", actionConfig) : Map.of();
            ApprovalGate.Route route = approvalGate.submit(PolicyActionRequests.forManual(
                    policy.getId(), policy.getName(), dslAction, actionType, parameters, snapshot,
                    evaluationId, firedAt), triggeredBySubject);
            executedActionsSummary.put(actionType, actionConfig != null ? actionConfig : Map.of());
            routes.put(actionType, route.name());
            log.info("Policy action {} for asset {}: {}", actionType, id, route);
        });
        return executedActionsSummary;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PolicyEvaluationSummaryDTO> getPolicyEvaluations(Pageable pageable, UUID policyIdFilter, String targetAssetIdFilter, String statusFilter) {
        Specification<PolicyEvaluation> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (policyIdFilter != null) {
                predicates.add(criteriaBuilder.equal(root.get("policyId"), policyIdFilter));
            }
            if (StringUtils.hasText(targetAssetIdFilter)) {
                predicates.add(criteriaBuilder.equal(root.get("targetAssetId"), targetAssetIdFilter));
            }
            if (StringUtils.hasText(statusFilter)) {
                try {
                    predicates.add(criteriaBuilder.equal(root.get("status"), PolicyEvaluationStatus.valueOf(statusFilter.toUpperCase())));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid policy evaluation status provided for filtering: {}", statusFilter);
                    predicates.add(criteriaBuilder.disjunction());
                }
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        Page<PolicyEvaluation> evaluationPage = policyEvaluationRepository.findAll(spec, pageable);
        
        List<UUID> policyIds = evaluationPage.getContent().stream()
                                .map(PolicyEvaluation::getPolicyId)
                                .distinct()
                                .collect(Collectors.toList());
        Map<UUID, String> policyNamesMap = Collections.emptyMap();
        if (!policyIds.isEmpty()) {
            policyNamesMap = policyRepository.findAllById(policyIds).stream()
                .collect(Collectors.toMap(Policy::getId, Policy::getName));
        }
        final Map<UUID, String> finalPolicyNamesMap = policyNamesMap;

        return evaluationPage.map(eval -> policyMapper.toPolicyEvaluationSummaryDTO(eval, finalPolicyNamesMap.getOrDefault(eval.getPolicyId(), "Unknown Policy")));
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyEvaluationOutputDTO getPolicyEvaluationById(UUID evaluationId) {
        PolicyEvaluation evaluation = policyEvaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyEvaluation", "id", evaluationId.toString()));
        Policy policy = policyRepository.findById(evaluation.getPolicyId())
            .orElse(null); // Policy might be deleted, handle gracefully
        return policyMapper.toPolicyEvaluationOutputDTO(evaluation, policy != null ? policy.getName() : "Unknown/Deleted Policy");
    }

    // P0-I: evaluatePolicyForAssetInternal retired with the AssetChangeConsumer
    // it served. Kafka-driven policy evaluation runs through LifecyclePolicyConsumer
    // + PolicyDslEvaluator (ADR-0021). evaluatePolicyForAsset above remains the
    // ad-hoc / REST-callable path.
} 