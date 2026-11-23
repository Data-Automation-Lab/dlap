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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.dlap.autocompliance.dto.MonitoredRegulationDTO;
import com.dlap.autocompliance.model.entity.ComplianceReportDefinitionEntity;
import com.dlap.autocompliance.model.repository.ComplianceControlRepository;
import com.dlap.autocompliance.model.repository.ComplianceGeneratedReportRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportDefinitionRepository;

/**
 * Unit tests for the focused KPI-slice impl. Migrated from
 * {@code ComplianceServiceImplTest} when the N1 phase-2 move pulled the
 * KPI method bodies out of the facade (the original test NPE'd against
 * the delegating facade).
 */
@ExtendWith(MockitoExtension.class)
class ComplianceKpiServiceImplTest {

    @Mock
    private ComplianceControlRepository controlRepository;
    @Mock
    private ComplianceGeneratedReportRepository generatedReportRepository;
    @Mock
    private ComplianceReportDefinitionRepository reportDefinitionRepository;

    @InjectMocks
    private ComplianceKpiServiceImpl kpiService;

    @Test
    void getMonitoredRegulations_usesUnpagedRepositoryCalls() {
        ComplianceReportDefinitionEntity def = ComplianceReportDefinitionEntity.builder()
                .reportType("HIPAA")
                .displayName("HIPAA")
                .version("1")
                .build();
        when(reportDefinitionRepository.findAll()).thenReturn(List.of(def));
        when(generatedReportRepository.findByReportTypeIgnoreCase(eq("HIPAA"), argThat(Pageable::isUnpaged)))
                .thenReturn(new PageImpl<>(List.of()));
        when(controlRepository.findAll()).thenReturn(List.of());

        List<MonitoredRegulationDTO> regs = kpiService.getMonitoredRegulations();
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).getId()).isEqualTo("HIPAA");
    }
}
