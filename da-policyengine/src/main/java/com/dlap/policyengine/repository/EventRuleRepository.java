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
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.policyengine.model.EventRule;

/**
 * Repository interface for EventRule entities.
 * Provides data access methods for event rule management.
 */
@Repository
public interface EventRuleRepository extends JpaRepository<EventRule, UUID> {

    /**
     * Find all rules for a specific subscription
     */
    List<EventRule> findBySubscriptionId(UUID subscriptionId);

    /**
     * Find enabled rules for a specific subscription ordered by priority
     */
    List<EventRule> findBySubscriptionIdAndEnabledTrueOrderByPriorityAsc(UUID subscriptionId);

    /**
     * Find rules by name pattern
     */
    List<EventRule> findByNameContainingIgnoreCase(String namePattern);

    /**
     * Find rules for a subscription by name pattern
     */
    List<EventRule> findBySubscriptionIdAndNameContainingIgnoreCase(UUID subscriptionId, String namePattern);

    /**
     * Check if a rule with the given name already exists for a subscription
     */
    boolean existsBySubscriptionIdAndName(UUID subscriptionId, String name);

    /**
     * Find rules by enabled status
     */
    List<EventRule> findByEnabled(Boolean enabled);

    /**
     * Find rules with a specific priority
     */
    List<EventRule> findByPriority(Integer priority);

    /**
     * Find rules created by a specific user
     */
    List<EventRule> findByCreatedByUserId(UUID createdByUserId);

    /**
     * Count rules for a specific subscription
     */
    long countBySubscriptionId(UUID subscriptionId);

    /**
     * Count enabled rules for a specific subscription
     */
    long countBySubscriptionIdAndEnabledTrue(UUID subscriptionId);

    /**
     * Find rules that contain a specific condition pattern
     */
    @Query("SELECT er FROM EventRule er WHERE er.condition LIKE %:conditionPattern%")
    List<EventRule> findByConditionContaining(@Param("conditionPattern") String conditionPattern);

    /**
     * Find the highest priority rule for a subscription
     */
    @Query("SELECT er FROM EventRule er WHERE er.subscription.id = :subscriptionId AND er.enabled = true ORDER BY er.priority ASC LIMIT 1")
    EventRule findHighestPriorityEnabledRule(@Param("subscriptionId") UUID subscriptionId);

    /**
     * Find rules for a subscription ordered by priority
     */
    List<EventRule> findBySubscriptionIdOrderByPriorityAsc(UUID subscriptionId);

    @Modifying
    @Query("delete from EventRule r where r.subscription.id = :subscriptionId")
    void deleteAllBySubscriptionId(@Param("subscriptionId") UUID subscriptionId);
} 