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

package com.dlap.policyengine.web.rest;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.dlap.common.security.SecurityUtils;
import com.dlap.policyengine.dto.EventAnalyticsDTO;
import com.dlap.policyengine.dto.EventStreamDTO;
import com.dlap.policyengine.dto.EventSubscriptionInputDTO;
import com.dlap.policyengine.dto.EventSubscriptionOutputDTO;
import com.dlap.policyengine.model.EventSubscriptionStatus;
import com.dlap.policyengine.model.EventType;
import com.dlap.policyengine.service.IEventSubscriptionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST controller for Event Center functionality.
 * Provides endpoints for managing event subscriptions, streaming events, and analytics.
 */
@RestController
@RequestMapping("/api/v1/policyengine/events")
@Tag(name = "Event Center", description = "Endpoints for Event Center management, streaming, and analytics")
public class EventCenterController {

    private static final Logger log = LoggerFactory.getLogger(EventCenterController.class);

    /** Roles that may manage their own event subscriptions. */
    private static final String SUBSCRIBER_ROLES =
        "hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')";

    /**
     * SEC-01: a subscription rule condition is MVEL, which is executable code. Subscriber
     * roles may save a subscription that carries no rules; any rule requires ROLE_ADMIN.
     * Evaluated against the request-body parameter named {@code inputDTO}.
     */
    private static final String SUBSCRIPTION_RULES_ADMIN_ONLY = SUBSCRIBER_ROLES
        + " and (hasAuthority('ROLE_ADMIN') or #inputDTO.rules == null or #inputDTO.rules.isEmpty())";

    private final IEventSubscriptionService eventSubscriptionService;

    @Autowired
    public EventCenterController(IEventSubscriptionService eventSubscriptionService) {
        this.eventSubscriptionService = eventSubscriptionService;
    }

    // Endpoint 1: Subscription Management
    @PostMapping("/subscriptions")
    @PreAuthorize(SUBSCRIPTION_RULES_ADMIN_ONLY)
    @Operation(
        summary = "Create event subscription",
        description = "Create a new event subscription to receive notifications for specific event types and conditions. "
                + "Rule conditions are MVEL expressions and are accepted only from ROLE_ADMIN."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Event subscription created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid subscription configuration"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to create subscription, or rules supplied by a non-admin")
    })
    public ResponseEntity<EventSubscriptionOutputDTO> createSubscription(
            @Parameter(description = "Event subscription configuration")
            @Valid @RequestBody @P("inputDTO") EventSubscriptionInputDTO inputDTO) {
        log.info("REST request to create Event Subscription: {}", inputDTO.getName());
        
        UUID creatorUserId = SecurityUtils.getAuthenticatedUserId();
        EventSubscriptionOutputDTO createdSubscription = eventSubscriptionService.createSubscription(inputDTO, creatorUserId);
        
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(createdSubscription.getId())
                .toUri();
        
        return ResponseEntity.created(location).body(createdSubscription);
    }

    @GetMapping("/subscriptions")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get user's event subscriptions",
        description = "Retrieve event subscriptions for the authenticated user with optional filtering"
    )
    public ResponseEntity<Page<EventSubscriptionOutputDTO>> getUserSubscriptions(
            @PageableDefault(size = 20, sort = "name") Pageable pageable,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String nameContains) {
        log.info("REST request to get Event Subscriptions for user with filters: status={}, nameContains={}", status, nameContains);
        
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        Page<EventSubscriptionOutputDTO> subscriptionsPage = eventSubscriptionService.getSubscriptionsForUser(
                userId, pageable, status, nameContains);
        
        return ResponseEntity.ok(subscriptionsPage);
    }

    @GetMapping("/subscriptions/{subscriptionId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get event subscription by ID",
        description = "Retrieve detailed information about a specific event subscription"
    )
    public ResponseEntity<EventSubscriptionOutputDTO> getSubscriptionById(@PathVariable UUID subscriptionId) {
        log.info("REST request to get Event Subscription by id: {}", subscriptionId);
        
        EventSubscriptionOutputDTO subscription = eventSubscriptionService.getSubscriptionById(subscriptionId);
        return ResponseEntity.ok(subscription);
    }

    @PutMapping("/subscriptions/{subscriptionId}")
    @PreAuthorize(SUBSCRIPTION_RULES_ADMIN_ONLY)
    @Operation(
        summary = "Update event subscription",
        description = "Update configuration of an existing event subscription. "
                + "Rule conditions are MVEL expressions and are accepted only from ROLE_ADMIN."
    )
    public ResponseEntity<EventSubscriptionOutputDTO> updateSubscription(
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody @P("inputDTO") EventSubscriptionInputDTO inputDTO) {
        log.info("REST request to update Event Subscription: {}", subscriptionId);
        
        UUID updaterUserId = SecurityUtils.getAuthenticatedUserId();
        EventSubscriptionOutputDTO updatedSubscription = eventSubscriptionService.updateSubscription(
                subscriptionId, inputDTO, updaterUserId);
        
        return ResponseEntity.ok(updatedSubscription);
    }

    @DeleteMapping("/subscriptions/{subscriptionId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Delete event subscription",
        description = "Delete an event subscription and stop receiving notifications"
    )
    public ResponseEntity<Void> deleteSubscription(@PathVariable UUID subscriptionId) {
        log.info("REST request to delete Event Subscription: {}", subscriptionId);
        
        eventSubscriptionService.deleteSubscription(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/subscriptions/{subscriptionId}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Update subscription status",
        description = "Enable, disable, pause, or archive an event subscription"
    )
    public ResponseEntity<EventSubscriptionOutputDTO> updateSubscriptionStatus(
            @PathVariable UUID subscriptionId,
            @RequestParam EventSubscriptionStatus status) {
        log.info("REST request to update Event Subscription status: {} to {}", subscriptionId, status);
        
        UUID updaterUserId = SecurityUtils.getAuthenticatedUserId();
        EventSubscriptionOutputDTO updatedSubscription = eventSubscriptionService.updateSubscriptionStatus(
                subscriptionId, status, updaterUserId);
        
        return ResponseEntity.ok(updatedSubscription);
    }

    // Endpoint 2: Event Streaming
    @GetMapping("/stream")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get real-time event stream",
        description = "Retrieve real-time events matching user's subscriptions for the Event Center dashboard"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Event stream retrieved successfully"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to access event stream")
    })
    public ResponseEntity<List<EventStreamDTO>> getEventStream(
            @Parameter(description = "Maximum number of events to return (default: 50)")
            @RequestParam(defaultValue = "50") Integer limit) {
        log.info("REST request to get Event Stream with limit: {}", limit);
        
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        List<EventStreamDTO> eventStream = eventSubscriptionService.getEventStreamForUser(userId, limit);
        
        return ResponseEntity.ok(eventStream);
    }

    @GetMapping("/stream/all")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
    @Operation(
        summary = "Get all event stream (Admin only)",
        description = "Retrieve real-time events from all subscriptions across the platform"
    )
    public ResponseEntity<List<EventStreamDTO>> getAllEventStream(
            @RequestParam(defaultValue = "100") Integer limit) {
        log.info("REST request to get All Event Stream with limit: {}", limit);
        
        List<EventStreamDTO> eventStream = eventSubscriptionService.getAllEventStream(limit);
        return ResponseEntity.ok(eventStream);
    }

    // Endpoint 3: Historical Events
    @GetMapping("/subscriptions/{subscriptionId}/history")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get historical events for subscription",
        description = "Retrieve paginated historical events that matched a specific subscription"
    )
    public ResponseEntity<Page<EventStreamDTO>> getHistoricalEvents(
            @PathVariable UUID subscriptionId,
            @PageableDefault(size = 50, sort = "timestamp") Pageable pageable) {
        log.info("REST request to get Historical Events for subscription: {}", subscriptionId);
        
        Page<EventStreamDTO> historicalEvents = eventSubscriptionService.getHistoricalEventsForSubscription(
                subscriptionId, pageable);
        
        return ResponseEntity.ok(historicalEvents);
    }

    // Endpoint 4: Event Analytics
    @GetMapping("/analytics")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get event analytics dashboard",
        description = "Retrieve comprehensive analytics for user's event subscriptions including trends, metrics, and insights"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Event analytics retrieved successfully"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to access analytics")
    })
    public ResponseEntity<EventAnalyticsDTO> getEventAnalytics(
            @Parameter(description = "Start time for analytics (defaults to 24 hours ago)")
            @RequestParam(required = false) String fromTime,
            @Parameter(description = "End time for analytics (defaults to now)")
            @RequestParam(required = false) String toTime) {
        log.info("REST request to get Event Analytics with time range: {} to {}", fromTime, toTime);
        
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        
        if (fromTime != null && toTime != null) {
            Instant from = Instant.parse(fromTime);
            Instant to = Instant.parse(toTime);
            EventAnalyticsDTO analytics = eventSubscriptionService.getEventAnalyticsForTimeRange(userId, from, to);
            return ResponseEntity.ok(analytics);
        } else {
            EventAnalyticsDTO analytics = eventSubscriptionService.getEventAnalyticsForUser(userId);
            return ResponseEntity.ok(analytics);
        }
    }

    @GetMapping("/analytics/system")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
    @Operation(
        summary = "Get system-wide event analytics (Admin only)",
        description = "Retrieve comprehensive analytics across all subscriptions and users in the platform"
    )
    public ResponseEntity<EventAnalyticsDTO> getSystemEventAnalytics() {
        log.info("REST request to get System Event Analytics");
        
        EventAnalyticsDTO analytics = eventSubscriptionService.getSystemEventAnalytics();
        return ResponseEntity.ok(analytics);
    }

    // Endpoint 5: Configuration Helpers
    // SEC-01: the former rule-test endpoint compiled and ran caller-supplied MVEL and has
    // been removed. Nothing in this controller evaluates caller-supplied code.
    @GetMapping("/config/event-types")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get available event types",
        description = "Retrieve list of all available event types for subscription configuration"
    )
    public ResponseEntity<List<EventType>> getAvailableEventTypes() {
        log.info("REST request to get Available Event Types");
        
        List<EventType> eventTypes = eventSubscriptionService.getAvailableEventTypes();
        return ResponseEntity.ok(eventTypes);
    }

    @GetMapping("/config/source-services")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get available source services",
        description = "Retrieve list of all available source services for subscription configuration"
    )
    public ResponseEntity<List<String>> getAvailableSourceServices() {
        log.info("REST request to get Available Source Services");
        
        List<String> sourceServices = eventSubscriptionService.getAvailableSourceServices();
        return ResponseEntity.ok(sourceServices);
    }

    @PostMapping("/config/validate")
    @PreAuthorize(SUBSCRIPTION_RULES_ADMIN_ONLY)
    @Operation(
        summary = "Validate subscription configuration",
        description = "Validate the structure of an event subscription configuration before creation to catch errors early. "
                + "Checks shape only: rule conditions are never compiled or evaluated here, and rules are accepted only from ROLE_ADMIN."
    )
    public ResponseEntity<Void> validateSubscriptionConfiguration(
            @Valid @RequestBody @P("inputDTO") EventSubscriptionInputDTO inputDTO) {
        log.info("REST request to validate Subscription Configuration: {}", inputDTO.getName());
        
        eventSubscriptionService.validateSubscriptionConfiguration(inputDTO);
        return ResponseEntity.ok().build();
    }
} 