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

package com.dlap.discovery.log.service.gcp.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.dlap.discovery.log.service.gcp.config.GcpLogSourceType;
import com.dlap.discovery.log.service.gcp.persistence.entity.GcpLogSourceDetail;

/**
 * Repository interface for GcpLogSourceDetail entities.
 * Provides methods to save and retrieve log source configuration details.
 */
@Repository
public interface IGcpLogSourceDetailRepository extends JpaRepository<GcpLogSourceDetail, Long> {

    /**
     * Find source details by account ID and source type.
     * 
     * @param accountId  The account ID
     * @param sourceType The log source type
     * @return Optional containing the source details if found
     */
    Optional<GcpLogSourceDetail> findByAccountIdAndSourceType(String accountId, GcpLogSourceType sourceType);

    /**
     * Find source details by account ID.
     * 
     * @param accountId The account ID
     * @return List of all source details for the account
     */
    java.util.List<GcpLogSourceDetail> findByAccountId(String accountId);

    /**
     * Delete source details for a specific account.
     * 
     * @param accountId The account ID
     * @return The number of records deleted
     */
    long deleteByAccountId(String accountId);
}