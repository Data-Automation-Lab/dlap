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

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.dlap.policyengine.dto.EventAnalyticsDTO;
import com.dlap.policyengine.dto.EventStreamDTO;
import com.dlap.policyengine.dto.EventSubscriptionInputDTO;
import com.dlap.policyengine.dto.EventSubscriptionOutputDTO;
import com.dlap.policyengine.model.EventSubscriptionStatus;
import com.dlap.policyengine.model.EventType;

/**
 * Service interface for Event Subscription management and event streaming.
 * Provides operations for creating, managing, and processing event subscriptions.
 */
public interface IEventSubscriptionService {

    // Subscription Management

    /**
     * Create a new event subscription for the authenticated user
     */
    EventSubscriptionOutputDTO createSubscription(EventSubscriptionInputDTO inputDTO, UUID creatorUserId);

    /**
     * Update an existing event subscription
     */
    EventSubscriptionOutputDTO updateSubscription(UUID subscriptionId, EventSubscriptionInputDTO inputDTO, UUID updaterUserId);

    /**
     * Get event subscription by ID
     */
    EventSubscriptionOutputDTO getSubscriptionById(UUID subscriptionId);

    /**
     * Get all event subscriptions for a user with pagination
     */
    Page<EventSubscriptionOutputDTO> getSubscriptionsForUser(UUID userId, Pageable pageable, String status, String nameContains);

    /**
     * Get all event subscriptions (admin only) with pagination
     */
    Page<EventSubscriptionOutputDTO> getAllSubscriptions(Pageable pageable, String status, String nameContains);

    /**
     * Delete an event subscription
     */
    void deleteSubscription(UUID subscriptionId);

    /**
     * Update subscription status (enable/disable/pause)
     */
    EventSubscriptionOutputDTO updateSubscriptionStatus(UUID subscriptionId, EventSubscriptionStatus status, UUID updaterUserId);

    // Event Streaming and Processing

    /**
     * Get real-time event stream for a user's subscriptions
     * This would typically be used with WebSocket or Server-Sent Events
     */
    List<EventStreamDTO> getEventStreamForUser(UUID userId, Integer limit);

    /**
     * Get event stream for all subscriptions (admin only)
     */
    List<EventStreamDTO> getAllEventStream(Integer limit);

    /**
     * Process an incoming event and match it against active subscriptions
     */
    void processIncomingEvent(EventStreamDTO eventDTO);

    /**
     * Get historical events for a subscription
     */
    Page<EventStreamDTO> getHistoricalEventsForSubscription(UUID subscriptionId, Pageable pageable);

    // Analytics and Metrics

    /**
     * Get event analytics for a user's subscriptions
     */
    EventAnalyticsDTO getEventAnalyticsForUser(UUID userId);

    /**
     * Get system-wide event analytics (admin only)
     */
    EventAnalyticsDTO getSystemEventAnalytics();

    /**
     * Get event analytics for a specific time range
     */
    EventAnalyticsDTO getEventAnalyticsForTimeRange(UUID userId, java.time.Instant fromTime, java.time.Instant toTime);

    // Utility Methods

    /**
     * Get available event types for subscription configuration
     */
    List<EventType> getAvailableEventTypes();

    /**
     * Get available source services for subscription configuration
     */
    List<String> getAvailableSourceServices();

    /**
     * Validate subscription configuration
     */
    void validateSubscriptionConfiguration(EventSubscriptionInputDTO inputDTO);
} 