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

import com.dlap.reporting.model.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class NotificationRequestDTO {
    // Target specific user(s) by their IDs
    private List<UUID> targetUserIds;

    // Or target a specific notification type key (service will find subscribed users)
    private String notificationTypeKey;

    @NotEmpty(message = "At least one notification channel must be specified.")
    private List<NotificationChannel> channels; // e.g., [EMAIL, SLACK]

    @NotBlank(message = "Subject cannot be blank")
    private String subject;

    @NotBlank(message = "Body cannot be blank")
    private String body;

    // Optional: For rich content or structured data in notifications (e.g., Slack blocks, email template model)
    private Map<String, Object> contentModel;

    // Optional: Specific channel configurations to use for this notification, overriding user preferences or defaults.
    // Key: "EMAIL" or "SLACK". Value: Map, e.g. for SLACK: {"targetChannelId": "#alerts"}
    private Map<String, Map<String, String>> channelOverrides;
} 