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

package com.dlap.reporting.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.dlap.reporting.model.GeneratedReport;
import com.dlap.reporting.model.ReportDefinition;
import com.dlap.reporting.model.ReportStatus;

@Repository
public interface GeneratedReportRepository extends JpaRepository<GeneratedReport, UUID>, JpaSpecificationExecutor<GeneratedReport> {
    Page<GeneratedReport> findByReportDefinition(ReportDefinition reportDefinition, Pageable pageable);
    Page<GeneratedReport> findByStatus(ReportStatus status, Pageable pageable);
    Page<GeneratedReport> findByRequestedByUserId(UUID userId, Pageable pageable);
} 