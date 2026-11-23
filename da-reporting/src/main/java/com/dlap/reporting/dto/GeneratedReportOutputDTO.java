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

package com.dlap.reporting.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.dlap.reporting.model.ReportStatus;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GeneratedReportOutputDTO {
    private UUID id;
    private String reportKey; // From associated ReportDefinition
    private String reportDisplayName; // From associated ReportDefinition
    private ReportStatus status;
    private Map<String, Object> generationParameters;
    private Object reportContent; // Can be String (URI, CSV) or Map/Object (JSON)
    private String failureReason;
    private Instant requestedAt;
    private Instant startedAt;
    private Instant completedAt;
    private UUID requestedByUserId;
} 