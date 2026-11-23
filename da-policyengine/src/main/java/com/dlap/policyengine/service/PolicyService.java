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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dlap.common.security.SecurityUtils;
import com.dlap.policyengine.common.ConflictException;
import com.dlap.policyengine.common.ResourceNotFoundException;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dsl.PolicyDslTranslator;
import com.dlap.policyengine.dto.PolicyDraftActionDTO;
import com.dlap.policyengine.dto.PolicyDraftInputDTO;
import com.dlap.policyengine.dto.PolicyDraftOutputDTO;
import com.dlap.policyengine.dto.PolicyImpactRequestDTO;
import com.dlap.policyengine.dto.PolicyImpactResponseDTO;
// Add missing DTO imports
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.dto.PolicyOutputDTO;
import com.dlap.policyengine.dto.PolicySummaryDTO;
import com.dlap.policyengine.filter.BoundedRegex;
import com.dlap.policyengine.filter.RetiredRules;
import com.dlap.policyengine.mapper.PolicyDraftMapper;
// Add missing service imports
import com.dlap.policyengine.mapper.PolicyMapper;
// Add missing entity imports
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyDraft;
import com.dlap.policyengine.model.PolicyChangeType;
import com.dlap.policyengine.model.PolicyDraftStatus;
import com.dlap.policyengine.model.PolicyKind;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.model.PolicyVersion;
import com.dlap.policyengine.repository.PolicyDraftRepository;
import com.dlap.policyengine.repository.PolicyRepository;

// Add missing JPA criteria import
import jakarta.persistence.criteria.Predicate;

/**
 * Policies and policy drafts.
 *
 * <p>Policy history (AUD-02a): every create, update, status change and delete of a policy adds one
 * immutable version and one entry of the audit stream {@code govern.policy}, in the same transaction
 * ({@link PolicyHistoryService}). A delete is soft: the policy is disabled, stamped with
 * {@code deletedAt} and frozen, and its history stays readable. When the audit log cannot record a
 * change, nothing is changed and the API answers 503 {@code audit-unavailable}.
 */
@Service
@Transactional
public class PolicyService implements IPolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    /** The DSL match clause whose value is a user-supplied regular expression. */
    private static final String NAME_MATCHES = "nameMatches";

    private final PolicyRepository policyRepository;
    private final PolicyMapper policyMapper;

    // Draft management dependencies
    private final PolicyDraftRepository policyDraftRepository;
    private final PolicyDraftMapper policyDraftMapper;

    // DSL unification (ADR-0031): validate canonical documents on save and
    // translate the visual builder's structured drafts into DSL YAML.
    private final PolicyDslParser dslParser;
    private final PolicyDslTranslator dslTranslator;

    // Notifies the PDP's AccessPolicy cache to refresh after a change commits
    // (ADR-0040 immediate-propagation seam).
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // Policy history (AUD-02a): versions and the govern.policy audit stream.
    private final PolicyHistoryService history;

    /**
     * Creates the service.
     *
     * @param policyRepository      the policies
     * @param policyMapper          entity and DTO mapping
     * @param policyDraftRepository the drafts
     * @param policyDraftMapper     draft mapping
     * @param dslParser             the DSL parser, to validate documents
     * @param dslTranslator         the DSL translator, to publish drafts
     * @param eventPublisher        the publisher of policy-change events
     * @param history               the policy history (versions and audit chain)
     */
    @Autowired
    public PolicyService(PolicyRepository policyRepository, PolicyMapper policyMapper,
                        PolicyDraftRepository policyDraftRepository, PolicyDraftMapper policyDraftMapper,
                        PolicyDslParser dslParser, PolicyDslTranslator dslTranslator,
                        org.springframework.context.ApplicationEventPublisher eventPublisher,
                        PolicyHistoryService history) {
        this.policyRepository = policyRepository;
        this.policyMapper = policyMapper;
        this.policyDraftRepository = policyDraftRepository;
        this.policyDraftMapper = policyDraftMapper;
        this.dslParser = dslParser;
        this.dslTranslator = dslTranslator;
        this.eventPublisher = eventPublisher;
        this.history = history;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PolicySummaryDTO> getAllPolicies(Pageable pageable, String status, String nameContains) {
        Specification<Policy> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(status)) {
                try {
                    predicates.add(criteriaBuilder.equal(root.get("status"), PolicyStatus.valueOf(status.toUpperCase())));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid policy status provided: {}", status);
                    // Option: throw bad request, or ignore filter, or return empty
                    // Returning empty for now by adding a certainly false predicate
                    predicates.add(criteriaBuilder.disjunction());
                }
            }
            if (StringUtils.hasText(nameContains)) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("name")),
                        "%" + nameContains.toLowerCase() + "%"));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
        return policyRepository.findAll(spec, pageable).map(policyMapper::toPolicySummaryDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyOutputDTO getPolicyById(UUID policyId) {
        Policy policy = livePolicy(policyId);
        return policyMapper.toPolicyOutputDTO(policy);
    }

    /**
     * A live policy, or 404. A soft-deleted policy is hidden by the entity's restriction already; the
     * check here keeps that true on any persistence path.
     */
    private Policy livePolicy(UUID policyId) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", "id", policyId.toString()));
        if (policy.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Policy", "id", policyId.toString());
        }
        return policy;
    }

    /**
     * Records a change of a policy: the next version and its audit entry, after the policy row is
     * written. The policy carries the version's time and number; a delete also its stamp.
     *
     * @param policy     the policy after the change
     * @param changeType the change
     * @param userId     who made it
     * @return the version
     */
    private PolicyVersion recordChange(Policy policy, PolicyChangeType changeType, UUID userId) {
        PolicyHistoryService.Pending pending = history.prepare(policy, changeType, userId);
        policy.setUpdatedAt(pending.at());
        policy.setCurrentVersion(pending.version());
        if (changeType == PolicyChangeType.DELETED) {
            policy.setDeletedAt(pending.at());
            policy.setDeletedByUserId(userId);
        }
        policyRepository.saveAndFlush(policy);
        // Last, so nothing but the commit can still fail once the audit entry exists.
        return history.record(pending);
    }

    @Override
    public PolicyOutputDTO createPolicy(PolicyInputDTO policyInputDTO, UUID creatorUserId) {
        rejectRetiredRules(policyInputDTO);
        validatePolicyDocument(policyInputDTO);
        policyRepository.findByName(policyInputDTO.getName()).ifPresent(p -> {
            throw new ConflictException("Policy with name '" + policyInputDTO.getName() + "' already exists.");
        });

        Policy policy = policyMapper.toPolicyEntity(policyInputDTO);
        // validatePolicyDocument above guarantees a document, so every row the API creates is DSL.
        policy.setPolicyKind(PolicyKind.DSL);
        policy.setCreatedByUserId(creatorUserId);
        Instant now = history.now();
        policy.setCreatedAt(now);
        policy.setUpdatedAt(now);

        Policy savedPolicy = policyRepository.save(policy);
        policyRepository.flush();
        PolicyVersion version = recordChange(savedPolicy, PolicyChangeType.CREATED, creatorUserId);
        eventPublisher.publishEvent(new com.dlap.policyengine.access.PolicyChangedEvent("create"));
        log.info("Created policy {} with id {} (version {})", savedPolicy.getName(), savedPolicy.getId(),
                version.getVersion());
        return policyMapper.toPolicyOutputDTO(savedPolicy);
    }

    @Override
    public PolicyOutputDTO updatePolicy(UUID policyId, PolicyInputDTO policyInputDTO, UUID updaterUserId) {
        rejectRetiredRules(policyInputDTO);
        validatePolicyDocument(policyInputDTO);
        Policy existingPolicy = history.lockForChange(livePolicy(policyId));
        history.baselineIfNeeded(existingPolicy);

        // Check for name conflict if name is being changed
        if (!existingPolicy.getName().equals(policyInputDTO.getName())) {
            policyRepository.findByName(policyInputDTO.getName()).ifPresent(p -> {
                if (!p.getId().equals(existingPolicy.getId())) {
                    throw new ConflictException("Another policy with name '" + policyInputDTO.getName() + "' already exists.");
                }
            });
        }

        policyMapper.updatePolicyEntityFromInputDTO(existingPolicy, policyInputDTO);
        // A legacy rule row that gets a document (validated above) becomes a DSL row (POL-04).
        existingPolicy.setPolicyKind(PolicyKind.DSL);
        existingPolicy.setUpdatedByUserId(updaterUserId);

        PolicyVersion version = recordChange(existingPolicy, PolicyChangeType.UPDATED, updaterUserId);
        eventPublisher.publishEvent(new com.dlap.policyengine.access.PolicyChangedEvent("update"));
        log.info("Updated policy {} with id {} (version {})", existingPolicy.getName(), existingPolicy.getId(),
                version.getVersion());
        return policyMapper.toPolicyOutputDTO(existingPolicy);
    }

    @Override
    public PolicyOutputDTO setPolicyStatus(UUID policyId, PolicyStatus status, UUID updaterUserId) {
        Policy policy = history.lockForChange(livePolicy(policyId));
        // Guard: don't enable a policy whose stored DSL no longer parses: an
        // ENABLED-but-inert policy is exactly the failure mode ADR-0031 removes. Since the
        // MVEL rules were retired (SEC-04, ADR-0057) a DSL document is the only way to run,
        // so a policy V5 disabled for its legacy rules stays disabled until it gets one.
        if (status == PolicyStatus.ENABLED) {
            String doc = policy.getPolicyDocument();
            if (doc == null || doc.isBlank()) {
                throw new IllegalArgumentException(
                        "Cannot enable a policy with no dlap/v1 DSL document (policyDocument); "
                        + "legacy MVEL rules were retired (ADR-0057).");
            }
            try {
                validateNameMatches(dslParser.parse(doc));
            } catch (PolicyDslParser.PolicyDslParseException e) {
                throw new IllegalArgumentException(
                        "Cannot enable policy: its DSL is invalid: " + e.getMessage());
            }
        }
        history.baselineIfNeeded(policy);
        policy.setStatus(status);
        policy.setUpdatedByUserId(updaterUserId);
        PolicyVersion version = recordChange(policy, PolicyChangeType.STATUS_CHANGED, updaterUserId);
        eventPublisher.publishEvent(new com.dlap.policyengine.access.PolicyChangedEvent("status:" + status));
        log.info("Set policy {} status to {} (version {})", policyId, status, version.getVersion());
        return policyMapper.toPolicyOutputDTO(policy);
    }

    /**
     * Deletes a policy softly (AUD-02a): it is disabled, stamped with {@code deletedAt} and the caller,
     * and frozen; it disappears from every policy query and the evaluators, and its versions, the last
     * one {@code DELETED}, stay readable. Approval history never depended on the policy row.
     *
     * @param policyId the policy
     */
    @Override
    public void deletePolicy(UUID policyId) {
        Policy policy = history.lockForChange(livePolicy(policyId));
        UUID deleterUserId = SecurityUtils.getAuthenticatedUserId();
        history.baselineIfNeeded(policy);
        policy.setStatus(PolicyStatus.DISABLED);
        policy.setUpdatedByUserId(deleterUserId);
        PolicyVersion version = recordChange(policy, PolicyChangeType.DELETED, deleterUserId);
        eventPublisher.publishEvent(new com.dlap.policyengine.access.PolicyChangedEvent("delete"));
        log.info("Deleted policy {} (soft delete, version {})", policyId, version.getVersion());
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyImpactResponseDTO analyzePolicy(PolicyImpactRequestDTO request) {
        // TODO(M1): implement real impact analysis. A real implementation must parse the
        // draft rules, query the catalog for matching assets, simulate rule evaluation
        // against each, and aggregate the counts/risk from actual data. Returning fabricated
        // numbers is explicitly forbidden, so until the real pipeline lands this endpoint
        // returns an explicit "data not available" response.
        log.warn("Policy impact analysis requested (analysisType={}) but real implementation is not yet available; returning unavailable response.",
                request != null ? request.getAnalysisType() : null);

        long startTime = System.currentTimeMillis();
        String analysisId = "impact-" + UUID.randomUUID().toString().substring(0, 8);
        String analysisType = request != null ? request.getAnalysisType() : null;

        PolicyImpactResponseDTO response = new PolicyImpactResponseDTO(analysisId, analysisType);
        response.setDataAvailable(false);
        response.setReason("impact analysis not yet implemented");
        response.setSummary(emptyImpactSummary());
        response.setAffectedAssets(new ArrayList<>());
        response.setRiskAssessment(emptyRiskAssessment());
        response.setRecommendations(new ArrayList<>());

        if (request != null && Boolean.TRUE.equals(request.getIncludePerformanceEstimate())) {
            response.setPerformanceImpact(new PolicyImpactResponseDTO.PerformanceImpactDTO());
        }
        if (request != null && Boolean.TRUE.equals(request.getIncludeCostImpact())) {
            response.setCostImpact(new PolicyImpactResponseDTO.CostImpactDTO());
        }
        if (request != null && Boolean.TRUE.equals(request.getIncludeComplianceImpact())) {
            response.setComplianceImpact(new PolicyImpactResponseDTO.ComplianceImpactDTO());
        }

        PolicyImpactResponseDTO.AnalysisMetadataDTO metadata = new PolicyImpactResponseDTO.AnalysisMetadataDTO();
        metadata.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        metadata.setUsesCachedData(false);
        metadata.setDataFreshnessTimestamp(Instant.now());
        response.setMetadata(metadata);

        return response;
    }

    /**
     * Zeroed summary so clients can render the fields without encountering null metrics.
     * No fabricated values - every count is 0 and risk level is UNKNOWN.
     */
    private PolicyImpactResponseDTO.ImpactSummaryDTO emptyImpactSummary() {
        PolicyImpactResponseDTO.ImpactSummaryDTO summary = new PolicyImpactResponseDTO.ImpactSummaryDTO();
        summary.setTotalAssetsAnalyzed(0);
        summary.setTotalAssetsAffected(0);
        summary.setHighImpactAssets(0);
        summary.setMediumImpactAssets(0);
        summary.setLowImpactAssets(0);
        summary.setOverallRiskLevel("UNKNOWN");
        summary.setImpactPercentage(0.0);
        return summary;
    }

    private PolicyImpactResponseDTO.RiskAssessmentDTO emptyRiskAssessment() {
        PolicyImpactResponseDTO.RiskAssessmentDTO risk = new PolicyImpactResponseDTO.RiskAssessmentDTO();
        risk.setOverallRiskLevel("UNKNOWN");
        risk.setRiskScore(0.0);
        risk.setIdentifiedRisks(new ArrayList<>());
        risk.setMitigationStrategies(new ArrayList<>());
        return risk;
    }

    // ========== POLICY DRAFT MANAGEMENT IMPLEMENTATION ==========

    @Override
    @Transactional
    public PolicyDraftOutputDTO createDraft(PolicyDraftInputDTO draftInput, UUID creatorUserId) {
        log.info("Creating new policy draft: {}", draftInput.getName());
        validateDraftRegexes(draftInput);

        // Validate draft name uniqueness
        if (policyDraftRepository.existsByName(draftInput.getName())) {
            throw new ConflictException("A draft with this name already exists");
        }
        
        // Convert DTO to entity
        PolicyDraft draft = policyDraftMapper.toEntity(draftInput);
        draft.setCreatedByUserId(creatorUserId);
        draft.setStatus(PolicyDraftStatus.CREATED);
        
        // Save the draft
        PolicyDraft savedDraft = policyDraftRepository.save(draft);
        
        log.info("Successfully created policy draft with ID: {}", savedDraft.getId());
        return policyDraftMapper.toOutputDTO(savedDraft);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyDraftOutputDTO getDraftById(UUID draftId) {
        log.debug("Retrieving policy draft with ID: {}", draftId);
        
        PolicyDraft draft = policyDraftRepository.findById(draftId)
            .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        
        return policyDraftMapper.toOutputDTO(draft);
    }

    @Override
    @Transactional
    public PolicyDraftOutputDTO updateDraft(UUID draftId, PolicyDraftInputDTO draftInput, UUID updaterUserId) {
        log.info("Updating policy draft with ID: {}", draftId);
        
        PolicyDraft existingDraft = policyDraftRepository.findById(draftId)
            .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        
        // Validate that draft can be updated
        if (!canEditDraft(existingDraft.getStatus())) {
            throw new ConflictException("Draft cannot be edited in current status: " + existingDraft.getStatus());
        }
        validateDraftRegexes(draftInput);
        
        // Validate name uniqueness (excluding current draft)
        if (!draftInput.getName().equals(existingDraft.getName()) && 
            policyDraftRepository.existsByNameExcludingId(draftInput.getName(), draftId)) {
            throw new ConflictException("A draft with this name already exists");
        }
        
        // Update the draft
        policyDraftMapper.updateEntity(existingDraft, draftInput);
        existingDraft.setUpdatedByUserId(updaterUserId);
        
        PolicyDraft savedDraft = policyDraftRepository.save(existingDraft);
        
        log.info("Successfully updated policy draft with ID: {}", savedDraft.getId());
        return policyDraftMapper.toOutputDTO(savedDraft);
    }

    @Override
    @Transactional
    public PolicyDraftOutputDTO submitDraft(UUID draftId, PolicyDraftActionDTO action, UUID submittedByUserId) {
        log.info("Submitting policy draft with ID: {}", draftId);
        
        PolicyDraft draft = policyDraftRepository.findById(draftId)
            .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        
        // Validate status transition
        if (!canSubmitDraft(draft.getStatus())) {
            throw new ConflictException("Draft cannot be submitted in current status: " + draft.getStatus());
        }
        
        // Update draft status and metadata
        draft.setStatus(PolicyDraftStatus.SUBMITTED);
        draft.setSubmittedAt(Instant.now());
        draft.setSubmittedByUserId(submittedByUserId);
        draft.setUpdatedByUserId(submittedByUserId);
        
        // Add comment if provided
        if (action.getComment() != null && !action.getComment().trim().isEmpty()) {
            draft.addReviewComment("Submission: " + action.getComment(), submittedByUserId, "SUBMITTER");
        }
        
        PolicyDraft savedDraft = policyDraftRepository.save(draft);
        
        log.info("Successfully submitted policy draft with ID: {}", savedDraft.getId());
        return policyDraftMapper.toOutputDTO(savedDraft);
    }

    @Override
    @Transactional
    public PolicyDraftOutputDTO approveDraft(UUID draftId, PolicyDraftActionDTO action, UUID approverUserId) {
        log.info("Approving policy draft with ID: {}", draftId);
        
        PolicyDraft draft = policyDraftRepository.findById(draftId)
            .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        
        // Validate status transition
        if (!canApproveDraft(draft.getStatus())) {
            throw new ConflictException("Draft cannot be approved in current status: " + draft.getStatus());
        }
        
        // Update draft status and metadata
        draft.setStatus(PolicyDraftStatus.APPROVED);
        draft.setApprovedAt(Instant.now());
        draft.setApprovedByUserId(approverUserId);
        draft.setUpdatedByUserId(approverUserId);
        
        // Add comment if provided
        if (action.getComment() != null && !action.getComment().trim().isEmpty()) {
            draft.addReviewComment("Approval: " + action.getComment(), approverUserId, "APPROVER");
        }
        
        PolicyDraft savedDraft = policyDraftRepository.save(draft);
        
        log.info("Successfully approved policy draft with ID: {}", savedDraft.getId());
        return policyDraftMapper.toOutputDTO(savedDraft);
    }

    @Override
    @Transactional
    public PolicyDraftOutputDTO rejectDraft(UUID draftId, PolicyDraftActionDTO action, UUID rejectorUserId) {
        log.info("Rejecting policy draft with ID: {}", draftId);
        
        PolicyDraft draft = policyDraftRepository.findById(draftId)
            .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        
        // Validate status transition
        if (!canRejectDraft(draft.getStatus())) {
            throw new ConflictException("Draft cannot be rejected in current status: " + draft.getStatus());
        }
        
        // Rejection requires a comment
        if (action.getComment() == null || action.getComment().trim().isEmpty()) {
            throw new IllegalArgumentException("Comment is required for draft rejection");
        }
        
        // Update draft status and metadata
        draft.setStatus(PolicyDraftStatus.REJECTED);
        draft.setRejectedAt(Instant.now());
        draft.setRejectedByUserId(rejectorUserId);
        draft.setUpdatedByUserId(rejectorUserId);
        
        // Add rejection comment
        draft.addReviewComment("Rejection: " + action.getComment(), rejectorUserId, "REJECTOR");
        
        PolicyDraft savedDraft = policyDraftRepository.save(draft);
        
        log.info("Successfully rejected policy draft with ID: {}", savedDraft.getId());
        return policyDraftMapper.toOutputDTO(savedDraft);
    }

    @Override
    @Transactional
    public PolicyDraftOutputDTO requestChanges(UUID draftId, PolicyDraftActionDTO action, UUID reviewerUserId) {
        log.info("Requesting changes for policy draft with ID: {}", draftId);
        
        PolicyDraft draft = policyDraftRepository.findById(draftId)
            .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        
        // Validate status transition
        if (!canRequestChanges(draft.getStatus())) {
            throw new ConflictException("Changes cannot be requested for draft in current status: " + draft.getStatus());
        }
        
        // Change request requires a comment
        if (action.getComment() == null || action.getComment().trim().isEmpty()) {
            throw new IllegalArgumentException("Comment is required for change request");
        }
        
        // Update draft status and metadata
        draft.setStatus(PolicyDraftStatus.REQUIRES_CHANGES);
        draft.setUpdatedByUserId(reviewerUserId);
        
        // Add change request comment
        draft.addReviewComment("Change Request: " + action.getComment(), reviewerUserId, "REVIEWER");
        
        PolicyDraft savedDraft = policyDraftRepository.save(draft);
        
        log.info("Successfully requested changes for policy draft with ID: {}", savedDraft.getId());
        return policyDraftMapper.toOutputDTO(savedDraft);
    }

    @Override
    @Transactional
    public PolicyOutputDTO publishDraft(UUID draftId, PolicyDraftActionDTO action, UUID publisherUserId) {
        log.info("Publishing policy draft with ID: {}", draftId);
        
        PolicyDraft draft = policyDraftRepository.findById(draftId)
            .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        
        // Validate status
        if (draft.getStatus() != PolicyDraftStatus.APPROVED) {
            throw new ConflictException("Only approved drafts can be published");
        }
        
        // Create policy from draft
        PolicyInputDTO policyInput = convertDraftToPolicy(draft);
        PolicyOutputDTO publishedPolicy = createPolicy(policyInput, publisherUserId);
        
        // Update draft status
        draft.setStatus(PolicyDraftStatus.PUBLISHED);
        draft.setPublishedAt(Instant.now());
        draft.setPublishedByUserId(publisherUserId);
        draft.setUpdatedByUserId(publisherUserId);
        
        // Add publication comment
        if (action.getComment() != null && !action.getComment().trim().isEmpty()) {
            draft.addReviewComment("Publication: " + action.getComment(), publisherUserId, "PUBLISHER");
        }
        
        policyDraftRepository.save(draft);
        
        log.info("Successfully published policy draft {} as policy {}", draftId, publishedPolicy.getId());
        return publishedPolicy;
    }

    // Helper methods for validation
    private boolean canEditDraft(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.CREATED || status == PolicyDraftStatus.REQUIRES_CHANGES;
    }

    private boolean canSubmitDraft(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.CREATED || status == PolicyDraftStatus.REQUIRES_CHANGES;
    }

    private boolean canApproveDraft(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.SUBMITTED || status == PolicyDraftStatus.UNDER_REVIEW;
    }

    private boolean canRejectDraft(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.SUBMITTED || 
               status == PolicyDraftStatus.UNDER_REVIEW || 
               status == PolicyDraftStatus.APPROVED;
    }

    private boolean canRequestChanges(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.SUBMITTED || status == PolicyDraftStatus.UNDER_REVIEW;
    }

    // Convert draft to policy for publication (ADR-0031).
    // The visual builder stores structured conditions (rulesDefinition) and an
    // action on the draft; publication translates those into the canonical
    // dlap/v1 DSL document the evaluator runs, and enables the policy so it
    // actually fires. A draft with no resolvable action is rejected (HTTP 400).
    private PolicyInputDTO convertDraftToPolicy(PolicyDraft draft) {
        PolicyInputDTO policyInput = new PolicyInputDTO();
        policyInput.setName(draft.getName());
        policyInput.setDescription(draft.getDescription());
        policyInput.setConditionLogic(draft.getConditionLogic());
        policyInput.setActions(draft.getActions());
        // Publishing a draft means activating the policy - the DSL consumer /
        // AuthZEN PDP only load ENABLED policies.
        policyInput.setStatus(PolicyStatus.ENABLED);

        boolean isAccessPolicy =
                com.dlap.policyengine.dsl.PolicyDsl.KIND_ACCESS_POLICY.equals(draft.getKind());

        try {
            String dsl;
            if (isAccessPolicy) {
                // ADR-0040: translate the structured AccessPolicy draft into a
                // kind:AccessPolicy document the AuthZEN PDP evaluates. The asset
                // selector reuses rulesDefinition (spec.match).
                dsl = dslTranslator.toYamlAccessPolicy(
                        null,                       // metadata.id: slug from name
                        draft.getName(),
                        draft.getDescription(),
                        null,                       // owner: not captured on drafts (follow-up)
                        true,                       // enabled
                        draft.getRulesDefinition(), // spec.match asset selector
                        draft.getAccessSubjects(),  // {tiers, roles}
                        draft.getAccessActions(),   // AuthZEN action names
                        draft.getAccessCondition(), // {purpose, consent, region}
                        draft.getAccessEffect(),    // allow | deny
                        draft.getAccessObligations()); // [{type, labels}]
            } else {
                dsl = dslTranslator.toYaml(
                        null,                       // metadata.id: derive a slug from the name
                        draft.getName(),
                        draft.getDescription(),
                        null,                       // owner: not captured on drafts (follow-up)
                        true,                       // enabled
                        draft.getRulesDefinition(), // structured match conditions
                        draft.getActions(),         // action(s)
                        null);                      // triggers: default created + modified events
            }
            policyInput.setPolicyDocument(dsl);
        } catch (PolicyDslTranslator.PolicyTranslationException e) {
            throw new IllegalArgumentException(
                    "Cannot publish draft '" + draft.getName() + "': " + e.getMessage());
        }
        return policyInput;
    }

    /**
     * Refuses legacy {@code rules[]} from every caller (SEC-04, ADR-0057).
     *
     * <p>A legacy rule condition was an MVEL expression, which is executable code. MVEL is
     * retired: the REST layer already answers HTTP 400 through bean validation, and this
     * check keeps the service safe if it is ever called from another entry point. Nothing
     * is mapped or stored when it fails.
     *
     * @param dto the policy input; may be {@code null}
     * @throws IllegalArgumentException when any legacy rule is present
     */
    private void rejectRetiredRules(PolicyInputDTO dto) {
        if (dto == null || dto.getRules() == null || dto.getRules().isEmpty()) {
            return;
        }
        log.warn("Refused a policy carrying {} retired rule condition(s)", dto.getRules().size());
        throw new IllegalArgumentException(RetiredRules.POLICY_RULES_MESSAGE);
    }

    /**
     * Enforce the DSL-canonical contract (ADR-0031, ADR-0057): a policy must carry a
     * dlap/v1 DSL document, and the document must parse, so an inert policy is never
     * persisted. Parse failures, an unbounded {@code nameMatches} regex and a destructive
     * action without a match surface as HTTP 400 with the reason.
     */
    private void validatePolicyDocument(PolicyInputDTO dto) {
        String doc = dto.getPolicyDocument();
        boolean hasDoc = doc != null && !doc.isBlank();
        if (!hasDoc) {
            throw new IllegalArgumentException(
                    "A policy requires a dlap/v1 DSL document (policyDocument); "
                    + "legacy MVEL rules were retired (ADR-0057).");
        }
        com.dlap.policyengine.dsl.PolicyDsl dsl;
        try {
            dsl = dslParser.parse(doc);
        } catch (PolicyDslParser.PolicyDslParseException e) {
            throw new IllegalArgumentException("Invalid policy DSL: " + e.getMessage());
        }
        String effectDefect = PolicyDslParser.lifecycleEffectDefect(dsl);
        if (effectDefect != null) {
            throw new IllegalArgumentException("Invalid policy DSL: " + effectDefect);
        }
        validateNameMatches(dsl);
        // Safety guard: a destructive action (archive/delete) with no match
        // clauses would fire against EVERY asset. Require at least one
        // narrowing condition so a mis-built policy can't wipe the estate.
        var spec = dsl.getSpec();
        var action = spec != null ? spec.getAction() : null;
        var match = spec != null ? spec.getMatch() : null;
        if (action != null && action.getType() != null) {
            String type = action.getType().toLowerCase(java.util.Locale.ROOT);
            if ((type.equals("archive") || type.equals("delete"))
                    && (match == null || match.isEmpty())) {
                throw new IllegalArgumentException(
                        "A '" + action.getType() + "' policy must include at least one match "
                        + "condition (an empty match would target every asset).");
            }
        }
    }

    /**
     * Checks the {@code nameMatches} regex of a parsed document (SEC-04): at most
     * {@link BoundedRegex#MAX_PATTERN_LENGTH} characters and valid RE2 syntax, the same
     * rules {@link com.dlap.policyengine.dsl.PolicyDslEvaluator} applies when it runs it.
     *
     * @param dsl the parsed document; may be {@code null}
     * @throws IllegalArgumentException when the clause is not a string or the regex is invalid
     */
    private static void validateNameMatches(com.dlap.policyengine.dsl.PolicyDsl dsl) {
        Map<String, Object> match = dsl != null && dsl.getSpec() != null ? dsl.getSpec().getMatch() : null;
        if (match == null || !match.containsKey(NAME_MATCHES)) {
            return;
        }
        Object pattern = match.get(NAME_MATCHES);
        if (!(pattern instanceof String text)) {
            throw new IllegalArgumentException("spec.match.nameMatches must be a regular expression string.");
        }
        BoundedRegex.validate(text);
    }

    /**
     * Checks every {@code nameMatches} condition of a visual-builder draft (SEC-04), so a
     * regex that could never run is refused when it is saved, not when it is published.
     *
     * @param draftInput the draft input; may be {@code null}
     * @throws IllegalArgumentException when a {@code nameMatches} value is not a valid bounded regex
     */
    private static void validateDraftRegexes(PolicyDraftInputDTO draftInput) {
        if (draftInput == null || draftInput.getRulesDefinition() == null) {
            return;
        }
        for (Map<String, Object> condition : draftInput.getRulesDefinition()) {
            if (condition != null && NAME_MATCHES.equals(condition.get("field"))) {
                Object pattern = condition.get("value");
                if (!(pattern instanceof String text)) {
                    throw new IllegalArgumentException("A nameMatches condition needs a regular expression string.");
                }
                BoundedRegex.validate(text);
            }
        }
    }

    // Placeholder implementations for remaining methods (to resolve compilation errors)
    @Override
    public Page<PolicyDraftOutputDTO> getAllDrafts(Pageable pageable, PolicyDraftStatus status, String category, String priority, UUID createdBy, String nameContains) {
        Specification<PolicyDraft> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(category)) {
                predicates.add(cb.equal(cb.lower(root.get("category")), category.toLowerCase()));
            }
            if (StringUtils.hasText(priority)) {
                predicates.add(cb.equal(cb.lower(root.get("priority")), priority.toLowerCase()));
            }
            if (createdBy != null) {
                predicates.add(cb.equal(root.get("createdByUserId"), createdBy));
            }
            if (StringUtils.hasText(nameContains)) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + nameContains.toLowerCase() + "%"));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
        return policyDraftRepository.findAll(spec, pageable).map(policyDraftMapper::toOutputDTO);
    }

    @Override
    public Page<PolicyDraftOutputDTO> getDraftsRequiringAttention(UUID userId, Pageable pageable) {
        Page<PolicyDraft> drafts = policyDraftRepository.findRequiringAttention(userId, pageable);
        return drafts.map(policyDraftMapper::toOutputDTO);
    }

    @Override
    public Page<PolicyDraftOutputDTO> getDraftsPendingReview(Pageable pageable) {
        Page<PolicyDraft> drafts = policyDraftRepository.findPendingReview(pageable);
        return drafts.map(policyDraftMapper::toOutputDTO);
    }

    @Override
    public PolicyDraftOutputDTO archiveDraft(UUID draftId, PolicyDraftActionDTO action, UUID archiverUserId) {
        PolicyDraft draft = policyDraftRepository.findById(draftId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        draft.setStatus(PolicyDraftStatus.ARCHIVED);
        draft.setUpdatedByUserId(archiverUserId);
        if (action != null && StringUtils.hasText(action.getComment())) {
            draft.addReviewComment("Archive: " + action.getComment(), archiverUserId, "ARCHIVER");
        }
        return policyDraftMapper.toOutputDTO(policyDraftRepository.save(draft));
    }

    @Override
    public PolicyDraftOutputDTO addReviewComment(UUID draftId, String comment, UUID reviewerUserId, String reviewerRole) {
        PolicyDraft draft = policyDraftRepository.findById(draftId)
            .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        
        draft.addReviewComment(comment, reviewerUserId, reviewerRole);
        PolicyDraft savedDraft = policyDraftRepository.save(draft);
        
        return policyDraftMapper.toOutputDTO(savedDraft);
    }

    @Override
    public void deleteDraft(UUID draftId, UUID deleterUserId) {
        PolicyDraft draft = policyDraftRepository.findById(draftId)
            .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        
        if (draft.getStatus() != PolicyDraftStatus.CREATED) {
            throw new ConflictException("Only drafts in CREATED status can be deleted");
        }
        
        policyDraftRepository.delete(draft);
        log.info("Successfully deleted policy draft with ID: {}", draftId);
    }

    @Override
    public List<String> getDraftCategories() {
        return policyDraftRepository.findAllCategories();
    }

    @Override
    public List<String> getDraftTags() {
        return policyDraftRepository.findAllTags();
    }

    @Override
    public List<Object[]> getDraftStatistics() {
        return policyDraftRepository.getDraftStatisticsByStatus();
    }

    @Override
    public List<PolicyDraftOutputDTO> getOverdueDrafts() {
        List<PolicyDraft> overdueDrafts = policyDraftRepository.findOverdueDrafts(Instant.now());
        return overdueDrafts.stream()
            .map(policyDraftMapper::toOutputDTO)
            .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public PolicyDraftOutputDTO clonePolicyAsDraft(UUID policyId, UUID creatorUserId) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", "id", policyId.toString()));
        PolicyDraftInputDTO input = new PolicyDraftInputDTO();
        input.setName(policy.getName() + " (draft clone)");
        input.setDescription(policy.getDescription());
        input.setConditionLogic(policy.getConditionLogic());
        input.setBasePolicyId(policyId);
        input.setChangeSummary("Cloned from published policy " + policyId);
        return createDraft(input, creatorUserId);
    }

    @Override
    public PolicyDraftOutputDTO createDraftVersion(UUID draftId, PolicyDraftInputDTO draftInput, UUID creatorUserId) {
        PolicyDraft existing = policyDraftRepository.findById(draftId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy draft not found with ID: " + draftId));
        PolicyDraftInputDTO input = draftInput != null ? draftInput : new PolicyDraftInputDTO();
        if (!StringUtils.hasText(input.getName())) {
            input.setName(existing.getName() + " v" + ((existing.getVersion() == null ? 1 : existing.getVersion()) + 1));
        }
        if (!StringUtils.hasText(input.getDescription())) {
            input.setDescription(existing.getDescription());
        }
        if (!StringUtils.hasText(input.getConditionLogic())) {
            input.setConditionLogic(existing.getConditionLogic());
        }
        if (input.getRulesDefinition() == null) {
            input.setRulesDefinition(existing.getRulesDefinition());
        }
        if (input.getActions() == null) {
            input.setActions(existing.getActions());
        }
        if (!StringUtils.hasText(input.getPriority())) {
            input.setPriority(existing.getPriority());
        }
        if (!StringUtils.hasText(input.getCategory())) {
            input.setCategory(existing.getCategory());
        }
        PolicyDraft next = policyDraftMapper.toEntity(input);
        next.setCreatedByUserId(creatorUserId);
        next.setStatus(PolicyDraftStatus.CREATED);
        next.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);
        next.setBasePolicyId(existing.getBasePolicyId());
        PolicyDraft saved = policyDraftRepository.save(next);
        return policyDraftMapper.toOutputDTO(saved);
    }
} 