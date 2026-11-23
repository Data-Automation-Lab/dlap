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

package com.dlap.autocompliance.model.repository;

import com.dlap.autocompliance.model.entity.ComplianceGeneratedReportEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComplianceGeneratedReportRepository extends JpaRepository<ComplianceGeneratedReportEntity, String> {
    // String is the type of the ID (reportId)

    Page<ComplianceGeneratedReportEntity> findByReportTypeIgnoreCase(String reportType, Pageable pageable);

    // Example of a query that might be needed to get asset status (though this is complex for JSONB)
    // This is a conceptual query; actual JSONB querying capabilities depend heavily on the database (e.g., PostgreSQL).
    // It's often better to process this in the service layer after fetching relevant reports, 
    // or use a dedicated search index if performance is critical.
    @Query(value = "SELECT cgr.* FROM compliance_generated_reports cgr " +
                   "WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(cgr.findings_data) AS finding " +
                                 "WHERE finding ->> 'assetId' = :assetId)",
           nativeQuery = true)
    List<ComplianceGeneratedReportEntity> findReportsContainingAssetId(@Param("assetId") String assetId);
} 