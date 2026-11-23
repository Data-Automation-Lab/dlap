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

package com.dlap.autodelete.service.storage;

import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of deletion verification for compliance purposes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeletionVerificationResult {
    
    private String operationId;
    private boolean isCompletelyDeleted;
    private boolean isCompliant;
    private List<String> complianceStandards; // e.g., ["GDPR", "HIPAA"]
    private LocalDateTime verificationTimestamp;
    private String verificationMethod;
    private List<String> remainingTraces; // Any remaining data traces found
    private String certificateUrl; // URL to compliance certificate if available
    private String auditTrail; // Audit trail information
} 