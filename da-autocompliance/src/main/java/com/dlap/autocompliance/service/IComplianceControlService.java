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

import com.dlap.autocompliance.dto.ComplianceControlDTO;
import com.dlap.autocompliance.dto.ControlEvaluationRequestDTO;
import com.dlap.autocompliance.dto.ControlEvaluationResponseDTO;
import java.util.List;

/**
 * Control management surface of the compliance service (R-P1-12 / ADR-0020 split).
 *
 * <p>CRUD + async evaluation for {@code ComplianceControl} entities.
 * Owns the {@code ComplianceControlRepository} +
 * {@code ControlEvaluationJobRepository} mappings; nothing else in the
 * split touches them.
 */
public interface IComplianceControlService {

    List<ComplianceControlDTO> listAvailableControls();

    ComplianceControlDTO createComplianceControl(ComplianceControlDTO controlDto);

    ComplianceControlDTO updateComplianceControl(String controlId, ComplianceControlDTO controlDto);

    void deleteComplianceControl(String controlId);

    /** Submit an async control evaluation; returns the job id immediately. */
    ControlEvaluationResponseDTO evaluateControl(String controlId, ControlEvaluationRequestDTO request);

    ControlEvaluationResponseDTO getControlEvaluationJobStatus(String jobId);

    /** Idempotent control-table bootstrap (currently a log-and-skip; reserved for default-data seeding). */
    void initDefaultControls();
}
