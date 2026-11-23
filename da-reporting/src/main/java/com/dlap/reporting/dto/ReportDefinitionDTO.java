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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ReportDefinitionDTO {
    private UUID id;

    @NotBlank(message = "Report key cannot be blank")
    @Size(max = 100, message = "Report key must be less than 100 characters")
    private String reportKey;

    @NotBlank(message = "Display name cannot be blank")
    @Size(max = 255, message = "Display name must be less than 255 characters")
    private String displayName;

    @Size(max = 1000, message = "Description must be less than 1000 characters")
    private String description;

    private Map<String, Object> defaultParameters;

    @Size(max = 100, message = "Default schedule cron must be less than 100 characters")
    private String defaultScheduleCron;

    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
} 