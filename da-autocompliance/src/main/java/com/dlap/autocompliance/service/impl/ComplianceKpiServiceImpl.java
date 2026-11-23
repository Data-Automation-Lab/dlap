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

package com.dlap.autocompliance.service.impl;

import com.dlap.autocompliance.dto.ComplianceHeatmapDTO;
import com.dlap.autocompliance.dto.ComplianceKpisDTO;
import com.dlap.autocompliance.dto.ComplianceTrendDTO;
import com.dlap.autocompliance.dto.MonitoredRegulationDTO;
import com.dlap.autocompliance.model.entity.ComplianceGeneratedReportEntity;
import com.dlap.autocompliance.model.entity.ComplianceReportDefinitionEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;
import com.dlap.autocompliance.model.repository.ComplianceControlRepository;
import com.dlap.autocompliance.model.repository.ComplianceGeneratedReportRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportDefinitionRepository;
import com.dlap.autocompliance.service.IComplianceKpiService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * R-P1-12 / ADR-0024 phase-2 (v0.2.0 N1): focused impl of the KPI /
 * dashboard slice. Method bodies recovered from the pre-N1
 * {@code ComplianceServiceImpl} god-class (commit 32777de^) after the
 * N1 move commit truncated this file. The facade
 * ({@code ComplianceServiceImpl}) delegates the four read-side
 * dashboard methods here.
 *
 * <p>Owns overall KPIs, weekly trends, regulation heatmap, and
 * monitored regulations, plus their pure-function helpers (category
 * mapping, risk-level scoring, resolution-time aggregation, weekly
 * compliance score / violations). Reads from the control, generated-
 * report, and report-definition repositories.
 */
@Service
@RequiredArgsConstructor
public class ComplianceKpiServiceImpl implements IComplianceKpiService {

    private final ComplianceControlRepository controlRepository;
    private final ComplianceGeneratedReportRepository generatedReportRepository;
    private final ComplianceReportDefinitionRepository reportDefinitionRepository;

    @Override
    @Transactional(readOnly = true)
    public ComplianceKpisDTO getComplianceKpis() {
        List<ComplianceGeneratedReportEntity> reports = generatedReportRepository.findAll();
        
        int totalChecks = 0;
        int compliantChecks = 0;
        int nonCompliantChecks = 0;
        int criticalViolations = 0;
        int resolvedViolations = 0;
        int regulationsMonitored = 0;
        int highRiskAssets = 0;
        
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        LocalDateTime last7Days = LocalDateTime.now().minusDays(7);
        
        int violationsLast24h = 0;
        int violationsLast7Days = 0;
        
        // Calculate from actual report data
        for (ComplianceGeneratedReportEntity report : reports) {
            if (report.getFindingsData() != null) {
                for (ReportFindingData finding : report.getFindingsData()) {
                    totalChecks++;
                    
                    if ("COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                        compliantChecks++;
                    } else if ("NON_COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                        nonCompliantChecks++;
                        
                        if ("CRITICAL".equalsIgnoreCase(finding.getSeverity())) {
                            criticalViolations++;
                        }
                        
                        if (report.getGenerationTimestamp().isAfter(last24h)) {
                            violationsLast24h++;
                        }
                        if (report.getGenerationTimestamp().isAfter(last7Days)) {
                            violationsLast7Days++;
                        }
                    }
                }
            }
        }
        
        // Calculate compliance score from actual finding data (compliant / total ratio).
        double overallComplianceScore = totalChecks > 0 ? (double) compliantChecks / totalChecks * 100 : 0;

        // activeChecks reflects the number of enabled compliance controls the
        // platform is monitoring (the seeded controls), independent of how many
        // findings a scan has produced. Empty controls table -> honest 0.
        int activeChecks = (int) controlRepository.findByEnabledTrue().size();

        // Count unique regulations from actual reports
        regulationsMonitored = (int) reports.stream()
                .map(ComplianceGeneratedReportEntity::getReportType)
                .distinct()
                .count();
        
        // Calculate high risk assets from actual data
        highRiskAssets = (int) reports.stream()
                .flatMap(report -> report.getFindingsData() != null ? report.getFindingsData().stream() : java.util.stream.Stream.empty())
                .filter(finding -> "CRITICAL".equalsIgnoreCase(finding.getSeverity()) && "NON_COMPLIANT".equalsIgnoreCase(finding.getStatus()))
                .map(ReportFindingData::getAssetId)
                .distinct()
                .count();
        
        // Calculate average resolution time from actual data
        double averageResolutionTime = calculateAverageResolutionTime(reports);
        
        // Get last scan time from actual data
        String lastScanTime = calculateLastScanTime(reports);
        
        return ComplianceKpisDTO.builder()
                .overallComplianceScore(Math.round(overallComplianceScore * 10.0) / 10.0)
                .activeChecks(activeChecks)
                .violationsLast24h(violationsLast24h)
                .violationsLast7Days(violationsLast7Days)
                .criticalViolations(criticalViolations)
                .resolvedViolations(resolvedViolations)
                .averageResolutionTime(averageResolutionTime)
                // Flag the resolution metric as unmeasured: no per-violation
                // resolution-timestamp tracking exists yet, so averageResolutionTime
                // is a placeholder, not a measured value. See calculateAverageResolutionTime.
                .resolutionMetricsAvailable(false)
                .lastScanTime(lastScanTime)
                .regulationsMonitored(regulationsMonitored)
                .highRiskAssets(highRiskAssets)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComplianceTrendDTO> getComplianceTrends() {
        List<ComplianceGeneratedReportEntity> reports = generatedReportRepository.findAll();
        List<ComplianceTrendDTO> trends = new ArrayList<>();
        
        if (reports.isEmpty()) {
            // Return empty trends if no data available
            return trends;
        }
        
        // Group reports by week for the last 7 weeks
        LocalDate endDate = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        for (int i = 6; i >= 0; i--) {
            LocalDate weekStart = endDate.minusWeeks(i).with(java.time.DayOfWeek.MONDAY);
            LocalDate weekEnd = weekStart.plusDays(6);
            
            String dateStr = weekStart.format(formatter);
            
            // Calculate actual scores for this week from database data
            double overallScore = calculateWeeklyComplianceScore(reports, weekStart, weekEnd, null);
            double hipaaScore = calculateWeeklyComplianceScore(reports, weekStart, weekEnd, "HIPAA");
            double gdprScore = calculateWeeklyComplianceScore(reports, weekStart, weekEnd, "GDPR");
            double socScore = calculateWeeklyComplianceScore(reports, weekStart, weekEnd, "SOC");
            int violations = calculateWeeklyViolations(reports, weekStart, weekEnd);
            
            trends.add(ComplianceTrendDTO.builder()
                    .date(dateStr)
                    .overallScore(Math.round(overallScore * 10.0) / 10.0)
                    .hipaaScore(Math.round(hipaaScore * 10.0) / 10.0)
                    .gdprScore(Math.round(gdprScore * 10.0) / 10.0)
                    .socScore(Math.round(socScore * 10.0) / 10.0)
                    .violations(violations)
                    .build());
        }
        
        return trends;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComplianceHeatmapDTO> getComplianceHeatmap() {
        List<ComplianceGeneratedReportEntity> reports = generatedReportRepository.findAll();
        List<ComplianceHeatmapDTO> heatmap = new ArrayList<>();
        
        // Group findings by regulation and category
        Map<String, Map<String, List<ReportFindingData>>> groupedFindings = new HashMap<>();
        
        for (ComplianceGeneratedReportEntity report : reports) {
            if (report.getFindingsData() != null) {
                for (ReportFindingData finding : report.getFindingsData()) {
                    String regulation = report.getReportType();
                    String category = getCategoryFromCheckId(finding.getCheckId());
                    
                    groupedFindings.computeIfAbsent(regulation, k -> new HashMap<>())
                            .computeIfAbsent(category, k -> new ArrayList<>())
                            .add(finding);
                }
            }
        }
        
        // Convert to heatmap DTOs
        for (Map.Entry<String, Map<String, List<ReportFindingData>>> regulationEntry : groupedFindings.entrySet()) {
            String regulation = regulationEntry.getKey();
            
            for (Map.Entry<String, List<ReportFindingData>> categoryEntry : regulationEntry.getValue().entrySet()) {
                String category = categoryEntry.getKey();
                List<ReportFindingData> findings = categoryEntry.getValue();
                
                int totalFindings = findings.size();
                int compliantFindings = (int) findings.stream()
                        .filter(f -> "COMPLIANT".equalsIgnoreCase(f.getStatus()))
                        .count();
                int violations = totalFindings - compliantFindings;
                
                double score = totalFindings > 0 ? (double) compliantFindings / totalFindings * 100 : 0;
                String riskLevel = getRiskLevel(score, violations);
                
                heatmap.add(ComplianceHeatmapDTO.builder()
                        .regulation(regulation)
                        .category(category)
                        .score(Math.round(score * 10.0) / 10.0)
                        .violations(violations)
                        .riskLevel(riskLevel)
                        .build());
            }
        }
        
        return heatmap;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MonitoredRegulationDTO> getMonitoredRegulations() {
        List<ComplianceReportDefinitionEntity> definitions = reportDefinitionRepository.findAll();
        List<MonitoredRegulationDTO> regulations = new ArrayList<>();
        
        for (ComplianceReportDefinitionEntity definition : definitions) {
            // Calculate compliance score for this regulation from actual data
            // Use Pageable.unpaged() to request all rows; passing null here would
            // NPE inside Spring Data's page construction.
            List<ComplianceGeneratedReportEntity> reportsForRegulation = generatedReportRepository
                    .findByReportTypeIgnoreCase(definition.getReportType(), Pageable.unpaged())
                    .getContent();
            
            int totalChecks = 0;
            int compliantChecks = 0;
            
            for (ComplianceGeneratedReportEntity report : reportsForRegulation) {
                if (report.getFindingsData() != null) {
                    for (ReportFindingData finding : report.getFindingsData()) {
                        totalChecks++;
                        if ("COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                            compliantChecks++;
                        }
                    }
                }
            }
            
            double complianceScore = totalChecks > 0 ? (double) compliantChecks / totalChecks * 100 : 0;
            
            // Get rules count from controls for this regulation (simplified)
            int rulesCount = (int) controlRepository.findAll().stream()
                    .filter(control -> control.getApplicableFrameworks() != null && 
                            control.getApplicableFrameworks().contains(definition.getReportType()))
                    .count();
            
            // Get actual last updated from the most recent report for this regulation
            String lastUpdated = reportsForRegulation.stream()
                    .map(ComplianceGeneratedReportEntity::getGenerationTimestamp)
                    .max(LocalDateTime::compareTo)
                    .map(timestamp -> timestamp.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    .orElse("No reports generated");
            
            regulations.add(MonitoredRegulationDTO.builder()
                    .id(definition.getReportType())
                    .name(definition.getDisplayName())
                    .version(definition.getVersion())
                    .enabled(true) // Default to true since we don't have this field
                    .lastUpdated(lastUpdated)
                    .rulesCount(rulesCount)
                    .complianceScore(Math.round(complianceScore * 10.0) / 10.0)
                    .build());
        }
        
        return regulations;
    }

    // Helper methods
    private String getCategoryFromCheckId(String checkId) {
        if (checkId == null) return "Unknown";
        
        if (checkId.contains("ACCESS") || checkId.contains("AUTH")) {
            return "Data Access";
        } else if (checkId.contains("ENC") || checkId.contains("CRYPTO")) {
            return "Encryption";
        } else if (checkId.contains("LOG") || checkId.contains("AUDIT")) {
            return "Audit Logs";
        } else if (checkId.contains("CONSENT") || checkId.contains("PRIVACY")) {
            return "Consent Management";
        } else if (checkId.contains("PROCESSING") || checkId.contains("DATA")) {
            return "Data Processing";
        } else if (checkId.contains("SEC") || checkId.contains("SECURITY")) {
            return "Security";
        } else if (checkId.contains("AVAIL") || checkId.contains("UPTIME")) {
            return "Availability";
        } else if (checkId.contains("INTEGRITY") || checkId.contains("VALIDATION")) {
            return "Processing Integrity";
        } else {
            return "General";
        }
    }

    private String getRiskLevel(double score, int violations) {
        if (score >= 95 && violations == 0) {
            return "Low";
        } else if (score >= 85 && violations <= 2) {
            return "Medium";
        } else if (score >= 70 && violations <= 5) {
            return "High";
        } else {
            return "Critical";
        }
    }

    // Helper methods for calculating actual data
    /**
     * Placeholder for average violation resolution time. Returns {@code 0.0}
     * unconditionally because per-violation creation/resolution timestamps are
     * not yet persisted. Callers must treat the result as unmeasured and set
     * {@link ComplianceKpisDTO#isResolutionMetricsAvailable()} to {@code false}
     * until real tracking exists - do NOT surface this as a measured KPI.
     *
     * @param reports generated compliance reports (currently unused pending
     *                resolution-timestamp tracking)
     * @return {@code 0.0} placeholder
     */
    private double calculateAverageResolutionTime(List<ComplianceGeneratedReportEntity> reports) {
        // TODO: Implement actual resolution time calculation. This requires
        // tracking violation creation and resolution timestamps, which are not
        // yet persisted. Until then the value is a placeholder, flagged unmeasured
        // via ComplianceKpisDTO.resolutionMetricsAvailable=false.
        return 0.0;
    }
    
    private String calculateLastScanTime(List<ComplianceGeneratedReportEntity> reports) {
        if (reports.isEmpty()) {
            return "No scans performed";
        }
        
        // Find the most recent report generation timestamp
        LocalDateTime lastScan = reports.stream()
                .map(ComplianceGeneratedReportEntity::getGenerationTimestamp)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now());
        
        // Calculate time difference
        Duration duration = Duration.between(lastScan, LocalDateTime.now());
        
        if (duration.toMinutes() < 60) {
            return duration.toMinutes() + " minutes ago";
        } else if (duration.toHours() < 24) {
            return duration.toHours() + " hours ago";
        } else {
            return duration.toDays() + " days ago";
        }
    }

    // Helper methods for trend calculations
    private double calculateWeeklyComplianceScore(List<ComplianceGeneratedReportEntity> reports, LocalDate weekStart, LocalDate weekEnd, String regulationType) {
        int totalChecks = 0;
        int compliantChecks = 0;
        
        for (ComplianceGeneratedReportEntity report : reports) {
            LocalDate reportDate = report.getGenerationTimestamp().toLocalDate();
            
            // Check if report is within the week and matches regulation type (if specified)
            if (reportDate.isAfter(weekStart.minusDays(1)) && reportDate.isBefore(weekEnd.plusDays(1))) {
                if (regulationType == null || report.getReportType().contains(regulationType)) {
                    if (report.getFindingsData() != null) {
                        for (ReportFindingData finding : report.getFindingsData()) {
                            totalChecks++;
                            if ("COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                                compliantChecks++;
                            }
                        }
                    }
                }
            }
        }
        
        return totalChecks > 0 ? (double) compliantChecks / totalChecks * 100 : 0;
    }
    
    private int calculateWeeklyViolations(List<ComplianceGeneratedReportEntity> reports, LocalDate weekStart, LocalDate weekEnd) {
        int violations = 0;
        
        for (ComplianceGeneratedReportEntity report : reports) {
            LocalDate reportDate = report.getGenerationTimestamp().toLocalDate();
            
            if (reportDate.isAfter(weekStart.minusDays(1)) && reportDate.isBefore(weekEnd.plusDays(1))) {
                if (report.getFindingsData() != null) {
                    for (ReportFindingData finding : report.getFindingsData()) {
                        if ("NON_COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                            violations++;
                        }
                    }
                }
            }
        }
        
        return violations;
    }
}
