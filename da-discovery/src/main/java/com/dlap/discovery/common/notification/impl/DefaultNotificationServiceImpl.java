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

package com.dlap.discovery.common.notification.impl;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.dlap.discovery.common.notification.INotificationService;
import com.dlap.discovery.common.notification.dto.NotificationConfigDTO;
import com.dlap.discovery.common.notification.dto.NotificationDTO;
import com.dlap.discovery.crawler.service.event.dto.DiscoveryEventDTO;
import com.dlap.discovery.event.service.NotificationMessageBuilder;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Default implementation of the INotificationService interface.
 * Provides functionality for sending notifications through various channels
 * including email, Slack, and Microsoft Teams.
 */
@Service
public class DefaultNotificationServiceImpl implements INotificationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultNotificationServiceImpl.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    @Autowired(required = false)
    private JavaMailSender mailSender;

    private final ExecutorService notificationExecutor;

    // Configuration properties with default values
    private final Map<NotificationChannel, Boolean> enabledChannels = new ConcurrentHashMap<>();
    private final Map<NotificationChannel, Map<String, Object>> channelProperties = new ConcurrentHashMap<>();

    @Value("${notification.default.email.recipient:alerts@example.com}")
    private String defaultEmailRecipient;

    @Value("${notification.default.slack.channel:#alerts}")
    private String defaultSlackChannel;

    @Value("${notification.default.teams.channel:AlertsChannel}")
    private String defaultTeamsChannel;

    @Value("${notification.default.email.from:no-reply@example.com}")
    private String defaultEmailFrom;

    /**
     * Constructor initializes the notification service with default settings.
     */
    public DefaultNotificationServiceImpl() {
        // Initialize executor service for async notification sending
        this.notificationExecutor = Executors.newFixedThreadPool(5);

        // Set default enabled state for channels
        for (NotificationChannel channel : NotificationChannel.values()) {
            enabledChannels.put(channel, channel == NotificationChannel.EMAIL);
            channelProperties.put(channel, new HashMap<>());
        }

        log.info("Notification service initialized with default configuration");
    }

    @Override
    public boolean sendNotification(NotificationDTO notification, NotificationChannel channel) {
        if (notification == null) {
            log.warn("Cannot send null notification");
            return false;
        }

        if (channel == null) {
            log.warn("Cannot send notification with null channel");
            return false;
        }

        if (!isChannelEnabled(channel)) {
            log.info("Notification channel {} is disabled, skipping notification: {}",
                    channel, notification.getNotificationId());
            return false;
        }

        log.debug("Sending notification {} via {}: {}",
                notification.getNotificationId(), channel, notification.getTitle());

        try {
            // Convert NotificationDTO to the format needed by specific channels
            String subject = notification.getTitle();
            String message = formatMessage(notification);
            List<String> recipients = notification.getTargetChannels();

            // Send via specified channel
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    switch (channel) {
                        case EMAIL:
                            sendEmail(subject, message, recipients);
                            break;
                        case SLACK:
                            sendSlackMessage(subject, message, recipients);
                            break;
                        case TEAMS:
                            sendTeamsMessage(subject, message, recipients);
                            break;
                        default:
                            log.warn("Unsupported notification channel: {}", channel);
                            return false;
                    }
                    return true;
                } catch (Exception e) {
                    log.error("Failed to send notification via {}: {}", channel, e.getMessage(), e);
                    return false;
                }
            }, notificationExecutor);

            return future.join(); // Wait for completion
        } catch (Exception e) {
            log.error("Error preparing notification for channel {}: {}", channel, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void configureNotificationChannels(NotificationConfigDTO config) {
        if (config == null) {
            log.warn("Null configuration provided, ignoring");
            return;
        }

        log.info("Configuring notification channels");

        // Configure channel-specific settings
        for (NotificationConfigDTO.ChannelConfig channelConfig : config.getChannels()) {
            try {
                NotificationChannel channel = NotificationChannel.valueOf(channelConfig.getChannelType());
                enabledChannels.put(channel, channelConfig.isEnabled());

                // Merge new properties with existing ones
                Map<String, Object> existingProps = channelProperties.getOrDefault(channel, new HashMap<>());
                existingProps.putAll(channelConfig.getProperties());
                channelProperties.put(channel, existingProps);

                log.info("Configured channel {}: enabled={}", channel, channelConfig.isEnabled());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown notification channel type: {}", channelConfig.getChannelType());
            }
        }

        // Apply default properties if provided
        if (config.getDefaultProperties() != null && !config.getDefaultProperties().isEmpty()) {
            // Extract default properties for all channels
            if (config.getDefaultProperties().containsKey("defaultEmailRecipient")) {
                defaultEmailRecipient = (String) config.getDefaultProperties().get("defaultEmailRecipient");
            }

            if (config.getDefaultProperties().containsKey("defaultSlackChannel")) {
                defaultSlackChannel = (String) config.getDefaultProperties().get("defaultSlackChannel");
            }

            if (config.getDefaultProperties().containsKey("defaultTeamsChannel")) {
                defaultTeamsChannel = (String) config.getDefaultProperties().get("defaultTeamsChannel");
            }

            if (config.getDefaultProperties().containsKey("defaultEmailFrom")) {
                defaultEmailFrom = (String) config.getDefaultProperties().get("defaultEmailFrom");
            }
        }
    }

    @Override
    public boolean isChannelEnabled(NotificationChannel channelType) {
        return enabledChannels.getOrDefault(channelType, false);
    }

    /**
     * Handles discovery events and sends notifications based on severity.
     * 
     * @param event The discovery event
     */
    public void handleDiscoveryEvent(DiscoveryEventDTO event) {
        if (event == null) {
            return;
        }

        // Only handle ERROR and CRITICAL events by default
        if (event.getSeverity() == DiscoveryEventDTO.EventSeverity.ERROR ||
                event.getSeverity() == DiscoveryEventDTO.EventSeverity.CRITICAL) {

            NotificationDTO notification = new NotificationDTO();
            notification.setTitle(event.getSeverity() + " Event: " + event.getEventType());

            // Use NotificationMessageBuilder instead of manual string building
            NotificationMessageBuilder messageBuilder = new NotificationMessageBuilder()
                    .withDateFormatter(DATE_FORMATTER)
                    .withEventDetails(
                            event.getEventType(),
                            event.getResourceId(),
                            event.getTimestamp(),
                            event.getPayload());

            notification.setMessage(messageBuilder.build());
            notification.setType(mapSeverityToNotificationType(event.getSeverity()));
            notification.setTimestamp(event.getTimestamp());

            // Send to all enabled channels
            for (NotificationChannel channel : NotificationChannel.values()) {
                if (isChannelEnabled(channel)) {
                    sendNotification(notification, channel);
                }
            }
        }
    }

    // --- Private Helper Methods ---

    /**
     * Maps DiscoveryEventDTO.EventSeverity to NotificationDTO.NotificationType.
     * 
     * @param severity The event severity
     * @return The corresponding notification type
     */
    private NotificationDTO.NotificationType mapSeverityToNotificationType(DiscoveryEventDTO.EventSeverity severity) {
        switch (severity) {
            case CRITICAL:
                return NotificationDTO.NotificationType.CRITICAL;
            case ERROR:
                return NotificationDTO.NotificationType.ERROR;
            case WARN:
                return NotificationDTO.NotificationType.WARNING;
            default:
                return NotificationDTO.NotificationType.INFO;
        }
    }

    /**
     * Formats a notification message with relevant details.
     * 
     * @param notification The notification to format
     * @return A formatted message string
     */
    private String formatMessage(NotificationDTO notification) {
        // Use NotificationMessageBuilder instead of manual string building
        NotificationMessageBuilder builder = new NotificationMessageBuilder()
                .withDateFormatter(DATE_FORMATTER);

        // Add message body
        builder.withField("Message", notification.getMessage());

        // Add notification metadata
        if (notification.getTimestamp() != null) {
            builder.withNotificationMetadata(
                    notification.getNotificationId(),
                    notification.getType().toString(),
                    notification.getTimestamp(),
                    String.valueOf(notification.getPriority()));
        }

        return builder.build();
    }

    /**
     * Sends a notification via email.
     * 
     * @param subject    The email subject
     * @param body       The email body
     * @param recipients List of recipient email addresses
     */
    private void sendEmail(String subject, String body, List<String> recipients) {
        if (mailSender == null) {
            log.warn("Email notification requested, but JavaMailSender is not configured");
            return;
        }

        List<String> actualRecipients = recipients != null && !recipients.isEmpty()
                ? recipients
                : List.of(defaultEmailRecipient);

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "utf-8");

            helper.setSubject(subject);
            mimeMessage.setContent(body, "text/plain; charset=utf-8");
            helper.setTo(actualRecipients.toArray(new String[0]));
            helper.setFrom(defaultEmailFrom);

            mailSender.send(mimeMessage);
            log.info("Email notification sent successfully to: {}", String.join(", ", actualRecipients));
        } catch (MessagingException e) {
            log.error("Failed to send email notification: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send email notification", e);
        }
    }

    /**
     * Sends a notification via Slack.
     * 
     * @param subject  The message subject/title
     * @param message  The message body
     * @param channels List of Slack channels
     */
    private void sendSlackMessage(String subject, String message, List<String> channels) {
        List<String> targetChannels = channels != null && !channels.isEmpty()
                ? channels
                : List.of(defaultSlackChannel);

        // Format message for Slack (markdown-style formatting)
        String formattedMessage = String.format("*%s*\n%s", subject, message);

        // Get Slack-specific properties
        Map<String, Object> slackProps = channelProperties.getOrDefault(NotificationChannel.SLACK, new HashMap<>());
        String slackToken = (String) slackProps.getOrDefault("token", "");
        String slackUsername = (String) slackProps.getOrDefault("username", "NotificationBot");

        if (slackToken.isEmpty()) {
            log.warn("Slack notification requested, but no token is configured");
            return;
        }

        try {
            // This is a placeholder for actual Slack API integration
            // In a real implementation, you would use the Slack Java SDK or their HTTP API

            for (String channel : targetChannels) {
                log.info("Would send Slack message to channel {} with username {}",
                        channel, slackUsername);

                // Example of how real implementation might look:
                // SlackClient client = new SlackClient(slackToken);
                // ChatPostMessageRequest request = ChatPostMessageRequest.builder()
                // .channel(channel)
                // .text(formattedMessage)
                // .username(slackUsername)
                // .build();
                // ChatPostMessageResponse response = client.chatPostMessage(request);
                // if (!response.isOk()) {
                // log.error("Slack API error: {}", response.getError());
                // }
            }

            log.info("Slack notification sent to channels: {}", String.join(", ", targetChannels));
        } catch (Exception e) {
            log.error("Failed to send Slack notification: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send Slack notification", e);
        }
    }

    /**
     * Sends a notification via Microsoft Teams.
     * 
     * @param subject  The message subject/title
     * @param message  The message body
     * @param channels List of Teams channels or webhook URLs
     */
    private void sendTeamsMessage(String subject, String message, List<String> channels) {
        List<String> targetChannels = channels != null && !channels.isEmpty()
                ? channels
                : List.of(defaultTeamsChannel);

        // Format message for Teams (using markdown)
        String formattedMessage = String.format("## %s\n\n%s", subject, message);

        // Get Teams-specific properties
        Map<String, Object> teamsProps = channelProperties.getOrDefault(NotificationChannel.TEAMS, new HashMap<>());
        String webhookUrl = (String) teamsProps.getOrDefault("webhookUrl", "");

        if (webhookUrl.isEmpty()) {
            log.warn("Teams notification requested, but no webhook URL is configured");
            return;
        }

        try {
            // This is a placeholder for actual Microsoft Teams integration
            // In a real implementation, you would use their incoming webhook API

            for (String channel : targetChannels) {
                log.info("Would send Teams message to channel {}", channel);

                // Example of how real implementation might look:
                // Map<String, Object> card = new HashMap<>();
                // card.put("@type", "MessageCard");
                // card.put("@context", "http://schema.org/extensions");
                // card.put("title", subject);
                // card.put("text", message);

                // HttpClient client = HttpClient.newHttpClient();
                // HttpRequest request = HttpRequest.newBuilder()
                // .uri(URI.create(webhookUrl))
                // .header("Content-Type", "application/json")
                // .POST(HttpRequest.BodyPublishers.ofString(new
                // ObjectMapper().writeValueAsString(card)))
                // .build();

                // HttpResponse<String> response = client.send(request,
                // HttpResponse.BodyHandlers.ofString());
                // if (response.statusCode() != 200) {
                // log.error("Teams API error: {}", response.body());
                // }
            }

            log.info("Teams notification sent to channels: {}", String.join(", ", targetChannels));
        } catch (Exception e) {
            log.error("Failed to send Teams notification: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send Teams notification", e);
        }
    }
}