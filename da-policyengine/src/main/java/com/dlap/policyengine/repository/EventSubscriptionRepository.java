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

package com.dlap.policyengine.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.policyengine.model.EventSubscription;
import com.dlap.policyengine.model.EventSubscriptionStatus;
import com.dlap.policyengine.model.EventType;

/**
 * Repository interface for EventSubscription entities.
 * Provides data access methods for event subscription management.
 */
@Repository
public interface EventSubscriptionRepository extends JpaRepository<EventSubscription, UUID>, JpaSpecificationExecutor<EventSubscription> {

    /**
     * Find all event subscriptions for a specific user
     */
    List<EventSubscription> findByUserId(UUID userId);

    /**
     * Find event subscriptions by user ID with pagination
     */
    Page<EventSubscription> findByUserId(UUID userId, Pageable pageable);

    /**
     * Find event subscriptions by status
     */
    List<EventSubscription> findByStatus(EventSubscriptionStatus status);

    /**
     * Find active event subscriptions for a specific user
     */
    List<EventSubscription> findByUserIdAndStatus(UUID userId, EventSubscriptionStatus status);

    /**
     * Find event subscriptions that monitor a specific event type
     */
    @Query("SELECT es FROM EventSubscription es JOIN es.eventTypes et WHERE et = :eventType AND es.status = 'ACTIVE'")
    List<EventSubscription> findActiveSubscriptionsByEventType(@Param("eventType") EventType eventType);

    /**
     * Find event subscriptions that monitor a specific source service
     */
    @Query("SELECT es FROM EventSubscription es JOIN es.sourceServices ss WHERE ss = :sourceService AND es.status = 'ACTIVE'")
    List<EventSubscription> findActiveSubscriptionsBySourceService(@Param("sourceService") String sourceService);

    /**
     * Find event subscriptions by name pattern
     */
    List<EventSubscription> findByNameContainingIgnoreCase(String namePattern);

    /**
     * Find event subscriptions for a user by name pattern
     */
    List<EventSubscription> findByUserIdAndNameContainingIgnoreCase(UUID userId, String namePattern);

    /**
     * Check if a subscription with the given name already exists for a user
     */
    boolean existsByUserIdAndName(UUID userId, String name);

    /**
     * Find active subscriptions that could match an event based on event type and source service
     */
    @Query("SELECT DISTINCT es FROM EventSubscription es " +
           "LEFT JOIN es.eventTypes et " +
           "LEFT JOIN es.sourceServices ss " +
           "WHERE es.status = 'ACTIVE' " +
           "AND (et = :eventType OR es.eventTypes IS EMPTY) " +
           "AND (ss = :sourceService OR es.sourceServices IS EMPTY)")
    List<EventSubscription> findPotentialMatchingSubscriptions(
            @Param("eventType") EventType eventType, 
            @Param("sourceService") String sourceService);

    /**
     * Count active subscriptions for a user
     */
    long countByUserIdAndStatus(UUID userId, EventSubscriptionStatus status);

    /**
     * Find subscriptions created by a specific user
     */
    List<EventSubscription> findByCreatedByUserId(UUID createdByUserId);

    /**
     * Find subscriptions with a specific notification type enabled.
     *
     * <p>Native query: {@code jsonb_extract_path_text} is Postgres-specific
     * and Hibernate 6 cannot type the function result in JPQL (the previous
     * JPQL version failed semantic analysis at repository bootstrap, so the
     * whole context refused to start). {@code {h-schema}} resolves to the
     * configured {@code hibernate.default_schema}.
     */
    @Query(value = "SELECT * FROM {h-schema}event_subscriptions es "
            + "WHERE jsonb_extract_path_text(es.notification_config, :notificationType) = 'true'",
            nativeQuery = true)
    List<EventSubscription> findSubscriptionsWithNotificationType(@Param("notificationType") String notificationType);
} 