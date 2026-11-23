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

package com.dlap.reporting.service.notification.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dlap.reporting.dto.NotificationRequestDTO;
import com.dlap.reporting.model.NotificationChannel;
import com.dlap.reporting.model.UserNotificationPreference;
import com.dlap.reporting.service.notification.INotificationProvider;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;

@Service
public class SlackNotificationProvider implements INotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SlackNotificationProvider.class);

    private final MethodsClient slackClient;

    @Value("${app.notifications.slack.bot-token:}")
    private String slackBotToken;

    @Value("${app.notifications.slack.default-channel:#general}")
    private String defaultSlackChannel;

    // Keys for UserNotificationPreference.channelConfiguration
    private static final String SLACK_CHANNEL_ID_KEY = "channelId";
    private static final String SLACK_USER_ID_KEY = "userId"; // For DMs

    public SlackNotificationProvider(@Value("${app.notifications.slack.bot-token:}") String token) {
        if (StringUtils.hasText(token)) {
            this.slackClient = Slack.getInstance().methods(token);
            log.info("SlackNotificationProvider initialized with a token.");
        } else {
            this.slackClient = null;
            log.warn("SlackNotificationProvider initialized WITHOUT a token. Slack notifications will be disabled.");
        }
        this.slackBotToken = token; // Ensure field is set
    }

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.SLACK;
    }

    @Async
    @Override
    public void send(UserNotificationPreference preference, NotificationRequestDTO request) {
        if (slackClient == null || !StringUtils.hasText(slackBotToken)) {
            log.warn("Slack client not initialized or token missing; cannot send notification. Subject: {}", request.getSubject());
            return;
        }

        String targetChannelId = determineSlackTarget(preference);
        if (targetChannelId == null) {
            log.warn("No Slack target (channel/user) found for user {} in preference {}. Cannot send Slack notification.",
                    preference.getUserId(), preference.getId());
            return;
        }
        postMessage(targetChannelId, request.getSubject(), request.getBody(), request.getContentModel());
    }

    @Async
    @Override
    public void sendDirect(NotificationRequestDTO request, String targetChannelOrUserId) {
        if (slackClient == null || !StringUtils.hasText(slackBotToken)) {
            log.warn("Slack client not initialized or token missing; cannot send direct notification. Subject: {}", request.getSubject());
            return;
        }
        postMessage(targetChannelOrUserId, request.getSubject(), request.getBody(), request.getContentModel());
    }

    private String determineSlackTarget(UserNotificationPreference preference) {
        if (preference.getChannelConfiguration() != null) {
            if (preference.getChannelConfiguration().containsKey(SLACK_USER_ID_KEY)) {
                return preference.getChannelConfiguration().get(SLACK_USER_ID_KEY); // DM to user
            }
            if (preference.getChannelConfiguration().containsKey(SLACK_CHANNEL_ID_KEY)) {
                return preference.getChannelConfiguration().get(SLACK_CHANNEL_ID_KEY); // Post to channel
            }
        }
        log.warn("Slack target (userId or channelId) not found in preference channelConfiguration for user {}. Using default channel: {}", 
                 preference.getUserId(), defaultSlackChannel);
        return defaultSlackChannel; // Fallback to default channel if specific target not in preference
    }

    private void postMessage(String channelId, String subject, String body, Object contentModel) {
        try {
            List<LayoutBlock> blocks = new ArrayList<>();
            // Basic formatting: Subject as header (kind of), body as section
            blocks.add(SectionBlock.builder().text(MarkdownTextObject.builder().text("*" + subject + "*").build()).build());
            blocks.add(SectionBlock.builder().text(MarkdownTextObject.builder().text(body).build()).build());

            // TODO: More sophisticated block building based on contentModel if provided
            // Example: if contentModel has "blocks", use those directly
            // if (contentModel instanceof List) { ... build blocks ... }

            ChatPostMessageRequest request = ChatPostMessageRequest.builder()
                    .channel(channelId)
                    .text(subject + ": " + body) // Fallback text for notifications
                    .blocks(blocks)
                    .build();

            slackClient.chatPostMessage(request);
            log.info("Slack message sent to channel/user {} with subject: {}", channelId, subject);
        } catch (IOException | SlackApiException e) {
            log.error("Failed to send Slack message to {} with subject: {}. Error: {}", channelId, subject, e.getMessage(), e);
        }
    }
} 