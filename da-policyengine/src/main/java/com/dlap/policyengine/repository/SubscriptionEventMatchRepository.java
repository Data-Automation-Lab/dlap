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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.policyengine.model.SubscriptionEventMatch;

@Repository
public interface SubscriptionEventMatchRepository extends JpaRepository<SubscriptionEventMatch, UUID> {

    long countBySubscriptionId(UUID subscriptionId);

    @Query("select count(m) from SubscriptionEventMatch m where m.subscriptionId in (select s.id from EventSubscription s where s.userId = :userId)")
    long countMatchesForUser(@Param("userId") UUID userId);

    @Query("select count(m) from SubscriptionEventMatch m where m.subscriptionId in (select s.id from EventSubscription s where s.userId = :userId) and m.matchedAt >= :since")
    long countMatchesForUserSince(@Param("userId") UUID userId, @Param("since") Instant since);

    @Query("select count(m) from SubscriptionEventMatch m where m.subscriptionId in (select s.id from EventSubscription s where s.userId = :userId) and m.matchedAt >= :from and m.matchedAt < :to")
    long countMatchesForUserInRange(@Param("userId") UUID userId, @Param("from") Instant from, @Param("to") Instant to);

    Optional<SubscriptionEventMatch> findFirstBySubscriptionIdOrderByMatchedAtDesc(UUID subscriptionId);

    void deleteBySubscriptionId(UUID subscriptionId);

    @Modifying
    @Query("delete from SubscriptionEventMatch m where m.ledgerEventId = :ledgerId")
    void deleteByLedgerEventId(@Param("ledgerId") UUID ledgerEventId);
}
