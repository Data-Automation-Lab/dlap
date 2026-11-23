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

package com.dlap.discovery.common.model.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.dlap.discovery.common.model.CheckpointEntity;

/**
 * Repository for managing checkpoint entities.
 */
@Repository
public interface ICheckpointRepository extends JpaRepository<CheckpointEntity, String> {

    /**
     * Finds checkpoints for a specific provider.
     *
     * @param provider The cloud provider
     * @return A list of checkpoints for that provider
     */
    List<CheckpointEntity> findByProvider(String provider);

    /**
     * Finds checkpoints for a specific account.
     *
     * @param accountId The account ID
     * @return A list of checkpoints for that account
     */
    List<CheckpointEntity> findByAccountId(String accountId);
    
    /**
     * Finds checkpoints for a specific provider and account.
     *
     * @param provider  The cloud provider
     * @param accountId The account ID
     * @return A list of checkpoints for that provider and account
     */
    List<CheckpointEntity> findByProviderAndAccountId(String provider, String accountId);
    
    /**
     * Finds checkpoints updated after a specific time.
     *
     * @param lastUpdated The timestamp threshold
     * @return A list of recently updated checkpoints
     */
    List<CheckpointEntity> findByLastUpdatedAfter(Instant lastUpdated);
} 