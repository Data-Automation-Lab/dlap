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

package com.dlap.catalog.service;

import com.dlap.common.constants.DlapConstants;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dlap.catalog.common.ConflictException;
import com.dlap.catalog.common.ResourceNotFoundException;
import com.dlap.catalog.integration.ExternalCatalogIntegration;
import com.dlap.catalog.dto.AssetActivityPageDTO;
import com.dlap.catalog.dto.AssetActivityRowDTO;
import com.dlap.catalog.dto.AssetComplianceStatusDTO;
import com.dlap.catalog.dto.AssetInputDTO;
import com.dlap.catalog.dto.AssetLabelsPostRequestDTO;
import com.dlap.catalog.dto.AssetOutputDTO;
import com.dlap.catalog.dto.AssetPolicyMappingDTO;
import com.dlap.catalog.dto.AssetSchemaDTO;
import com.dlap.catalog.dto.AssetUsageAnalyticsDTO;
import com.dlap.catalog.dto.BusinessMetadataInputDTO;
import com.dlap.catalog.dto.BusinessMetadataResponseDTO;
import com.dlap.catalog.dto.CatalogFiltersDTO;
import com.dlap.catalog.dto.EnhancedLineageDTO;
import com.dlap.catalog.dto.LabelAssignmentInputDTO;
import com.dlap.catalog.dto.LabelOutputDTO;
import com.dlap.catalog.dto.LineageAssetDTO;
import com.dlap.catalog.dto.LineageResponseDTO;
import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.catalog.mapper.AssetMapper;
import com.dlap.catalog.model.AssetLineage;
import com.dlap.catalog.repository.AssetLineageRepository;
import com.dlap.catalog.usage.AssetUsageDailyStore;
import com.dlap.catalog.usage.AssetUsageDailyStore.UsageDay;
import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.event.AssetChangeEvent;
import com.dlap.common.event.AssetSnapshot;
import com.dlap.common.event.LabelAssignmentDetails;
import com.dlap.common.model.RetentionStatus;
import com.dlap.common.model.entity.Asset;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dlap.common.model.entity.AssetLabelAssignment;
import com.dlap.common.model.entity.Label;
import com.dlap.common.repository.IAssetLabelAssignmentRepository;
import com.dlap.common.repository.IAssetRepository;
import com.dlap.common.repository.ILabelRepository;
import com.dlap.common.repository.IUserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

@Service
@Transactional
public class AssetService implements IAssetService {
    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    /** First day the usage store can hold; the lower bound when no incarnation boundary is known. */
    private static final LocalDate USAGE_EPOCH = LocalDate.of(1970, 1, 1);

    /** Last day of the usage query, far past any event time and inside what PostgreSQL stores. */
    private static final LocalDate USAGE_HORIZON = LocalDate.of(9999, 12, 31);

    /** Grouped (value, count) queries behind the catalog filter facets. Package-private for the parse test. */
    static final String FACET_ASSET_TYPE_JPQL = "SELECT a.assetType, COUNT(a) FROM Asset a "
            + "WHERE a.assetType IS NOT NULL GROUP BY a.assetType ORDER BY a.assetType";
    static final String FACET_CLOUD_PROVIDER_JPQL = "SELECT a.cloudProvider, COUNT(a) FROM Asset a "
            + "WHERE a.cloudProvider IS NOT NULL GROUP BY a.cloudProvider ORDER BY a.cloudProvider";
    static final String FACET_LABEL_JPQL = "SELECT l.labelName, COUNT(DISTINCT ala.assetId) "
            + "FROM AssetLabelAssignment ala JOIN ala.label l GROUP BY l.labelName ORDER BY l.labelName";

    /** Page size used by {@code GET /assets} when the caller sends none (see AssetController). */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** Page size of the activity report for a caller that sends none or asks for an unpaged result. */
    private static final int DEFAULT_ACTIVITY_PAGE_SIZE = 50;

    /** Longest cursor accepted by the keyset path: the longest asset URI the column holds. */
    private static final int MAX_CURSOR_LENGTH = 2048;

    /** Most values accepted for one repeatable activity filter. */
    private static final int MAX_FILTER_VALUES = 20;

    /** Sort properties of the activity report (the property names of {@link Asset}). */
    static final Set<String> ACTIVITY_SORTABLE = Set.of(
            "assetUri", "assetName", "cloudProvider", "serviceType", "status", "discoveredAt", "cloudCreatedAt",
            "sizeBytes", "lastAccessedAt", "lastModifiedAt", "retentionStatus", "retentionExpiresAt");

    /** The sortable properties that can be unknown; an unknown value sorts last in either direction. */
    private static final Set<String> ACTIVITY_NULLABLE_SORT = Set.of(
            "assetName", "discoveredAt", "cloudCreatedAt", "sizeBytes", "lastAccessedAt", "lastModifiedAt",
            "retentionExpiresAt");

    /** Lifecycle statuses of the asset descriptor. */
    private static final Set<String> ASSET_STATUSES = Set.of("ACTIVE", "ARCHIVED", "PENDING_DELETION", "DELETED");

    /** Read-coverage filter values; {@code UNKNOWN} selects assets with no coverage recorded. */
    private static final Set<String> READ_COVERAGE_FILTER_VALUES = Set.of("FULL", "PARTIAL", "NONE", "UNKNOWN");

    /** An asset kind as spelled in {@code dlap_assets.service_type}. */
    private static final Pattern KIND_PATTERN = Pattern.compile("[A-Z0-9_]{1,50}");

    /**
     * Labels of a page of assets in one statement. Package-private for the parse test.
     */
    static final String ACTIVITY_LABELS_JPQL = "SELECT ala.assetId, l.labelName FROM AssetLabelAssignment ala "
            + "JOIN ala.label l WHERE ala.assetId IN :ids";

    /** Display names for cloud-provider facet values; unknown codes are shown as they are stored. */
    private static final Map<String, String> CLOUD_PROVIDER_DISPLAY_NAMES = Map.of(
            "AWS", "Amazon Web Services",
            "AZURE", "Microsoft Azure",
            "GCP", "Google Cloud Platform");

    private final IAssetRepository assetRepository;
    private final AssetMapper assetMapper;
    private final ILabelRepository labelRepository;
    private final IAssetLabelAssignmentRepository assetLabelAssignmentRepository;
    private final IUserRepository userRepository;
    /**
     * The topic asset-change events are published to. It is the constant da-policyengine's
     * {@code LifecycleEventStreamConsumer} subscribes to, not a setting, so the two cannot drift (Q-417).
     */
    static final String ASSET_CHANGE_TOPIC = com.dlap.common.constants.DlapConstants.KafkaTopic.ASSET_CHANGE_EVENT;

    private final AssetLineageRepository assetLineageRepository;
    private final LineageService lineageService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ExternalCatalogIntegration externalCatalog;
    private final Clock clock;
    private final CatalogIngestProperties ingestProperties;
    private final CanonicalUriIndex canonicalUriIndex;
    private final AssetUsageDailyStore usageStore;

    @Value("${dlap.kafka.topics.label-assignment-changed:dlap.asset.label.assignment.changed}")
    private String labelAssignmentChangedTopic;

    @Value("${spring.data.web.pageable.max-page-size:200}")
    private int maxPageSize = 200;

    /** Grouped facet counts for the catalog filters; injected by the container. */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Creates the service.
     *
     * @param assetRepository                assets
     * @param assetMapper                    entity to DTO mapping
     * @param labelRepository                labels
     * @param assetLabelAssignmentRepository label assignments
     * @param userRepository                 catalog users
     * @param assetLineageRepository         lineage edges
     * @param lineageService                 lineage graph builder
     * @param kafkaTemplate                  asset-change publisher
     * @param objectMapper                   JSON mapper
     * @param externalCatalog                optional external catalog mirror
     * @param clock                          the time source: it stamps operational times and the
     *                                       default report instant, never a business time
     * @param ingestProperties               the operator's default normalization hints
     * @param canonicalUriIndex              {@code normalize(stored asset_uri)} for rows stored in an older form
     * @param usageStore                     daily read and write counts
     */
    @Autowired
    public AssetService(IAssetRepository assetRepository, AssetMapper assetMapper, ILabelRepository labelRepository,
                        IAssetLabelAssignmentRepository assetLabelAssignmentRepository,
                        IUserRepository userRepository,
                        AssetLineageRepository assetLineageRepository,
                        LineageService lineageService,
                        KafkaTemplate<String, Object> kafkaTemplate,
                        ObjectMapper objectMapper,
                        ExternalCatalogIntegration externalCatalog,
                        Clock clock,
                        CatalogIngestProperties ingestProperties,
                        CanonicalUriIndex canonicalUriIndex,
                        AssetUsageDailyStore usageStore) {
        this.assetRepository = assetRepository;
        this.assetMapper = assetMapper;
        this.labelRepository = labelRepository;
        this.assetLabelAssignmentRepository = assetLabelAssignmentRepository;
        this.userRepository = userRepository;
        this.assetLineageRepository = assetLineageRepository;
        this.lineageService = lineageService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.externalCatalog = externalCatalog;
        this.clock = clock;
        this.ingestProperties = ingestProperties;
        this.canonicalUriIndex = canonicalUriIndex;
        this.usageStore = usageStore;
    }

    /**
     * Publish an asset-change event to both the legacy
     * {@code dlap.asset.change.event} topic (broad change stream
     * consumed by reporting/compliance) and, for upsert event types,
     * the dedicated {@code dlap.catalog.asset.upserted} topic
     * ({@link com.dlap.common.constants.DlapConstants.KafkaTopic#CATALOG_ASSET_UPSERTED})
     * - wired in M3 (R-P1-10 / M4 prerequisite). Auto-label
     * subscribes to the upsert topic to drive its labeling pipeline
     * without filtering broad change events.
     *
     * <p>Upsert eventTypes: {@code ASSET_CREATED}, {@code ASSET_UPDATED}.
     * Other event types (e.g. label-only changes) fan out only on the
     * legacy topic.
     */
    // Package-private so DiscoveryAssetUpsertService (the Kafka discovery→catalog
    // path) can publish the same ASSET_CREATED/ASSET_UPDATED event - otherwise
    // discovered assets never reach the CATALOG_ASSET_UPSERTED topic that
    // da-autolabel consumes, and Auto-Label never fires for them.
    void publishAssetChangeEvent(Asset asset, String eventType, String userId) {
        if (asset == null || asset.getAssetId() == null) {
            return;
        }
        try {
            AssetChangeEvent evt = new AssetChangeEvent();
            evt.setEventType(eventType);
            evt.setAssetId(asset.getAssetId().toString());
            evt.setTimestamp(clock.instant().toString());
            evt.setUserId(userId);
            evt.setSourceService("da-catalog");
            // R-P2-3 (v0.2.0 N0): typed snapshot replaces the prior
            // Map<String,Object> payload. Field set is unchanged.
            AssetSnapshot snapshot = AssetSnapshot.builder()
                    .assetName(asset.getAssetName())
                    .assetType(asset.getAssetType())
                    .cloudProvider(asset.getCloudProvider())
                    .region(asset.getRegion())
                    .nativeAssetId(asset.getNativeAssetId())
                    .detailsJson(asset.getDetailsJson() != null ? objectMapper.writeValueAsString(asset.getDetailsJson()) : null)
                    .businessMetadataJson(asset.getBusinessMetadataJson() != null ? objectMapper.writeValueAsString(asset.getBusinessMetadataJson()) : null)
                    .build();
            evt.setAssetSnapshot(snapshot);
            String json = objectMapper.writeValueAsString(evt);

            kafkaTemplate.send(ASSET_CHANGE_TOPIC, evt.getAssetId(), json);
            log.debug("Published {} for asset {} on topic {}", eventType, evt.getAssetId(), ASSET_CHANGE_TOPIC);

            // M3 / M4 prerequisite: parallel publish to the dedicated
            // upsert topic so auto-label can subscribe without filtering
            // a broad change stream. Only upsert event types fan out here.
            if ("ASSET_CREATED".equals(eventType) || "ASSET_UPDATED".equals(eventType)) {
                kafkaTemplate.send(
                        com.dlap.common.constants.DlapConstants.KafkaTopic.CATALOG_ASSET_UPSERTED,
                        evt.getAssetId(),
                        json);
                log.debug("Published {} for asset {} on upsert topic {}",
                        eventType, evt.getAssetId(),
                        com.dlap.common.constants.DlapConstants.KafkaTopic.CATALOG_ASSET_UPSERTED);

                // ADR-0029: mirror the asset into Unity Catalog when enabled.
                // Guarded by isEnabled() so the default no-op path adds no query
                // and no behavior change (the integration itself swallows errors).
                if (externalCatalog.isEnabled()) {
                    externalCatalog.registerAsset(asset, labelNamesForAsset(asset.getAssetId()));
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize asset change event for {}: {}", asset.getAssetId(), e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to publish asset change event for {}: {}", asset.getAssetId(), e.getMessage());
        }
    }

    /**
     * Resolve the label names currently assigned to an asset, for use as
     * external-catalog metadata (ADR-0029). Only invoked when an external
     * catalog is enabled, so it adds no query on the default path.
     */
    private List<String> labelNamesForAsset(UUID assetId) {
        if (assetId == null) {
            return List.of();
        }
        return assetLabelAssignmentRepository.findByAssetAssetId(assetId).stream()
                .map(a -> a.getLabel() != null ? a.getLabel().getLabelName() : null)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Publish a label assignment change event to Kafka so that downstream services
     * (policy engine, reporting, compliance) can react to labels being added/removed
     * from an asset.
     *
     * @param asset the asset whose label assignments changed
     * @param changeType one of {@code LABELS_ASSIGNED} or {@code LABEL_REMOVED}
     * @param userId id of the user who performed the change (nullable for system actions)
     * @param details structured details about which labels changed
     */
    private void publishLabelAssignmentChangeEvent(Asset asset, String changeType, String userId,
                                                   Map<String, String> details) {
        if (asset == null || asset.getAssetId() == null) {
            return;
        }
        try {
            AssetChangeEvent evt = new AssetChangeEvent();
            evt.setEventType(changeType);
            evt.setAssetId(asset.getAssetId().toString());
            evt.setTimestamp(clock.instant().toString());
            evt.setUserId(userId);
            evt.setSourceService("da-catalog");
            // R-P2-3 (v0.2.0 N0): split into typed asset snapshot +
            // labelDetails. Previously a single Map<String,Object>
            // commingled identity + label-change keys.
            AssetSnapshot snapshot = AssetSnapshot.builder()
                    .assetName(asset.getAssetName())
                    .assetType(asset.getAssetType())
                    .cloudProvider(asset.getCloudProvider())
                    .build();
            evt.setAssetSnapshot(snapshot);
            if (details != null) {
                evt.setLabelDetails(LabelAssignmentDetails.of(details));
            }
            String json = objectMapper.writeValueAsString(evt);
            kafkaTemplate.send(labelAssignmentChangedTopic, evt.getAssetId(), json);
            log.debug("Published {} for asset {}", changeType, evt.getAssetId());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize label assignment event for {}: {}", asset.getAssetId(), e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to publish label assignment event for {}: {}", asset.getAssetId(), e.getMessage());
        }
    }

    @Override
    public Page<AssetOutputDTO> getAllAssets(Pageable pageable, String cloudProvider, String assetType,
                                            String region, List<String> labelNames, String nameContains,
                                            UUID connectionId) {
        Specification<Asset> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(cloudProvider)) {
                predicates.add(criteriaBuilder.equal(root.get("cloudProvider"), cloudProvider));
            }
            if (StringUtils.hasText(assetType)) {
                predicates.add(criteriaBuilder.equal(root.get("assetType"), assetType));
            }
            if (StringUtils.hasText(region)) {
                predicates.add(criteriaBuilder.equal(root.get("region"), region));
            }
            if (StringUtils.hasText(nameContains)) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("assetName")),
                        "%" + nameContains.toLowerCase() + "%"));
            }
            if (connectionId != null) {
                predicates.add(criteriaBuilder.equal(root.get("cloudConnectionId"), connectionId));
            }

            if (labelNames != null && !labelNames.isEmpty()) {
                // 1. Find label IDs for the given label names
                List<UUID> labelIds = labelRepository.findLabelIdsByLabelNames(labelNames);

                if (labelIds.size() != labelNames.size()) {
                    // One or more label names provided do not exist in the database.
                    // This means no asset can have all these labels, so return an empty result.
                    // Or, throw an exception if this is considered a bad request.
                    // For now, effectively results in no assets matching.
                    predicates.add(criteriaBuilder.disjunction()); // Always false predicate
                } else if (!labelIds.isEmpty()){
                    // Create a subquery to find asset IDs that have all the specified labels.
                    Subquery<UUID> subquery = query.subquery(UUID.class);
                    Root<AssetLabelAssignment> alaRoot = subquery.from(AssetLabelAssignment.class);
                    subquery.select(alaRoot.get("asset").get("assetId"));
                    subquery.where(alaRoot.get("label").get("labelId").in(labelIds));
                    subquery.groupBy(alaRoot.get("asset").get("assetId"));
                    subquery.having(criteriaBuilder.equal(criteriaBuilder.count(alaRoot.get("label").get("labelId")), labelIds.size()));

                    predicates.add(root.get("assetId").in(subquery));
                }
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        Page<Asset> assetPage = assetRepository.findAll(spec, pageable);
        return assetPage.map(assetMapper::toAssetOutputDTO);
    }

    @Override
    public AssetOutputDTO getAssetById(UUID assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));
        return assetMapper.toAssetOutputDTO(asset);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs without a surrounding transaction on purpose: a lookup that misses may wait for the
     * canonical-URI index build, which needs its own connections, and a request that holds a
     * pooled connection while it waits could starve the build.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<UUID> resolveAssetIdByUri(String assetUri) {
        if (assetUri == null || assetUri.isBlank()) {
            return Optional.empty();
        }
        String raw = assetUri.trim();
        AssetIdParseResult parsed = AssetIdNormalizer.parse(raw, defaultHints());
        if (parsed instanceof AssetIdParseResult.Normalized normalized) {
            String canonical = normalized.canonical().uri();
            Optional<UUID> exact = assetRepository.findByAssetUri(canonical).map(Asset::getAssetId);
            if (exact.isPresent()) {
                return exact;
            }
            // A row stored in an older form: normalize(stored asset_uri), oldest row first.
            Optional<UUID> stored = canonicalUriIndex.candidates(canonical).stream().findFirst();
            if (stored.isEmpty() && (canonicalUriIndex.ensureBuilt() || canonicalUriIndex.catchUpIfDue())) {
                stored = canonicalUriIndex.candidates(canonical).stream().findFirst();
            }
            if (stored.isPresent() || raw.equals(canonical)) {
                return stored;
            }
            // The index may lag a row written as the caller spelled it.
            return assetRepository.findByAssetUri(raw).map(Asset::getAssetId);
        }
        if (raw.length() > AssetIdNormalizer.MAX_URI_LENGTH) {
            return Optional.empty();
        }
        // Not an identifier the normalizer knows: look the text up as written, nothing more.
        return assetRepository.findByAssetUri(raw).map(Asset::getAssetId);
    }

    /** The operator's default normalization context ({@code dlap.catalog.ingest.match.default-hints.*}). */
    private AssetIdHints defaultHints() {
        CatalogIngestProperties.DefaultHints defaults = ingestProperties.getMatch().getDefaultHints();
        return AssetIdHints.builder()
                .account(defaults.getAccount())
                .project(defaults.getProject())
                .metastoreId(defaults.getMetastoreId())
                .workspaceId(defaults.getWorkspaceId())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AssetActivityRowDTO> getAssetActivity(ActivityQuery query, Pageable pageable) {
        ActivityQuery q = query == null ? ActivityQuery.unfiltered() : query;
        Instant asOf = q.asOf() != null ? q.asOf() : clock.instant();
        ActivityFilters filters = activityFilters(q);

        List<Sort.Order> orders = activityOrders(pageable);
        Pageable page = activityPage(pageable);

        Specification<Asset> spec = (root, criteria, cb) -> {
            Predicate predicate = activityPredicate(filters, root, cb);
            applyActivityOrder(criteria, root, cb, orders);
            return predicate;
        };

        Page<Asset> assets = assetRepository.findAll(spec, page);
        Map<UUID, List<String>> labels = activityLabels(assets.getContent());
        return assets.map(asset -> assetMapper.toAssetActivityRowDTO(
                asset, labels.getOrDefault(asset.getAssetId(), List.of()), asOf));
    }

    @Override
    @Transactional(readOnly = true)
    public AssetActivityPageDTO getAssetActivityKeyset(ActivityQuery query, String after, int size) {
        ActivityQuery q = query == null ? ActivityQuery.unfiltered() : query;
        Instant asOf = q.asOf() != null ? q.asOf() : clock.instant();
        ActivityFilters filters = activityFilters(q);
        String cursor = after == null || after.isBlank() ? null : after;
        if (cursor != null && cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("after is not an asset URI: it is longer than " + MAX_CURSOR_LENGTH
                    + " characters");
        }
        int window = Math.max(1, Math.min(size, maxPageSize));
        List<Sort.Order> byUri = List.of(Sort.Order.asc("assetUri"));

        // The cursor is a value, so a row written during the walk cannot move another row across
        // a page boundary. The strict comparison and the unique assetUri make the walk exact.
        Specification<Asset> spec = (root, criteria, cb) -> {
            Predicate predicate = activityPredicate(filters, root, cb);
            if (cursor != null) {
                predicate = cb.and(predicate, cb.greaterThan(root.<String>get("assetUri"), cursor));
            }
            applyActivityOrder(criteria, root, cb, byUri);
            return predicate;
        };
        // One extra row tells whether another page follows, so no page-number arithmetic is needed.
        Slice<Asset> slice = assetRepository.findBy(spec, fluent -> fluent.slice(PageRequest.of(0, window)));
        long total = assetRepository.count(activityFilterOnly(filters));
        Map<UUID, List<String>> labels = activityLabels(slice.getContent());
        List<AssetActivityRowDTO> rows = slice.getContent().stream()
                .map(asset -> assetMapper.toAssetActivityRowDTO(
                        asset, labels.getOrDefault(asset.getAssetId(), List.of()), asOf))
                .toList();
        String next = slice.hasNext() && !rows.isEmpty() ? rows.get(rows.size() - 1).assetUri() : null;
        return new AssetActivityPageDTO(rows, PageRequest.of(0, window), total, next, cursor == null,
                slice.hasNext());
    }

    /** The validated, normalized filters of one activity request. */
    private record ActivityFilters(List<String> providers, List<String> kinds, List<String> statuses,
                                   List<RetentionStatus> retentionStatuses, List<String> readCoverages,
                                   ActivityQuery query) {
    }

    /** Validates every filter of the query; throws {@link IllegalArgumentException} for a bad value. */
    private ActivityFilters activityFilters(ActivityQuery q) {
        List<String> providers = checkedValues("provider", q.providers(), v -> AssetProvider.fromString(v)
                .map(AssetProvider::name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider '" + shown(v) + "'")));
        List<String> kinds = checkedValues("kind", q.kinds(), v -> {
            String kind = v.strip().toUpperCase(Locale.ROOT);
            if (!KIND_PATTERN.matcher(kind).matches()) {
                throw new IllegalArgumentException("Malformed kind '" + shown(v)
                        + "'; a kind is an upper-case name such as GCS_BUCKET");
            }
            return kind;
        });
        List<String> statuses = checkedValues("status", q.statuses(), v -> {
            String status = v.strip().toUpperCase(Locale.ROOT);
            if (!ASSET_STATUSES.contains(status)) {
                throw new IllegalArgumentException("Unknown status '" + shown(v) + "'; allowed: "
                        + new TreeSet<>(ASSET_STATUSES));
            }
            return status;
        });
        List<RetentionStatus> retentionStatuses = checkedValues("retentionStatus", q.retentionStatuses(), v -> {
            try {
                return RetentionStatus.valueOf(v.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown retentionStatus '" + shown(v) + "'; allowed: "
                        + java.util.Arrays.toString(RetentionStatus.values()));
            }
        });
        List<String> readCoverages = checkedValues("readCoverage", q.readCoverages(), v -> {
            String coverage = v.strip().toUpperCase(Locale.ROOT);
            if (!READ_COVERAGE_FILTER_VALUES.contains(coverage)) {
                throw new IllegalArgumentException("Unknown readCoverage '" + shown(v) + "'; allowed: "
                        + new TreeSet<>(READ_COVERAGE_FILTER_VALUES));
            }
            return coverage;
        });

        return new ActivityFilters(providers, kinds, statuses, retentionStatuses, readCoverages, q);
    }

    /** The filters alone, without an order, for the count query. */
    private static Specification<Asset> activityFilterOnly(ActivityFilters filters) {
        return (root, criteria, cb) -> activityPredicate(filters, root, cb);
    }

    /** The WHERE clause of the activity report, shared by the offset and the keyset path. */
    private static Predicate activityPredicate(ActivityFilters f, Root<Asset> root, CriteriaBuilder cb) {
        ActivityQuery q = f.query();
        List<Predicate> predicates = new ArrayList<>();
        if (!f.providers().isEmpty()) {
            predicates.add(root.get("cloudProvider").in(f.providers()));
        }
        if (!f.kinds().isEmpty()) {
            predicates.add(root.get("serviceType").in(f.kinds()));
        }
        if (!f.statuses().isEmpty()) {
            predicates.add(root.get("status").in(f.statuses()));
        }
        // A last read that is unknown is neither before nor after anything (ADR-0046).
        if (q.lastReadBefore() != null) {
            predicates.add(cb.lessThan(root.<Instant>get("lastAccessedAt"), q.lastReadBefore()));
        }
        if (q.lastReadAfter() != null) {
            predicates.add(cb.greaterThan(root.<Instant>get("lastAccessedAt"), q.lastReadAfter()));
        }
        if (q.lastReadKnown() != null) {
            predicates.add(q.lastReadKnown()
                    ? cb.isNotNull(root.get("lastAccessedAt"))
                    : cb.isNull(root.get("lastAccessedAt")));
        }
        if (!f.retentionStatuses().isEmpty()) {
            predicates.add(root.get("retentionStatus").in(f.retentionStatuses()));
        }
        if (q.legalHold() != null) {
            predicates.add(cb.equal(root.get("legalHold"), q.legalHold()));
        }
        if (!f.readCoverages().isEmpty()) {
            predicates.add(readCoveragePredicate(root, cb, f.readCoverages()));
        }
        return cb.and(predicates.toArray(new Predicate[0]));
    }

    /**
     * Matches the stored read coverage ({@code dlap_assets.read_coverage}, COV-01). {@code UNKNOWN}
     * matches the rows without one; the column holds only {@code FULL}, {@code PARTIAL} and
     * {@code NONE} (a CHECK constraint), so no case or blank handling is needed.
     */
    private static Predicate readCoveragePredicate(Root<Asset> root, CriteriaBuilder cb, List<String> coverages) {
        Expression<String> stored = root.get("readCoverage");
        List<Predicate> any = new ArrayList<>();
        List<String> known = coverages.stream().filter(c -> !"UNKNOWN".equals(c)).toList();
        if (!known.isEmpty()) {
            any.add(stored.in(known));
        }
        if (coverages.contains("UNKNOWN")) {
            any.add(cb.isNull(stored));
        }
        return cb.or(any.toArray(new Predicate[0]));
    }

    /**
     * Validated sort of the activity report: the caller's properties, then {@code assetUri} as the
     * unique tie-breaker so a page boundary never repeats or drops a row.
     */
    private static List<Sort.Order> activityOrders(Pageable pageable) {
        List<Sort.Order> orders = new ArrayList<>();
        if (pageable != null && pageable.getSort().isSorted()) {
            for (Sort.Order order : pageable.getSort()) {
                if (!ACTIVITY_SORTABLE.contains(order.getProperty())) {
                    throw new IllegalArgumentException("Cannot sort the activity report by '"
                            + shown(order.getProperty()) + "'; allowed: " + new TreeSet<>(ACTIVITY_SORTABLE));
                }
                if (orders.stream().noneMatch(o -> o.getProperty().equals(order.getProperty()))) {
                    orders.add(order);
                }
            }
        }
        if (orders.stream().noneMatch(o -> o.getProperty().equals("assetUri"))) {
            orders.add(Sort.Order.asc("assetUri"));
        }
        return orders;
    }

    /** The page window without a sort: ordering is applied inside the specification. */
    private Pageable activityPage(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_ACTIVITY_PAGE_SIZE);
        }
        int size = Math.max(1, Math.min(pageable.getPageSize(), maxPageSize));
        return PageRequest.of(pageable.getPageNumber(), size);
    }

    /**
     * Orders the page query. An unknown value sorts last in either direction, because
     * PostgreSQL would put it first in a descending sort and an unknown must never lead a report.
     * The count query takes no order.
     */
    private static void applyActivityOrder(CriteriaQuery<?> criteria, Root<Asset> root, CriteriaBuilder cb,
                                           List<Sort.Order> orders) {
        Class<?> resultType = criteria.getResultType();
        if (Long.class.equals(resultType) || long.class.equals(resultType)) {
            return;
        }
        List<jakarta.persistence.criteria.Order> jpaOrders = new ArrayList<>();
        for (Sort.Order order : orders) {
            Path<Object> path = root.get(order.getProperty());
            if (ACTIVITY_NULLABLE_SORT.contains(order.getProperty())) {
                jpaOrders.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(path), 1).otherwise(0)));
            }
            jpaOrders.add(order.isAscending() ? cb.asc(path) : cb.desc(path));
        }
        criteria.orderBy(jpaOrders);
    }

    /** Label names of a page of assets, sorted and distinct, in one query. */
    private Map<UUID, List<String>> activityLabels(List<Asset> assets) {
        if (assets.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = assets.stream().map(Asset::getAssetId).toList();
        List<Object[]> rows = entityManager.createQuery(ACTIVITY_LABELS_JPQL, Object[].class)
                .setParameter("ids", ids)
                .getResultList();
        Map<UUID, Set<String>> byAsset = new HashMap<>();
        for (Object[] row : rows) {
            byAsset.computeIfAbsent((UUID) row[0], k -> new TreeSet<>()).add((String) row[1]);
        }
        Map<UUID, List<String>> sorted = new HashMap<>();
        byAsset.forEach((id, names) -> sorted.put(id, List.copyOf(names)));
        return sorted;
    }

    /**
     * Checks and normalizes one repeatable filter: blanks are dropped, repeats collapse, the count
     * is bounded, and each value goes through {@code check}, which throws on a value it rejects.
     */
    private static <T> List<T> checkedValues(String name, List<String> values, Function<String, T> check) {
        Set<T> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            out.add(check.apply(value));
            if (out.size() > MAX_FILTER_VALUES) {
                throw new IllegalArgumentException("At most " + MAX_FILTER_VALUES + " values are accepted for " + name);
            }
        }
        return List.copyOf(out);
    }

    /** A caller's value as it appears in an error message: bounded, with control characters removed. */
    private static String shown(String value) {
        String text = value.length() > 40 ? value.substring(0, 40) + "..." : value;
        return text.replaceAll("\\p{Cntrl}", "?");
    }

    @Override
    public AssetOutputDTO createAsset(AssetInputDTO assetInputDTO, UUID creatorUserId) {
        // Check for existing asset with the same nativeAssetId
        assetRepository.findByNativeAssetId(assetInputDTO.getNativeAssetId()).ifPresent(existingAsset -> {
            throw new ConflictException("Asset with nativeAssetId '" + assetInputDTO.getNativeAssetId() + "' already exists.");
        });

        Asset asset = new Asset();
        // Map fields from DTO
        asset.setAssetName(assetInputDTO.getAssetName());
        asset.setCloudConnectionId(assetInputDTO.getCloudConnectionId());
        asset.setCloudProvider(assetInputDTO.getCloudProvider());
        asset.setAssetType(assetInputDTO.getAssetType());
        asset.setNativeAssetId(assetInputDTO.getNativeAssetId());
        asset.setRegion(assetInputDTO.getRegion());
        asset.setDetailsJson(assetInputDTO.getDetailsJson());

        // Set system-managed fields
        asset.setCreatedByUserId(creatorUserId);
        Instant now = clock.instant(); // DLAP processing time, from the injected clock
        asset.setDiscoveredAt(now); // Or a more specific discovery timestamp if available from source
        asset.setLastScannedAt(now); // Initially same as discovered
        // createdAt and updatedAt are handled by @PrePersist

        Asset savedAsset = assetRepository.save(asset);

        // Handle initial labels, if any
        List<String> labelNamesToApply = assetInputDTO.getLabels();
        if (labelNamesToApply != null && !labelNamesToApply.isEmpty()) {
            List<Label> labels = labelRepository.findByLabelNameIn(labelNamesToApply);
            if (labels.size() != labelNamesToApply.size()) {
                List<String> foundLabelNames = labels.stream().map(Label::getLabelName).collect(Collectors.toList());
                List<String> missingLabelNames = labelNamesToApply.stream()
                                                                .filter(name -> !foundLabelNames.contains(name))
                                                                .collect(Collectors.toList());
                if (!missingLabelNames.isEmpty()) {
                     log.warn("The following labels were not found and will not be applied: {}", missingLabelNames);
                }
            }
            for (Label label : labels) {
                AssetLabelAssignment assignment = new AssetLabelAssignment();
                assignment.setAsset(savedAsset);
                assignment.setLabel(label);
                // Creation-time initial labels are a system import, not a human USER action.
                // Raw JWT subject must never be written to the dlap_users FK (see resolveAssignerUserId).
                assignment.setAssignedByUserId(null);
                assignment.setSource(DlapConstants.AssignmentSource.SYSTEM);
                assetLabelAssignmentRepository.save(assignment);
            }
        }

        publishAssetChangeEvent(savedAsset, "ASSET_CREATED", creatorUserId != null ? creatorUserId.toString() : null);

        return assetMapper.toAssetOutputDTO(savedAsset);
    }

    @Override
    public AssetOutputDTO updateBusinessMetadata(UUID assetId, Map<String, Object> businessMetadata, UUID updaterUserId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        asset.setBusinessMetadataJson(businessMetadata);
        Asset updatedAsset = assetRepository.save(asset);

        publishAssetChangeEvent(updatedAsset, "ASSET_UPDATED", updaterUserId != null ? updaterUserId.toString() : null);

        return assetMapper.toAssetOutputDTO(updatedAsset);
    }

    @Override
    public BusinessMetadataResponseDTO updateBusinessContext(UUID assetId, BusinessMetadataInputDTO businessMetadata, UUID updaterUserId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        // Map the enhanced business context input into the asset's JSONB storage
        // format and persist it (mirrors updateBusinessMetadata).
        asset.setBusinessMetadataJson(convertToStorageFormat(businessMetadata));
        Asset updatedAsset = assetRepository.save(asset);

        publishAssetChangeEvent(updatedAsset, "ASSET_UPDATED",
                updaterUserId != null ? updaterUserId.toString() : null);

        return generateBusinessMetadataResponse(updatedAsset, businessMetadata, updaterUserId);
    }

    @Override
    public BusinessMetadataResponseDTO getBusinessMetadata(UUID assetId) {
        log.info("Retrieving business metadata for Asset ID: {}", assetId);
        
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        return generateBusinessMetadataResponse(asset, null, null);
    }

    @Override
    public LineageResponseDTO getAssetLineage(UUID assetId) {
        // First, check if the main asset exists
        if (!assetRepository.existsById(assetId)) {
            throw new ResourceNotFoundException("Asset", "id", assetId.toString());
        }

        List<AssetLineage> upstreamLineageRecords = assetLineageRepository.findByDownstreamAsset_AssetId(assetId);
        List<AssetLineage> downstreamLineageRecords = assetLineageRepository.findByUpstreamAsset_AssetId(assetId);

        List<LineageAssetDTO> upstreamAssets = upstreamLineageRecords.stream()
                .map(AssetLineage::getUpstreamAsset)
                .map(asset -> new LineageAssetDTO(
                        asset.getAssetId(),
                        asset.getAssetName(),
                        asset.getAssetType(),
                        asset.getCloudProvider()
                        // TODO: consider adding relationshipType from AssetLineage record to DTO if needed
                ))
                .collect(Collectors.toList());

        List<LineageAssetDTO> downstreamAssets = downstreamLineageRecords.stream()
                .map(AssetLineage::getDownstreamAsset)
                .map(asset -> new LineageAssetDTO(
                        asset.getAssetId(),
                        asset.getAssetName(),
                        asset.getAssetType(),
                        asset.getCloudProvider()
                        // TODO: consider adding relationshipType from AssetLineage record to DTO if needed
                ))
                .collect(Collectors.toList());

        return new LineageResponseDTO(upstreamAssets, downstreamAssets);
    }

    @Override
    public List<LabelOutputDTO> getAssetLabels(UUID assetId) {
        if (!assetRepository.existsById(assetId)) {
            throw new ResourceNotFoundException("Asset", "id", assetId.toString());
        }

        List<AssetLabelAssignment> assignments = assetLabelAssignmentRepository.findByAssetAssetId(assetId);

        return assignments.stream().map(assignment -> {
            LabelOutputDTO dto = new LabelOutputDTO();
            Label label = assignment.getLabel(); // Get the Label entity

            // Populate from Label entity
            dto.setLabelId(label.getLabelId());
            dto.setLabelName(label.getLabelName());
            dto.setLabelCategory(label.getLabelCategory());
            dto.setDescription(label.getDescription());

            // Populate from AssetLabelAssignment entity
            dto.setAssignedByUserId(assignment.getAssignedByUserId());
            dto.setAssignedAt(assignment.getAssignedAt());
            dto.setConfidenceScore(assignment.getConfidenceScore());
            dto.setSource(assignment.getSource());

            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public List<LabelOutputDTO> assignLabelsToAsset(UUID assetId, AssetLabelsPostRequestDTO labelsToAssignRequest, UUID assignerUserId) {
        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        List<AssetLabelAssignment> newAssignments = new ArrayList<>();
        List<String> successfullyAssignedLabelNames = new ArrayList<>();

        // The controller passes the JWT subject as assignerUserId, but the
        // dlap_asset_label_assignments.assigned_by_user_id FK references
        // dlap_users.user_id - a SEPARATE column from keycloak_subject_id. Resolve
        // the subject to a real catalog user_id; when there is no matching row
        // (e.g. a service-account / admin subject), fall back to null (the FK
        // column is nullable) instead of writing an id that violates the FK.
        UUID resolvedAssignerUserId = resolveAssignerUserId(assignerUserId);

        for (LabelAssignmentInputDTO labelInput : labelsToAssignRequest.getLabels()) {
            Label label = labelRepository.findByLabelName(labelInput.getLabelName())
                .orElseGet(() -> {
                    // Auto-labeling (and other producers) legitimately introduce new label
                    // names. Create the label on first use instead of 500-ing the whole
                    // assignment. Category defaults to "AUTO" (the input DTO carries none);
                    // timestamps are set by the Label @PrePersist.
                    // Category must satisfy ck_label_category_valid; default new
                    // auto-labeled names to Classification. The description marks them
                    // as auto-created.
                    Label created = new Label(labelInput.getLabelName(), "Classification",
                            "Auto-created on first assignment");
                    return labelRepository.save(created);
                });

            // if (label == null) continue; // Skip if label not found and we chose to log/warn

            AssetLabelAssignment assignment = assetLabelAssignmentRepository.findByAssetAndLabel(asset, label)
                .orElse(new AssetLabelAssignment()); // Create new if not exists

            assignment.setAsset(asset);
            assignment.setLabel(label);
            String assignmentSource = StringUtils.hasText(labelInput.getSource())
                    ? labelInput.getSource() : "USER";
            // Only USER-sourced assignments carry a human assigner; system / ML /
            // policy sources set null (matching the seed convention). The USER
            // value is the resolved catalog user_id (or null when the subject is
            // not a catalog user), never the raw JWT subject - so the dlap_users
            // FK holds in every case.
            assignment.setAssignedByUserId("USER".equals(assignmentSource) ? resolvedAssignerUserId : null);
            assignment.setSource(assignmentSource);
            assignment.setConfidenceScore(labelInput.getConfidenceScore());
            // assignedAt is handled by @PrePersist or @PreUpdate

            newAssignments.add(assetLabelAssignmentRepository.save(assignment));
            successfullyAssignedLabelNames.add(label.getLabelName());
        }
        
        if (!successfullyAssignedLabelNames.isEmpty()) {
            Map<String, String> details = Map.of(
                "labels_assigned", String.join(",", successfullyAssignedLabelNames),
                "updated_section", "labels"
            );
            publishLabelAssignmentChangeEvent(asset, "LABELS_ASSIGNED",
                    assignerUserId != null ? assignerUserId.toString() : null, details);
        }

        // Return the current state of labels for the asset
        return getAssetLabels(assetId);
    }

    /**
     * Resolve the caller's identity to a persisted {@code dlap_users.user_id}
     * suitable for the {@code fk_assignment_user} foreign key.
     * <p>
     * The controller supplies {@link SecurityUtils#getAuthenticatedUserId()},
     * which is the JWT {@code sub} claim. That subject matches
     * {@code dlap_users.keycloak_subject_id}, NOT {@code dlap_users.user_id}, so
     * it cannot be written to the FK column directly. This looks up the catalog
     * user by subject and returns its {@code user_id}; if no catalog user exists
     * for the subject (service accounts, the synthetic system user, or a human
     * not yet provisioned in {@code dlap_users}) it returns {@code null}, which
     * the nullable FK accepts.
     *
     * @param authenticatedSubject the JWT subject supplied by the controller; may be {@code null}
     * @return the resolved catalog {@code user_id}, or {@code null} when unresolvable
     */
    private UUID resolveAssignerUserId(UUID authenticatedSubject) {
        if (authenticatedSubject == null) {
            return null;
        }
        // The subject is carried as a UUID by SecurityUtils but stored as a String
        // in dlap_users.keycloak_subject_id; match on its canonical string form.
        return userRepository.findByKeycloakSubjectId(authenticatedSubject.toString())
                .map(com.dlap.common.model.entity.User::getUserId)
                .orElse(null);
    }

    @Override
    public void removeLabelFromAsset(UUID assetId, String labelName, UUID removerUserId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        Label label = labelRepository.findByLabelName(labelName)
                .orElseThrow(() -> new ResourceNotFoundException("Label", "name", labelName));

        AssetLabelAssignment assignment = assetLabelAssignmentRepository.findByAssetAndLabel(asset, label)
                .orElseThrow(() -> new ResourceNotFoundException("AssetLabelAssignment", "assetId=" + assetId + ",labelName=" + labelName, ""));
        
        assetLabelAssignmentRepository.delete(assignment);
        log.info("Label '{}' removed from asset '{}' by user '{}'", labelName, assetId, removerUserId);

        Map<String, String> details = Map.of(
            "label_removed", labelName,
            "updated_section", "labels"
        );
        publishLabelAssignmentChangeEvent(asset, "LABEL_REMOVED",
                removerUserId != null ? removerUserId.toString() : null, details);
    }
    
    @Override
    public AssetUsageAnalyticsDTO getAssetUsageAnalytics(UUID assetId) {
        log.info("Reading stored usage signals for asset: {}", assetId);

        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        // The usage data the catalog stores is the last-read time on the asset and the daily read
        // counts (asset_usage_daily), both written from successful events by event time
        // (ADR-0046, ADR-0055). The legacy dlap_usage_metrics bag is no longer read. Everything
        // else (unique users, hourly or monthly distribution, query performance, popular queries)
        // has no data source yet, so it stays null rather than being estimated.
        Long totalAccesses = recordedReads(asset);
        Instant lastAccessed = asset.getLastAccessedAt();
        boolean available = totalAccesses != null || lastAccessed != null;

        AssetUsageAnalyticsDTO analytics = new AssetUsageAnalyticsDTO();
        analytics.setAvailable(available);
        analytics.setBasis(available
                ? "Reads recorded by the catalog from successful access events, counted by event day, and "
                        + "the time of the latest one. A missing value means no read was recorded, not that "
                        + "none happened: that needs read-logging coverage. "
                        + "Per-user, per-hour and performance data are not collected."
                : "No read has been recorded for this asset yet. That does not prove it was not read.");

        AssetUsageAnalyticsDTO.UsageMetricsDTO usageMetrics = new AssetUsageAnalyticsDTO.UsageMetricsDTO();
        usageMetrics.setTotalAccesses(totalAccesses);
        usageMetrics.setLastAccessed(lastAccessed);
        analytics.setUsageMetrics(usageMetrics);
        analytics.setUserAnalytics(new AssetUsageAnalyticsDTO.UserAnalyticsDTO());
        analytics.setAccessPatterns(new AssetUsageAnalyticsDTO.AccessPatternsDTO());
        analytics.setPerformanceMetrics(new AssetUsageAnalyticsDTO.PerformanceMetricsDTO());
        analytics.setRecommendations(createRecommendations(asset));

        log.info("Usage analytics for asset {}: available={}, recommendations={}",
                assetId, available, analytics.getRecommendations().size());
        return analytics;
    }

    /**
     * Builds recommendations from stored asset data only. The single rule that remains
     * is the missing-business-metadata check; rules that depended on estimated traffic,
     * timeouts or query patterns were removed with the estimates.
     */
    private List<AssetUsageAnalyticsDTO.RecommendationDTO> createRecommendations(Asset asset) {
        List<AssetUsageAnalyticsDTO.RecommendationDTO> recommendations = new ArrayList<>();
        if (asset.getBusinessMetadataJson() == null || asset.getBusinessMetadataJson().isEmpty()) {
            recommendations.add(new AssetUsageAnalyticsDTO.RecommendationDTO(
                "COMPLIANCE", "HIGH",
                "Missing business metadata. Add data steward and business context information.",
                "Improved data governance and compliance"));
        }
        return recommendations;
    }

    /**
     * Reads recorded for the current incarnation of an asset: the sum of {@code asset_usage_daily}
     * from the day of {@code recreatedAt} (the whole history when the asset was never re-created).
     * The store counts by UTC day, so a read earlier on the re-creation day is included.
     *
     * @return the count, or {@code null} when no read is recorded (unknown, not zero)
     */
    private Long recordedReads(Asset asset) {
        LocalDate from = asset.getRecreatedAt() == null
                ? USAGE_EPOCH : AssetUsageDailyStore.dayOf(asset.getRecreatedAt());
        long reads = usageStore.findDays(asset.getAssetId(), from, USAGE_HORIZON).stream()
                .mapToLong(UsageDay::reads)
                .sum();
        return reads > 0 ? reads : null;
    }

    /**
     * Convert BusinessMetadataInputDTO to storage format (Map<String, Object>)
     */
    private Map<String, Object> convertToStorageFormat(BusinessMetadataInputDTO input) {
        Map<String, Object> storage = new HashMap<>();
        
        if (input.getBusinessMetadata() != null) {
            BusinessMetadataInputDTO.BusinessMetadataDTO bm = input.getBusinessMetadata();
            
            storage.put("businessName", bm.getBusinessName());
            storage.put("businessDescription", bm.getBusinessDescription());
            storage.put("businessCriticality", bm.getBusinessCriticality());
            storage.put("businessDomain", bm.getBusinessDomain());
            storage.put("useCases", bm.getUseCases());
            storage.put("dataQualityScore", bm.getDataQualityScore());
            storage.put("updateFrequency", bm.getUpdateFrequency());
            
            // Business Owner
            if (bm.getBusinessOwner() != null) {
                Map<String, Object> owner = new HashMap<>();
                owner.put("userId", bm.getBusinessOwner().getUserId());
                owner.put("name", bm.getBusinessOwner().getName());
                owner.put("email", bm.getBusinessOwner().getEmail());
                owner.put("department", bm.getBusinessOwner().getDepartment());
                owner.put("role", bm.getBusinessOwner().getRole());
                owner.put("phone", bm.getBusinessOwner().getPhone());
                storage.put("businessOwner", owner);
            }
            
            // Data Steward
            if (bm.getDataSteward() != null) {
                Map<String, Object> steward = new HashMap<>();
                steward.put("userId", bm.getDataSteward().getUserId());
                steward.put("name", bm.getDataSteward().getName());
                steward.put("email", bm.getDataSteward().getEmail());
                steward.put("department", bm.getDataSteward().getDepartment());
                steward.put("role", bm.getDataSteward().getRole());
                steward.put("phone", bm.getDataSteward().getPhone());
                storage.put("dataSteward", steward);
            }
            
            // Retention Requirements
            if (bm.getRetentionRequirements() != null) {
                Map<String, Object> retention = new HashMap<>();
                retention.put("legalRetention", bm.getRetentionRequirements().getLegalRetention());
                retention.put("businessRetention", bm.getRetentionRequirements().getBusinessRetention());
                retention.put("archiveAfter", bm.getRetentionRequirements().getArchiveAfter());
                retention.put("deleteAfter", bm.getRetentionRequirements().getDeleteAfter());
                retention.put("retentionReasons", bm.getRetentionRequirements().getRetentionReasons());
                storage.put("retentionRequirements", retention);
            }
        }
        
        if (input.getComplianceClassification() != null) {
            BusinessMetadataInputDTO.ComplianceClassificationDTO cc = input.getComplianceClassification();
            
            Map<String, Object> compliance = new HashMap<>();
            compliance.put("dataClassification", cc.getDataClassification());
            compliance.put("containsPii", cc.getContainsPii());
            compliance.put("containsPhi", cc.getContainsPhi());
            compliance.put("containsFinancial", cc.getContainsFinancial());
            compliance.put("gdprApplicable", cc.getGdprApplicable());
            compliance.put("soxRelevant", cc.getSoxRelevant());
            compliance.put("hipaaRelevant", cc.getHipaaRelevant());
            compliance.put("pciRelevant", cc.getPciRelevant());
            compliance.put("regulatoryFrameworks", cc.getRegulatoryFrameworks());
            compliance.put("dataSubjectCategories", cc.getDataSubjectCategories());
            compliance.put("privacyImpactLevel", cc.getPrivacyImpactLevel());
            storage.put("complianceClassification", compliance);
        }
        
        return storage;
    }

    /**
     * Generate comprehensive business metadata response with validation and recommendations
     */
    private BusinessMetadataResponseDTO generateBusinessMetadataResponse(Asset asset, BusinessMetadataInputDTO input, UUID updaterUserId) {
        BusinessMetadataResponseDTO response = new BusinessMetadataResponseDTO();
        
        // Convert storage format back to DTOs
        if (asset.getBusinessMetadataJson() != null) {
            response.setBusinessMetadata(convertStorageToBusinessMetadataDTO(asset.getBusinessMetadataJson()));
            response.setComplianceClassification(convertStorageToComplianceDTO(asset.getBusinessMetadataJson()));
        }
        
        // Generate audit information
        response.setAuditInfo(generateAuditInfo(asset, updaterUserId));
        
        // Generate validation status
        response.setValidationStatus(generateValidationStatus(asset.getBusinessMetadataJson()));
        
        // Generate recommendations
        response.setRecommendations(generateMetadataRecommendations(asset));
        
        return response;
    }

    /**
     * Convert storage format to BusinessMetadataDTO
     */
    @SuppressWarnings("unchecked")
    private BusinessMetadataResponseDTO.BusinessMetadataDTO convertStorageToBusinessMetadataDTO(Map<String, Object> storage) {
        BusinessMetadataResponseDTO.BusinessMetadataDTO dto = new BusinessMetadataResponseDTO.BusinessMetadataDTO();
        
        dto.setBusinessName((String) storage.get("businessName"));
        dto.setBusinessDescription((String) storage.get("businessDescription"));
        dto.setBusinessCriticality((String) storage.get("businessCriticality"));
        dto.setBusinessDomain((String) storage.get("businessDomain"));
        dto.setUseCases(asStringList(storage.get("useCases")));
        
        Object qualityScore = storage.get("dataQualityScore");
        if (qualityScore instanceof Number) {
            dto.setDataQualityScore(((Number) qualityScore).doubleValue());
        }
        
        dto.setUpdateFrequency((String) storage.get("updateFrequency"));
        
        // Convert business owner
        Map<String, Object> ownerMap = asMap(storage.get("businessOwner"));
        if (ownerMap != null) {
            BusinessMetadataInputDTO.BusinessContactDTO owner = new BusinessMetadataInputDTO.BusinessContactDTO();
            owner.setUserId(parseUuidOrNull(ownerMap.get("userId"), "businessOwner.userId"));
            owner.setName((String) ownerMap.get("name"));
            owner.setEmail((String) ownerMap.get("email"));
            owner.setDepartment((String) ownerMap.get("department"));
            owner.setRole((String) ownerMap.get("role"));
            owner.setPhone((String) ownerMap.get("phone"));
            dto.setBusinessOwner(owner);
        }

        // Convert data steward
        Map<String, Object> stewardMap = asMap(storage.get("dataSteward"));
        if (stewardMap != null) {
            BusinessMetadataInputDTO.BusinessContactDTO steward = new BusinessMetadataInputDTO.BusinessContactDTO();
            steward.setUserId(parseUuidOrNull(stewardMap.get("userId"), "dataSteward.userId"));
            steward.setName((String) stewardMap.get("name"));
            steward.setEmail((String) stewardMap.get("email"));
            steward.setDepartment((String) stewardMap.get("department"));
            steward.setRole((String) stewardMap.get("role"));
            steward.setPhone((String) stewardMap.get("phone"));
            dto.setDataSteward(steward);
        }

        // Convert retention requirements
        Map<String, Object> retentionMap = asMap(storage.get("retentionRequirements"));
        if (retentionMap != null) {
            BusinessMetadataInputDTO.RetentionRequirementsDTO retention = new BusinessMetadataInputDTO.RetentionRequirementsDTO();
            retention.setLegalRetention((String) retentionMap.get("legalRetention"));
            retention.setBusinessRetention((String) retentionMap.get("businessRetention"));
            retention.setArchiveAfter((String) retentionMap.get("archiveAfter"));
            retention.setDeleteAfter((String) retentionMap.get("deleteAfter"));
            retention.setRetentionReasons(asStringList(retentionMap.get("retentionReasons")));
            dto.setRetentionRequirements(retention);
        }
        
        return dto;
    }

    /**
     * Convert storage format to ComplianceClassificationDTO.
     * All JSON value casts are defensive: invalid types are skipped with a WARN log
     * rather than propagating a {@link ClassCastException} out of the request.
     */
    private BusinessMetadataResponseDTO.ComplianceClassificationDTO convertStorageToComplianceDTO(Map<String, Object> storage) {
        Map<String, Object> complianceMap = asMap(storage.get("complianceClassification"));
        if (complianceMap == null) {
            return null;
        }

        BusinessMetadataResponseDTO.ComplianceClassificationDTO dto = new BusinessMetadataResponseDTO.ComplianceClassificationDTO();

        dto.setDataClassification((String) complianceMap.get("dataClassification"));
        dto.setContainsPii(asBoolean(complianceMap.get("containsPii"), "containsPii"));
        dto.setContainsPhi(asBoolean(complianceMap.get("containsPhi"), "containsPhi"));
        dto.setContainsFinancial(asBoolean(complianceMap.get("containsFinancial"), "containsFinancial"));
        dto.setGdprApplicable(asBoolean(complianceMap.get("gdprApplicable"), "gdprApplicable"));
        dto.setSoxRelevant(asBoolean(complianceMap.get("soxRelevant"), "soxRelevant"));
        dto.setHipaaRelevant(asBoolean(complianceMap.get("hipaaRelevant"), "hipaaRelevant"));
        dto.setPciRelevant(asBoolean(complianceMap.get("pciRelevant"), "pciRelevant"));
        dto.setRegulatoryFrameworks(asStringList(complianceMap.get("regulatoryFrameworks")));
        dto.setDataSubjectCategories(asStringList(complianceMap.get("dataSubjectCategories")));
        dto.setPrivacyImpactLevel((String) complianceMap.get("privacyImpactLevel"));

        return dto;
    }

    /**
     * Defensive Map cast: returns the value as a {@code Map<String, Object>} if it
     * actually is a Map, otherwise returns {@code null} and logs a warning. Used to
     * protect the service against malformed JSON payloads in the business metadata
     * JSONB column.
     */
    private Map<String, Object> asMap(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() instanceof String k) {
                    result.put(k, entry.getValue());
                }
            }
            return result;
        }
        log.warn("Expected a Map but got {}; ignoring value", value.getClass().getName());
        return null;
    }

    /**
     * Defensive List&lt;String&gt; cast: returns a filtered string list, or {@code null}
     * if the value is absent / wrong type. Logs a warning on type mismatch.
     */
    private List<String> asStringList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> raw) {
            List<String> result = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item instanceof String s) {
                    result.add(s);
                } else if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        log.warn("Expected a List but got {}; ignoring value", value.getClass().getName());
        return null;
    }

    /**
     * Defensive Boolean cast: returns the value if it is a Boolean, otherwise
     * {@code null}. Logs a warning on type mismatch.
     */
    private Boolean asBoolean(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        log.warn("Expected Boolean for field '{}' but got {}; ignoring value", fieldName, value.getClass().getName());
        return null;
    }

    /**
     * Defensive UUID parser: returns the parsed UUID, or {@code null} if the input
     * is missing, not a String, or not a valid UUID. Logs a warning on invalid input.
     */
    private UUID parseUuidOrNull(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            log.warn("Expected String for UUID field '{}' but got {}; ignoring value",
                    fieldName, value.getClass().getName());
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID value for field '{}': {}", fieldName, s);
            return null;
        }
    }

    /**
     * Generate audit information
     */
    private BusinessMetadataResponseDTO.MetadataAuditDTO generateAuditInfo(Asset asset, UUID updaterUserId) {
        BusinessMetadataResponseDTO.MetadataAuditDTO audit = new BusinessMetadataResponseDTO.MetadataAuditDTO();
        
        Map<String, Object> metadata = asset.getBusinessMetadataJson();
        if (metadata != null) {
            // Last updated info
            UUID lastUpdatedBy = parseUuidOrNull(metadata.get("lastUpdatedBy"), "lastUpdatedBy");
            if (lastUpdatedBy != null) {
                audit.setLastUpdatedBy(lastUpdatedBy);
                audit.setLastUpdatedByName(resolveUserDisplayName(lastUpdatedBy));
            }

            Object lastUpdatedAtObj = metadata.get("lastUpdatedAt");
            if (lastUpdatedAtObj instanceof String lastUpdatedAtStr) {
                try {
                    audit.setLastUpdatedAt(Instant.parse(lastUpdatedAtStr));
                } catch (java.time.format.DateTimeParseException e) {
                    log.warn("Invalid lastUpdatedAt timestamp '{}' on asset {}",
                            lastUpdatedAtStr, asset.getAssetId());
                }
            }
            
            audit.setUpdateReason((String) metadata.get("updateReason"));
            
            Object versionObj = metadata.get("versionNumber");
            if (versionObj instanceof Number) {
                audit.setVersionNumber(((Number) versionObj).intValue());
            }
        }
        
        // Created info from asset
        audit.setCreatedBy(asset.getCreatedByUserId());
        audit.setCreatedByName(resolveUserDisplayName(asset.getCreatedByUserId()));
        audit.setCreatedAt(asset.getCreatedTs());
        
        // Generate change history
        audit.setChangeHistory(generateChangeHistory(asset));
        
        return audit;
    }

    /**
     * Looks up the stored full name for a user id (catalog user id first, then Keycloak subject).
     * Returns {@code null} when no catalog user matches, never a placeholder name.
     */
    private String resolveUserDisplayName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .or(() -> userRepository.findByKeycloakSubjectId(userId.toString()))
                .map(com.dlap.common.model.entity.User::getFullName)
                .filter(StringUtils::hasText)
                .orElse(null);
    }

    /**
     * Generate validation status for metadata completeness
     */
    private BusinessMetadataResponseDTO.ValidationStatusDTO generateValidationStatus(Map<String, Object> metadata) {
        BusinessMetadataResponseDTO.ValidationStatusDTO validation = new BusinessMetadataResponseDTO.ValidationStatusDTO();
        
        List<String> missingFields = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        final int totalFields = 7; // exactly the fields checked below
        int presentFields = 0;
        
        if (metadata == null || metadata.isEmpty()) {
            validation.setIsComplete(false);
            validation.setCompletenessScore(0.0);
            validation.setOverallStatus("INCOMPLETE");
            missingFields.add("All business metadata fields are missing");
            validation.setMissingFields(missingFields);
            validation.setValidationWarnings(warnings);
            validation.setValidationErrors(errors);
            return validation;
        }
        
        // Check core business metadata
        if (metadata.get("businessName") != null) presentFields++;
        else missingFields.add("businessName");
        
        if (metadata.get("businessDescription") != null) presentFields++;
        else missingFields.add("businessDescription");
        
        if (metadata.get("businessCriticality") != null) presentFields++;
        else missingFields.add("businessCriticality");
        
        if (metadata.get("businessDomain") != null) presentFields++;
        else missingFields.add("businessDomain");
        
        // Check steward relationships
        Map<String, Object> owner = asMap(metadata.get("businessOwner"));
        if (owner != null && owner.get("name") != null && owner.get("email") != null) {
            presentFields++;
        } else {
            missingFields.add("businessOwner");
        }

        Map<String, Object> steward = asMap(metadata.get("dataSteward"));
        if (steward != null && steward.get("name") != null && steward.get("email") != null) {
            presentFields++;
        } else {
            missingFields.add("dataSteward");
        }
        
        // Check compliance classification
        Map<String, Object> compliance = asMap(metadata.get("complianceClassification"));
        if (compliance != null && compliance.get("dataClassification") != null) {
            presentFields++;
        } else {
            missingFields.add("complianceClassification");
        }
        
        // Calculate completeness
        double completenessScore = (double) presentFields / totalFields;
        validation.setCompletenessScore(completenessScore);
        validation.setIsComplete(completenessScore >= 0.8);
        
        // Determine overall status
        if (completenessScore >= 0.9) {
            validation.setOverallStatus("COMPLETE");
        } else if (completenessScore >= 0.6) {
            validation.setOverallStatus("PARTIAL");
        } else {
            validation.setOverallStatus("INCOMPLETE");
        }
        
        // Add warnings for low-quality data
        Object qualityScore = metadata.get("dataQualityScore");
        if (qualityScore instanceof Number && ((Number) qualityScore).doubleValue() < 5.0) {
            warnings.add("Data quality score is below recommended threshold (5.0)");
        }
        
        validation.setMissingFields(missingFields);
        validation.setValidationWarnings(warnings);
        validation.setValidationErrors(errors);
        
        return validation;
    }

    /**
     * Generate metadata recommendations
     */
    private List<BusinessMetadataResponseDTO.RecommendationDTO> generateMetadataRecommendations(Asset asset) {
        List<BusinessMetadataResponseDTO.RecommendationDTO> recommendations = new ArrayList<>();
        
        Map<String, Object> metadata = asset.getBusinessMetadataJson();
        
        if (metadata == null || metadata.isEmpty()) {
            recommendations.add(new BusinessMetadataResponseDTO.RecommendationDTO(
                "COMPLETENESS", "HIGH", "Missing Business Metadata",
                "This asset has no business metadata defined. Adding business context will improve governance and discoverability.",
                "Add business owner, data steward, business description, and compliance classification",
                "Improved data governance, compliance reporting, and asset discoverability"
            ));
            return recommendations;
        }
        
        // Check for missing steward
        if (metadata.get("dataSteward") == null) {
            recommendations.add(new BusinessMetadataResponseDTO.RecommendationDTO(
                "GOVERNANCE", "HIGH", "Missing Data Steward",
                "No data steward assigned to this asset. A data steward is responsible for data quality and governance.",
                "Assign a qualified data steward with appropriate technical and business knowledge",
                "Better data quality management and faster issue resolution"
            ));
        }
        
        // Check for missing compliance classification
        if (metadata.get("complianceClassification") == null) {
            recommendations.add(new BusinessMetadataResponseDTO.RecommendationDTO(
                "COMPLIANCE", "HIGH", "Missing Compliance Classification",
                "Asset lacks compliance classification which is required for regulatory reporting and risk management.",
                "Define data classification, PII/PHI status, and applicable regulatory frameworks",
                "Improved compliance posture and reduced regulatory risk"
            ));
        }
        
        // Check for low data quality score
        Object qualityScore = metadata.get("dataQualityScore");
        if (qualityScore instanceof Number && ((Number) qualityScore).doubleValue() < 6.0) {
            recommendations.add(new BusinessMetadataResponseDTO.RecommendationDTO(
                "QUALITY", "MEDIUM", "Low Data Quality Score",
                "Data quality score is below recommended threshold. Consider implementing data quality improvements.",
                "Review data quality issues, implement validation rules, and establish monitoring",
                "Increased trust in data and better decision-making outcomes"
            ));
        }
        
        // Check for missing business description
        if (metadata.get("businessDescription") == null || ((String) metadata.get("businessDescription")).trim().isEmpty()) {
            recommendations.add(new BusinessMetadataResponseDTO.RecommendationDTO(
                "COMPLETENESS", "MEDIUM", "Missing Business Description",
                "Asset lacks a business description which helps users understand its purpose and context.",
                "Add a comprehensive business description explaining the asset's purpose and use cases",
                "Improved asset discoverability and user understanding"
            ));
        }
        
        return recommendations;
    }

    /**
     * Get current metadata version number
     */
    private Integer getCurrentMetadataVersion(Map<String, Object> metadata) {
        if (metadata == null) {
            return 0;
        }
        
        Object version = metadata.get("versionNumber");
        if (version instanceof Number) {
            return ((Number) version).intValue();
        }
        
        return 0;
    }

    /**
     * Generate change history summary
     */
    private List<String> generateChangeHistory(Asset asset) {
        List<String> history = new ArrayList<>();
        
        // For now, generate basic history based on asset timestamps
        if (asset.getCreatedTs() != null) {
            history.add("Asset created on " + asset.getCreatedTs());
        }
        
        if (asset.getLastModifiedTs() != null && !asset.getLastModifiedTs().equals(asset.getCreatedTs())) {
            history.add("Last modified on " + asset.getLastModifiedTs());
        }
        
        // TODO: Implement proper change tracking for business metadata
        Map<String, Object> metadata = asset.getBusinessMetadataJson();
        if (metadata != null && metadata.get("lastUpdatedAt") != null) {
            history.add("Business metadata last updated on " + metadata.get("lastUpdatedAt"));
        }
        
        return history;
    }

    @Override
    public AssetSchemaDTO getAssetSchema(UUID assetId) {
        // Check if asset exists
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        // TODO(M1): Populate schema details from a real AssetSchemaEntity once the
        // discovery service persists column-level metadata. Until then, return an
        // empty schema DTO with dataAvailable=false so callers can distinguish
        // "no schema discovered yet" from "schema fetched".
        return createAssetSchema(asset);
    }

    /**
    /**
     * Build a minimal {@link AssetSchemaDTO} response for the given asset.
     *
     * <p>Real schema discovery (column names, types, constraints, statistics,
     * lineage, evolution) is not yet implemented in {@code da-catalog}. Column
     * metadata will be populated once {@code da-discovery} persists it to an
     * {@code AssetSchemaEntity} table and this service reads from it. Until then
     * we return an empty schema with {@code dataAvailable=false} in the metadata
     * map so that API consumers can distinguish "no schema discovered yet" from
     * "schema fetched successfully".
     */
    // TODO(M1): Replace this stub with a real implementation that reads schema
    // metadata from an AssetSchemaEntity populated by da-discovery scanners.
    private AssetSchemaDTO createAssetSchema(Asset asset) {
        AssetSchemaDTO schema = new AssetSchemaDTO();
        schema.setAssetId(asset.getAssetId());
        schema.setAssetName(asset.getAssetName());
        schema.setSource("NOT_YET_DISCOVERED");
        schema.setFields(Collections.emptyList());
        schema.setConstraints(Collections.emptyList());
        schema.setIndexes(Collections.emptyList());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("dataAvailable", false);
        metadata.put("reason", "Schema introspection not yet implemented; discovery has not populated column-level metadata for this asset.");
        schema.setMetadata(metadata);

        return schema;
    }

    /**
     * Creates business metadata response for enhanced business context management.
     */
    @Override
    public EnhancedLineageDTO getEnhancedLineage(UUID assetId, Integer maxDepth) {
        // Check if asset exists
        if (!assetRepository.existsById(assetId)) {
            throw new ResourceNotFoundException("Asset", "id", assetId.toString());
        }

        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        // Use default depth if not specified
        int depth = maxDepth != null ? maxDepth : 5;
        
        return createEnhancedLineage(asset, depth);
    }

    @Override
    public AssetPolicyMappingDTO getAssetPolicyMapping(UUID assetId) {
        // Check if asset exists
        if (!assetRepository.existsById(assetId)) {
            throw new ResourceNotFoundException("Asset", "id", assetId.toString());
        }

        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        return createAssetPolicyMapping(asset);
    }

    @Override
    public long countTotalAssets() {
        return assetRepository.count();
    }

    @Override
    public long countClassifiedAssets() {
        return assetRepository.countDistinctAssetsWithLabelAssignments();
    }

    @Override
    public long countAssetsWithMetadata() {
        return assetRepository.countAssetsWithAnyMetadata();
    }

    @Override
    public long countAssetsWithBusinessContext() {
        return assetRepository.countAssetsWithBusinessContextSignals();
    }

    @Override
    public long countAssetsWithLineage() {
        long fromGraph = assetLineageRepository.countDistinctAssetsInLineageGraph();
        long fromEmbedded = assetRepository.countAssetsWithLineageMetadata();
        return Math.max(fromGraph, fromEmbedded);
    }

    @Override
    public long countLineageConnections() {
        return assetLineageRepository.count();
    }

    @Override
    public long countAssetsByStatus(String status) {
        return assetRepository.countByStatus(status);
    }

    @Override
    public Map<String, Object> getGovernanceIssuesSnapshot(String severityFilter) {
        long total = countTotalAssets();
        long classified = countClassifiedAssets();
        long unclassified = Math.max(0, total - classified);
        Map<String, Object> body = new HashMap<>();
        body.put("generatedAt", clock.instant().toString());
        body.put("totalAssets", total);
        body.put("unclassifiedAssetCount", unclassified);
        body.put("metadataGaps", Math.max(0, total - countAssetsWithMetadata()));
        body.put("businessContextGaps", Math.max(0, total - countAssetsWithBusinessContext()));
        body.put("lineageGaps", Math.max(0, total - countAssetsWithLineage()));
        if (severityFilter != null) {
            body.put("severityFilter", severityFilter);
        }
        return body;
    }

    @Override
    public Map<String, Long> getClassificationBreakdown() {
        long total = countTotalAssets();
        long classified = countClassifiedAssets();
        Map<String, Long> m = new HashMap<>();
        m.put("classified", classified);
        m.put("unclassified", Math.max(0, total - classified));
        return m;
    }

    @Override
    public CatalogFiltersDTO getCatalogFilters() {
        return createCatalogFilters();
    }

    @Override
    public AssetComplianceStatusDTO getAssetComplianceStatus(UUID assetId) {
        // Check if asset exists
        if (!assetRepository.existsById(assetId)) {
            throw new ResourceNotFoundException("Asset", "id", assetId.toString());
        }

        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));

        return createAssetComplianceStatus(asset);
    }

    /**
     * Build the enhanced lineage graph for an asset from the real
     * {@code dlap_asset_lineage} graph (log-derived + manual edges), delegating
     * the bidirectional BFS + DTO assembly to {@link LineageService}. When an
     * asset has no lineage yet the result is an empty-but-valid graph (root node
     * only) - never synthetic/mock nodes.
     */
    private EnhancedLineageDTO createEnhancedLineage(Asset asset, int maxDepth) {
        EnhancedLineageDTO lineage = lineageService.buildGraph(asset.getAssetId(), maxDepth);
        lineage.setLineageVersion("2.0.0");
        return lineage;
    }

    /**
     * The catalog stores no per-asset policy evaluations (da-policyengine owns them), so the
     * mapping is reported as unavailable instead of being filled with example policies.
     */
    private AssetPolicyMappingDTO createAssetPolicyMapping(Asset asset) {
        AssetPolicyMappingDTO mapping = new AssetPolicyMappingDTO(asset.getAssetId(), asset.getAssetName());
        mapping.setAvailable(false);
        mapping.setBasis("The catalog does not store policy evaluations. Per-asset policy results are held "
                + "by da-policyengine and are not yet linked to the catalog.");
        return mapping;
    }

    /**
     * Builds the faceted-search metadata from live catalog counts. Every option count comes from a
     * grouped query over the stored assets and label assignments; nothing is a fixed list.
     */
    private CatalogFiltersDTO createCatalogFilters() {
        CatalogFiltersDTO filters = new CatalogFiltersDTO();

        List<CatalogFiltersDTO.FilterCategoryDTO> categories = new ArrayList<>();
        categories.add(buildFacet("assetType", "Asset Type", 1, true,
                groupedCounts(FACET_ASSET_TYPE_JPQL), null));
        categories.add(buildFacet("cloudProvider", "Cloud Provider", 2, true,
                groupedCounts(FACET_CLOUD_PROVIDER_JPQL), CLOUD_PROVIDER_DISPLAY_NAMES));
        categories.add(buildFacet("labels", "Labels", 3, false,
                groupedCounts(FACET_LABEL_JPQL), null));
        filters.setFilterCategories(categories);

        // Only what the listing endpoint really supports: a case-insensitive substring match on the
        // asset name and sorting by any asset attribute. Capabilities that do not exist are not claimed.
        CatalogFiltersDTO.SearchOptionsDTO searchOptions = new CatalogFiltersDTO.SearchOptionsDTO();
        searchOptions.setSearchableFields(List.of("assetName"));
        searchOptions.setSortableFields(List.of("assetName", "createdTs", "lastModifiedTs"));
        searchOptions.setMaxResults(maxPageSize);
        searchOptions.setDefaultPageSize(DEFAULT_PAGE_SIZE);
        searchOptions.setSupportsFullTextSearch(false);
        searchOptions.setSupportsWildcards(false);
        searchOptions.setSupportsFuzzySearch(false);
        filters.setSearchOptions(searchOptions);

        filters.setLastUpdated(clock.instant());
        filters.setTotalAssets(clampToInt(assetRepository.count()));

        return filters;
    }

    /** Runs a grouped (value, count) query and returns the rows in query order. */
    private List<Object[]> groupedCounts(String jpql) {
        return entityManager.createQuery(jpql, Object[].class).getResultList();
    }

    private static CatalogFiltersDTO.FilterCategoryDTO buildFacet(String name, String displayName, int order,
            boolean expandedByDefault, List<Object[]> rows, Map<String, String> displayNames) {
        CatalogFiltersDTO.FilterCategoryDTO category =
                new CatalogFiltersDTO.FilterCategoryDTO(name, displayName, "MULTI_SELECT");
        List<CatalogFiltersDTO.FilterOptionDTO> options = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String value = String.valueOf(row[0]);
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            String display = displayNames != null ? displayNames.getOrDefault(value, value) : value;
            options.add(new CatalogFiltersDTO.FilterOptionDTO(value, display, clampToInt(count)));
        }
        category.setOptions(options);
        category.setTotalOptions(options.size());
        category.setIsExpandedByDefault(expandedByDefault);
        category.setDisplayOrder(order);
        return category;
    }

    private static int clampToInt(long value) {
        return (int) Math.max(0L, Math.min(value, Integer.MAX_VALUE));
    }

    /**
     * The catalog stores no compliance evaluations (da-autocompliance owns them), so the status is
     * reported as unavailable and UNKNOWN instead of a score, frameworks and violations made up for
     * the asset. Callers needing real findings use the da-autocompliance asset status endpoint.
     */
    private AssetComplianceStatusDTO createAssetComplianceStatus(Asset asset) {
        AssetComplianceStatusDTO compliance = new AssetComplianceStatusDTO(asset.getAssetId(), asset.getAssetName());
        compliance.setAvailable(false);
        compliance.setOverallComplianceStatus("UNKNOWN");
        compliance.setBasis("The catalog does not store compliance evaluations. Per-asset findings are held by "
                + "da-autocompliance (GET /api/v1/compliance/assets/{assetId}/status).");
        return compliance;
    }
}
