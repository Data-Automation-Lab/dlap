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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dlap.policyengine.common.ResourceNotFoundException;
import com.dlap.policyengine.dto.EventAnalyticsDTO;
import com.dlap.policyengine.dto.EventStreamDTO;
import com.dlap.policyengine.dto.EventSubscriptionInputDTO;
import com.dlap.policyengine.dto.EventSubscriptionOutputDTO;
import com.dlap.policyengine.filter.RetiredRules;
import com.dlap.policyengine.filter.SubscriptionFilter;
import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventSubscription;
import com.dlap.policyengine.model.EventSubscriptionStatus;
import com.dlap.policyengine.model.EventType;
import com.dlap.policyengine.model.StreamEventLedger;
import com.dlap.policyengine.model.SubscriptionEventMatch;
import com.dlap.policyengine.repository.EventRuleRepository;
import com.dlap.policyengine.repository.EventSubscriptionRepository;
import com.dlap.policyengine.repository.StreamEventLedgerRepository;
import com.dlap.policyengine.repository.SubscriptionEventMatchRepository;

import jakarta.persistence.criteria.Predicate;

/**
 * Service implementation for Event Subscription management and event streaming.
 * Provides operations for creating, managing, and processing event subscriptions.
 *
 * <p>SEC-04 (ADR-0057): a subscription selects events with a typed
 * {@link SubscriptionFilter} (event types, severities, source services, asset prefixes,
 * labels). The former MVEL rule conditions are retired: any request that still carries a
 * rule is refused with {@link IllegalArgumentException} (HTTP 400), and nothing in this
 * service compiles or runs caller-supplied code.
 */
@Service
@Transactional
public class EventSubscriptionService implements IEventSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(EventSubscriptionService.class);

    /** Services that emit Event Center events; the only accepted {@code sourceServices}. */
    private static final List<String> SOURCE_SERVICES = List.of(
        "da-discovery",
        "da-catalog",
        "da-policyengine",
        "da-reporting",
        "da-autolabel",
        "da-autoarchival",
        "da-autodelete",
        "da-autocompliance",
        "da-admin-service"
    );

    private final EventSubscriptionRepository eventSubscriptionRepository;
    private final EventRuleRepository eventRuleRepository;
    private final StreamEventLedgerRepository streamEventLedgerRepository;
    private final SubscriptionEventMatchRepository subscriptionEventMatchRepository;

    /**
     * The cleaned typed-filter values of one request.
     *
     * @param eventTypes     distinct event types
     * @param severities     distinct severities
     * @param sourceServices known source services
     * @param assetPrefixes  cleaned asset prefixes
     * @param labels         cleaned label names
     */
    private record CleanFilter(List<EventType> eventTypes, List<EventSeverity> severities,
                               List<String> sourceServices, List<String> assetPrefixes, List<String> labels) { }

    @Autowired
    public EventSubscriptionService(EventSubscriptionRepository eventSubscriptionRepository,
                                    EventRuleRepository eventRuleRepository,
                                    StreamEventLedgerRepository streamEventLedgerRepository,
                                    SubscriptionEventMatchRepository subscriptionEventMatchRepository) {
        this.eventSubscriptionRepository = eventSubscriptionRepository;
        this.eventRuleRepository = eventRuleRepository;
        this.streamEventLedgerRepository = streamEventLedgerRepository;
        this.subscriptionEventMatchRepository = subscriptionEventMatchRepository;
    }

    // ==================== SUBSCRIPTION MANAGEMENT ====================

    @Override
    public EventSubscriptionOutputDTO createSubscription(EventSubscriptionInputDTO inputDTO, UUID creatorUserId) {
        log.info("Creating event subscription for user: {}", creatorUserId);

        // Validate input; refuses retired rule conditions (SEC-04).
        validateSubscriptionConfiguration(inputDTO);
        CleanFilter filter = cleanFilter(inputDTO);

        // Create subscription entity
        EventSubscription subscription = new EventSubscription();
        subscription.setName(inputDTO.getName());
        subscription.setDescription(inputDTO.getDescription());
        subscription.setUserId(creatorUserId);
        subscription.setStatus(EventSubscriptionStatus.ACTIVE);
        subscription.setNotificationConfig(inputDTO.getNotificationConfig());
        subscription.setActionConfig(inputDTO.getActionConfig());
        subscription.setEventTypes(new ArrayList<>(filter.eventTypes()));
        subscription.setSeverities(new ArrayList<>(filter.severities()));
        subscription.setSourceServices(new ArrayList<>(filter.sourceServices()));
        subscription.setAssetPrefixes(filter.assetPrefixes());
        subscription.setLabels(filter.labels());
        subscription.setCreatedAt(Instant.now());
        subscription.setUpdatedAt(Instant.now());
        subscription.setCreatedByUserId(creatorUserId);
        subscription.setUpdatedByUserId(creatorUserId);

        EventSubscription savedSubscription = eventSubscriptionRepository.save(subscription);

        log.info("Successfully created event subscription: {}", savedSubscription.getId());
        return convertToOutputDTO(savedSubscription);
    }

    @Override
    public EventSubscriptionOutputDTO updateSubscription(UUID subscriptionId, EventSubscriptionInputDTO inputDTO, UUID updaterUserId) {
        log.info("Updating event subscription: {}", subscriptionId);

        // Validate input before loading anything; refuses retired rule conditions (SEC-04).
        validateSubscriptionConfiguration(inputDTO);
        CleanFilter filter = cleanFilter(inputDTO);

        EventSubscription subscription = eventSubscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new ResourceNotFoundException("EventSubscription", "id", subscriptionId.toString()));

        subscription.setName(inputDTO.getName());
        subscription.setDescription(inputDTO.getDescription());
        subscription.setNotificationConfig(inputDTO.getNotificationConfig());
        subscription.setActionConfig(inputDTO.getActionConfig());
        // A filter list that is absent (null) keeps the stored values; a list replaces them.
        if (inputDTO.getEventTypes() != null) {
            subscription.setEventTypes(new ArrayList<>(filter.eventTypes()));
        }
        if (inputDTO.getSeverities() != null) {
            subscription.setSeverities(new ArrayList<>(filter.severities()));
        }
        if (inputDTO.getSourceServices() != null) {
            subscription.setSourceServices(new ArrayList<>(filter.sourceServices()));
        }
        if (inputDTO.getAssetPrefixes() != null) {
            subscription.setAssetPrefixes(filter.assetPrefixes());
        }
        if (inputDTO.getLabels() != null) {
            subscription.setLabels(filter.labels());
        }
        subscription.setUpdatedAt(Instant.now());
        subscription.setUpdatedByUserId(updaterUserId);

        EventSubscription updatedSubscription = eventSubscriptionRepository.save(subscription);
        log.info("Successfully updated event subscription: {}", subscriptionId);
        return convertToOutputDTO(updatedSubscription);
    }

    @Override
    @Transactional(readOnly = true)
    public EventSubscriptionOutputDTO getSubscriptionById(UUID subscriptionId) {
        EventSubscription subscription = eventSubscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new ResourceNotFoundException("EventSubscription", "id", subscriptionId.toString()));
        return convertToOutputDTO(subscription);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EventSubscriptionOutputDTO> getSubscriptionsForUser(UUID userId, Pageable pageable, String status, String nameContains) {
        Specification<EventSubscription> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("userId"), userId));
            
            if (StringUtils.hasText(status)) {
                try {
                    predicates.add(criteriaBuilder.equal(root.get("status"), EventSubscriptionStatus.valueOf(status.toUpperCase())));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid status provided for filtering: {}", status);
                    predicates.add(criteriaBuilder.disjunction());
                }
            }
            
            if (StringUtils.hasText(nameContains)) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), 
                    "%" + nameContains.toLowerCase() + "%"));
            }
            
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
        
        Page<EventSubscription> subscriptionPage = eventSubscriptionRepository.findAll(spec, pageable);
        return subscriptionPage.map(this::convertToOutputDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EventSubscriptionOutputDTO> getAllSubscriptions(Pageable pageable, String status, String nameContains) {
        Specification<EventSubscription> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            if (StringUtils.hasText(status)) {
                try {
                    predicates.add(criteriaBuilder.equal(root.get("status"), EventSubscriptionStatus.valueOf(status.toUpperCase())));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid status provided for filtering: {}", status);
                    predicates.add(criteriaBuilder.disjunction());
                }
            }
            
            if (StringUtils.hasText(nameContains)) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), 
                    "%" + nameContains.toLowerCase() + "%"));
            }
            
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
        
        Page<EventSubscription> subscriptionPage = eventSubscriptionRepository.findAll(spec, pageable);
        return subscriptionPage.map(this::convertToOutputDTO);
    }

    @Override
    public void deleteSubscription(UUID subscriptionId) {
        if (!eventSubscriptionRepository.existsById(subscriptionId)) {
            throw new ResourceNotFoundException("EventSubscription", "id", subscriptionId.toString());
        }

        subscriptionEventMatchRepository.deleteBySubscriptionId(subscriptionId);
        eventRuleRepository.deleteAllBySubscriptionId(subscriptionId);
        eventSubscriptionRepository.deleteById(subscriptionId);
        log.info("Successfully deleted event subscription: {}", subscriptionId);
    }

    @Override
    public EventSubscriptionOutputDTO updateSubscriptionStatus(UUID subscriptionId, EventSubscriptionStatus status, UUID updaterUserId) {
        EventSubscription subscription = eventSubscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new ResourceNotFoundException("EventSubscription", "id", subscriptionId.toString()));
        
        subscription.setStatus(status);
        subscription.setUpdatedAt(Instant.now());
        subscription.setUpdatedByUserId(updaterUserId);
        
        EventSubscription updatedSubscription = eventSubscriptionRepository.save(subscription);
        log.info("Updated subscription {} status to: {}", subscriptionId, status);
        return convertToOutputDTO(updatedSubscription);
    }

    // ==================== EVENT STREAMING AND PROCESSING ====================

    @Override
    @Transactional(readOnly = true)
    public List<EventStreamDTO> getEventStreamForUser(UUID userId, Integer limit) {
        log.info("Getting event stream for user: {} with limit: {}", userId, limit);
        
        // Get user's active subscriptions
        List<EventSubscription> activeSubscriptions = eventSubscriptionRepository
            .findByUserIdAndStatus(userId, EventSubscriptionStatus.ACTIVE);

        if (activeSubscriptions.isEmpty()) {
            return new ArrayList<>();
        }

        int lim = limit != null ? limit : 50;
        Page<StreamEventLedger> page = streamEventLedgerRepository.findForActiveUserSubscriptions(userId,
                PageRequest.of(0, lim, Sort.by(Sort.Direction.DESC, "timestamp")));
        return page.getContent().stream().map(this::toStreamDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventStreamDTO> getAllEventStream(Integer limit) {
        log.info("Getting all event stream with limit: {}", limit);

        int lim = limit != null ? limit : 100;
        return streamEventLedgerRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, lim))
                .getContent()
                .stream()
                .map(this::toStreamDto)
                .collect(Collectors.toList());
    }

    @Override
    public void processIncomingEvent(EventStreamDTO eventDTO) {
        if (eventDTO == null || eventDTO.getEventType() == null) {
            log.warn("Ignoring incoming event: DTO or eventType is null");
            return;
        }
        // Delivery is at least once (log-event.md section 5.5): an id already in the ledger is a duplicate
        // and changes nothing, so it adds no match and sends no second notification (Q-824).
        if (eventDTO.getEventId() != null && streamEventLedgerRepository.existsById(eventDTO.getEventId())) {
            log.debug("Ignoring a duplicate of event {}", eventDTO.getEventId());
            return;
        }
        log.info("Processing incoming event: {} from service: {}", eventDTO.getEventType(), eventDTO.getSourceService());

        StreamEventLedger ledger = toLedgerEntity(eventDTO);
        ledger = streamEventLedgerRepository.save(ledger);

        List<EventSubscription> activeSubscriptions = eventSubscriptionRepository
            .findByStatus(EventSubscriptionStatus.ACTIVE);

        int matchCount = 0;
        for (EventSubscription subscription : activeSubscriptions) {
            try {
                if (matchesSubscription(eventDTO, subscription)) {
                    SubscriptionEventMatch match = new SubscriptionEventMatch();
                    match.setSubscriptionId(subscription.getId());
                    match.setLedgerEventId(ledger.getId());
                    match.setMatchedAt(Instant.now());
                    subscriptionEventMatchRepository.save(match);
                    matchCount++;
                }
            } catch (Exception e) {
                log.error("Error processing event for subscription {}: {}", subscription.getId(), e.getMessage(), e);
            }
        }
        ledger.setProcessed(true);
        streamEventLedgerRepository.save(ledger);
        log.debug("Recorded event {} with {} subscription matches", ledger.getId(), matchCount);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EventStreamDTO> getHistoricalEventsForSubscription(UUID subscriptionId, Pageable pageable) {
        // Verify subscription exists
        if (!eventSubscriptionRepository.existsById(subscriptionId)) {
            throw new ResourceNotFoundException("EventSubscription", "id", subscriptionId.toString());
        }
        
        return streamEventLedgerRepository.findForSubscription(subscriptionId, pageable).map(this::toStreamDto);
    }

    // ==================== ANALYTICS AND METRICS ====================

    @Override
    @Transactional(readOnly = true)
    public EventAnalyticsDTO getEventAnalyticsForUser(UUID userId) {
        log.info("Getting event analytics for user: {}", userId);

        List<EventSubscription> userSubscriptions = eventSubscriptionRepository.findByUserId(userId);
        long active = userSubscriptions.stream().filter(s -> s.getStatus() == EventSubscriptionStatus.ACTIVE).count();

        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant monthAgo = Instant.now().minus(30, ChronoUnit.DAYS);

        EventAnalyticsDTO analytics = new EventAnalyticsDTO();
        analytics.setTotalActiveSubscriptions(active);
        analytics.setTotalEventsToday(subscriptionEventMatchRepository.countMatchesForUserSince(userId, startOfDay));
        analytics.setTotalEventsThisWeek(subscriptionEventMatchRepository.countMatchesForUserSince(userId, weekAgo));
        analytics.setTotalEventsThisMonth(subscriptionEventMatchRepository.countMatchesForUserSince(userId, monthAgo));
        return analytics;
    }

    @Override
    @Transactional(readOnly = true)
    public EventAnalyticsDTO getSystemEventAnalytics() {
        log.info("Getting system-wide event analytics");

        List<EventSubscription> allSubscriptions = eventSubscriptionRepository.findAll();
        long active = allSubscriptions.stream().filter(s -> s.getStatus() == EventSubscriptionStatus.ACTIVE).count();

        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant monthAgo = Instant.now().minus(30, ChronoUnit.DAYS);

        EventAnalyticsDTO analytics = new EventAnalyticsDTO();
        analytics.setTotalActiveSubscriptions(active);
        analytics.setTotalEventsToday(streamEventLedgerRepository.countSince(startOfDay));
        analytics.setTotalEventsThisWeek(streamEventLedgerRepository.countSince(weekAgo));
        analytics.setTotalEventsThisMonth(streamEventLedgerRepository.countSince(monthAgo));
        return analytics;
    }

    @Override
    @Transactional(readOnly = true)
    public EventAnalyticsDTO getEventAnalyticsForTimeRange(UUID userId, Instant fromTime, Instant toTime) {
        log.info("Getting event analytics for user: {} from {} to {}", userId, fromTime, toTime);

        Instant from = fromTime != null ? fromTime : Instant.now().minus(30, ChronoUnit.DAYS);
        Instant to = toTime != null ? toTime : Instant.now();

        EventAnalyticsDTO analytics = new EventAnalyticsDTO(from, to);
        long inRange = subscriptionEventMatchRepository.countMatchesForUserInRange(userId, from, to);
        analytics.setTotalEventsThisMonth(inRange);
        return analytics;
    }

    // ==================== UTILITY METHODS ====================

    @Override
    public List<EventType> getAvailableEventTypes() {
        return Arrays.asList(EventType.values());
    }

    @Override
    public List<String> getAvailableSourceServices() {
        return SOURCE_SERVICES;
    }

    @Override
    public void validateSubscriptionConfiguration(EventSubscriptionInputDTO inputDTO) {
        if (inputDTO == null) {
            throw new IllegalArgumentException("Subscription input cannot be null");
        }
        
        if (!StringUtils.hasText(inputDTO.getName())) {
            throw new IllegalArgumentException("Subscription name is required");
        }

        if (inputDTO.getName().length() > 255) {
            throw new IllegalArgumentException("Subscription name cannot exceed 255 characters");
        }

        if (inputDTO.getNotificationConfig() == null || inputDTO.getNotificationConfig().isEmpty()) {
            throw new IllegalArgumentException("At least one notification configuration entry is required");
        }
        
        // SEC-04: rule conditions were MVEL (executable code) and are retired. They are
        // refused for every caller; nothing here compiles or evaluates them.
        if (inputDTO.getRules() != null && !inputDTO.getRules().isEmpty()) {
            log.warn("Refused a subscription carrying {} retired rule condition(s)", inputDTO.getRules().size());
            throw new IllegalArgumentException(RetiredRules.SUBSCRIPTION_RULES_MESSAGE);
        }

        // Typed filter values: the same checks create and update apply.
        cleanFilter(inputDTO);
    }

    /**
     * Checks and cleans the typed filter values of a request.
     *
     * @param inputDTO the subscription input
     * @return the cleaned source services, asset prefixes and labels
     * @throws IllegalArgumentException when a value is unknown, blank, too long or too many
     */
    private CleanFilter cleanFilter(EventSubscriptionInputDTO inputDTO) {
        return new CleanFilter(
            SubscriptionFilter.cleanValues(inputDTO.getEventTypes(), "eventTypes"),
            SubscriptionFilter.cleanValues(inputDTO.getSeverities(), "severities"),
            SubscriptionFilter.cleanSourceServices(inputDTO.getSourceServices(), SOURCE_SERVICES),
            SubscriptionFilter.cleanAssetPrefixes(inputDTO.getAssetPrefixes()),
            SubscriptionFilter.cleanLabels(inputDTO.getLabels()));
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private EventSubscriptionOutputDTO convertToOutputDTO(EventSubscription subscription) {
        EventSubscriptionOutputDTO outputDTO = new EventSubscriptionOutputDTO();
        outputDTO.setId(subscription.getId());
        outputDTO.setName(subscription.getName());
        outputDTO.setDescription(subscription.getDescription());
        outputDTO.setUserId(subscription.getUserId());
        outputDTO.setStatus(subscription.getStatus());
        outputDTO.setNotificationConfig(subscription.getNotificationConfig());
        outputDTO.setActionConfig(subscription.getActionConfig());
        outputDTO.setEventTypes(subscription.getEventTypes());
        outputDTO.setSeverities(subscription.getSeverities());
        outputDTO.setSourceServices(subscription.getSourceServices());
        outputDTO.setAssetPrefixes(sorted(subscription.getAssetPrefixes()));
        outputDTO.setLabels(sorted(subscription.getLabels()));
        outputDTO.setCreatedAt(subscription.getCreatedAt());
        outputDTO.setUpdatedAt(subscription.getUpdatedAt());
        outputDTO.setCreatedByUserId(subscription.getCreatedByUserId());
        outputDTO.setUpdatedByUserId(subscription.getUpdatedByUserId());

        // Retired MVEL rules (SEC-04): V5 emptied event_rules, so there is never one to show.
        outputDTO.setRules(List.of());
        outputDTO.setTotalRulesCount(0L);
        outputDTO.setEnabledRulesCount(0L);
        outputDTO.setEventsMatchedCount(subscriptionEventMatchRepository.countBySubscriptionId(subscription.getId()));
        outputDTO.setLastEventMatchedAt(subscriptionEventMatchRepository
            .findFirstBySubscriptionIdOrderByMatchedAtDesc(subscription.getId())
            .map(SubscriptionEventMatch::getMatchedAt)
            .orElse(null));

        return outputDTO;
    }

    private static List<String> sorted(Collection<String> values) {
        return values == null ? List.of() : values.stream().sorted().collect(Collectors.toList());
    }

    /**
     * Decides whether an event matches a subscription with its typed filter (SEC-04).
     *
     * @param event        the incoming event
     * @param subscription the subscription
     * @return {@code true} when every non-empty filter field holds
     */
    private boolean matchesSubscription(EventStreamDTO event, EventSubscription subscription) {
        return SubscriptionFilter.of(subscription.getEventTypes(), subscription.getSeverities(),
                subscription.getSourceServices(), subscription.getAssetPrefixes(), subscription.getLabels())
            .matches(event);
    }

    private StreamEventLedger toLedgerEntity(EventStreamDTO dto) {
        StreamEventLedger e = new StreamEventLedger();
        e.setId(dto.getEventId() != null ? dto.getEventId() : UUID.randomUUID());
        e.setEventType(dto.getEventType());
        e.setSeverity(dto.getSeverity() != null ? dto.getSeverity() : EventSeverity.INFO);
        e.setSourceService(dto.getSourceService());
        e.setTitle(dto.getTitle());
        e.setDescription(dto.getDescription());
        e.setAssetId(dto.getAssetId());
        e.setAssetName(dto.getAssetName());
        e.setPolicyId(dto.getPolicyId());
        e.setPolicyName(dto.getPolicyName());
        e.setEventData(dto.getEventData());
        e.setMetadata(dto.getMetadata());
        e.setTimestamp(dto.getTimestamp() != null ? dto.getTimestamp() : Instant.now());
        e.setActorUserId(dto.getUserId());
        e.setProcessed(false);
        return e;
    }

    private EventStreamDTO toStreamDto(StreamEventLedger e) {
        EventStreamDTO dto = new EventStreamDTO();
        dto.setEventId(e.getId());
        dto.setEventType(e.getEventType());
        dto.setSeverity(e.getSeverity());
        dto.setSourceService(e.getSourceService());
        dto.setTitle(e.getTitle());
        dto.setDescription(e.getDescription());
        dto.setAssetId(e.getAssetId());
        dto.setAssetName(e.getAssetName());
        dto.setPolicyId(e.getPolicyId());
        dto.setPolicyName(e.getPolicyName());
        dto.setEventData(e.getEventData());
        dto.setMetadata(e.getMetadata());
        dto.setTimestamp(e.getTimestamp());
        dto.setUserId(e.getActorUserId());
        dto.setIsProcessed(e.getProcessed());
        return dto;
    }
} 