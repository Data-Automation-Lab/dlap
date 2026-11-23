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

package com.dlap.logingest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/v1/logingest/events}. Used by:
 *
 * <ul>
 *   <li>Tests (synthetic events).</li>
 *   <li>Operators replaying events from cold storage.</li>
 *   <li>Per-cloud adapter loops (subsequent milestones may have
 *       adapters call back into this endpoint instead of publishing
 *       directly to Kafka - kept open as an option).</li>
 * </ul>
 *
 * <p>Field semantics match {@code dlap.log.v1.DlapLogEvent}; this DTO
 * is the JSON-friendly mirror of the protobuf message. The service
 * normalizes the DTO into the protobuf shape before publishing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestRequestDTO {

    @NotBlank
    private String sourceProvider; // GCP, AWS, AZURE, OCI

    private String sourceService;

    @NotBlank
    private String operation;

    @NotBlank
    private String changeType; // CREATE, UPDATE, DELETE, ACCESS, PERMISSION

    private String resultStatus; // SUCCESS, FAILURE, PENDING

    @NotNull
    private Instant eventTime;

    private String principalId;
    private String principalType;
    private String principalDisplayName;
    private String principalAccountId;

    private String targetResourceKind;
    private String targetResourceId;
    private String targetResourceName;
    private String targetProject;
    private String targetRegion;

    private String callerIp;
    private String userAgent;
    private String requestId;
    private String traceId;

    /** Raw cloud event, stored verbatim per spec. */
    private Map<String, Object> rawEvent;
}
