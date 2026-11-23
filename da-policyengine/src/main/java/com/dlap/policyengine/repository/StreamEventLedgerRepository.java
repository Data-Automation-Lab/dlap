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

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.policyengine.model.StreamEventLedger;

@Repository
public interface StreamEventLedgerRepository extends JpaRepository<StreamEventLedger, UUID> {

    Page<StreamEventLedger> findAllByOrderByTimestampDesc(Pageable pageable);

    @Query("""
            select l from StreamEventLedger l where l.id in (
              select m.ledgerEventId from SubscriptionEventMatch m where m.subscriptionId in (
                select s.id from EventSubscription s where s.userId = :userId and s.status = 'ACTIVE'
              )
            ) order by l.timestamp desc
            """)
    Page<StreamEventLedger> findForActiveUserSubscriptions(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            select l from StreamEventLedger l, SubscriptionEventMatch m
            where m.subscriptionId = :subscriptionId and m.ledgerEventId = l.id
            order by l.timestamp desc
            """)
    Page<StreamEventLedger> findForSubscription(@Param("subscriptionId") UUID subscriptionId, Pageable pageable);

    long count();

    @Query("select count(l) from StreamEventLedger l where l.timestamp >= :since")
    long countSince(@Param("since") java.time.Instant since);
}
