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

package com.dlap.autocompliance.service;

import com.dlap.autocompliance.dto.ComplianceHeatmapDTO;
import com.dlap.autocompliance.dto.ComplianceKpisDTO;
import com.dlap.autocompliance.dto.ComplianceTrendDTO;
import com.dlap.autocompliance.dto.MonitoredRegulationDTO;
import java.util.List;

/**
 * KPI / dashboard surface of the compliance service (R-P1-12 / ADR-0020 split).
 *
 * <p>One of four focused services that {@code ComplianceServiceImpl}
 * (the 1188-line god-class) delegates to. This interface carves out
 * read-only metrics + dashboard aggregations: compliance score
 * computation, time-series trends, heatmap by framework, monitored
 * regulations catalog. No mutations.
 */
public interface IComplianceKpiService {

    /** Latest compliance KPIs (overall score, violation counts, last-scan time, etc.). */
    ComplianceKpisDTO getComplianceKpis();

    /** Time-series compliance trends, default 30 / 90 day windows. */
    List<ComplianceTrendDTO> getComplianceTrends();

    /** Per-framework / per-region compliance heatmap. */
    List<ComplianceHeatmapDTO> getComplianceHeatmap();

    /** Catalog of regulations the platform monitors (GDPR / HIPAA / SOC 2 / PCI-DSS / CCPA). */
    List<MonitoredRegulationDTO> getMonitoredRegulations();
}
