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

import com.dlap.autocompliance.dto.AssetComplianceStatusDTO;
import com.dlap.autocompliance.dto.ComplianceViolationDTO;
import java.util.List;

/**
 * Scan + violation surface of the compliance service (R-P1-12 / ADR-0020 split).
 *
 * <p>Manual + scheduled compliance scans, violation queries, per-asset
 * compliance status lookups. The async {@code processManualScanAsync}
 * pipeline lives here.
 */
public interface IComplianceScanService {

    List<ComplianceViolationDTO> getComplianceViolations();

    AssetComplianceStatusDTO getAssetComplianceStatus(String assetId);

    /** Submit a manual compliance scan; returns the job id. */
    String triggerComplianceScan();
}
