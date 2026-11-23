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

package com.dlap.autodelete.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO for representing the auto-deletion configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeletionConfigDTO {

    /** Longest retention accepted: one hundred years, in days. */
    public static final long MAX_RETENTION_DAYS = 36_500L;

    /** Most addresses the error notification list may hold. */
    public static final int MAX_NOTIFICATION_EMAILS = 50;

    @NotNull
    private Boolean enabled; // Global enable/disable for auto-deletion
    private boolean softDeleteByDefault; // If true, performs a soft delete (marks as deleted)
    @Min(0)
    @Max(MAX_RETENTION_DAYS)
    private Long defaultSoftDeleteRetentionDays; // If soft-deleted, how long before permanent deletion (if applicable)
    private boolean requireApprovalForHardDelete; // If manual approval is needed for permanent (hard) deletion
    private boolean requireApprovalForSoftDelete; // If manual approval is needed for soft deletion

    // Could define specific deletion strategies or grace periods per data source type or classification
    // For example: Map<String, DeletionStrategy> deletionStrategies;
    // DeletionStrategy could include things like: immediate, after X days, notify and wait, etc.
    @Size(max = MAX_NOTIFICATION_EMAILS)
    private List<@NotBlank @Email @Size(max = 254) String> notificationEmailsOnError; // Who to notify if deletion task fails critically
} 