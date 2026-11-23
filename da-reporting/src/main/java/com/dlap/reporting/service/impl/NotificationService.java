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

package com.dlap.reporting.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import com.dlap.reporting.common.exception.BadRequestException;
import com.dlap.reporting.common.exception.ResourceNotFoundException;
import com.dlap.reporting.dto.NotificationPreferenceDTO;
import com.dlap.reporting.dto.NotificationRequestDTO;
import com.dlap.reporting.mapper.NotificationPreferenceMapper;
import com.dlap.reporting.model.NotificationChannel;
import com.dlap.reporting.model.UserNotificationPreference;
import com.dlap.reporting.repository.UserNotificationPreferenceRepository;
import com.dlap.reporting.service.INotificationService;
import com.dlap.reporting.service.notification.INotificationProvider;

@Service
public class NotificationService implements INotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    
    private final UserNotificationPreferenceRepository preferenceRepository;
    private final NotificationPreferenceMapper preferenceMapper;
    private final Map<NotificationChannel, INotificationProvider> notificationProviders;

    public NotificationService(
            UserNotificationPreferenceRepository preferenceRepository,
            NotificationPreferenceMapper preferenceMapper,
            List<INotificationProvider> providers
    ) {
        this.preferenceRepository = preferenceRepository;
        this.preferenceMapper = preferenceMapper;
        this.notificationProviders = providers.stream()
                .collect(Collectors.toUnmodifiableMap(INotificationProvider::getChannelType, Function.identity()));
        log.info("Initialized NotificationService with providers for channels: {}", this.notificationProviders.keySet());
    }

    @Override
    @Transactional
    public NotificationPreferenceDTO saveNotificationPreference(NotificationPreferenceDTO preferenceDTO) {
        if (preferenceDTO.getUserId() == null || preferenceDTO.getNotificationTypeKey() == null || preferenceDTO.getChannel() == null) {
            throw new BadRequestException("User ID, Notification Type Key, and Channel are required for preferences.");
        }

        UserNotificationPreference preference = preferenceRepository
                .findByUserIdAndNotificationTypeKeyAndChannel(
                        preferenceDTO.getUserId(),
                        preferenceDTO.getNotificationTypeKey(),
                        preferenceDTO.getChannel())
                .orElseGet(() -> newPreference(preferenceDTO));

        // Update fields if it exists
        if (preference.getId() != null) {
            preference.setEnabled(preferenceDTO.isEnabled());
            preference.setChannelConfiguration(preferenceDTO.getChannelConfiguration());
        }
        // For new preferences, mapper already set the values from DTO.

        return preferenceMapper.toDTO(preferenceRepository.save(preference));
    }

    /**
     * A new preference from the request. Its id is generated: a client-sent id used to be dropped by
     * Hibernate's merge, which inserted a new row; since Hibernate ORM 6.6 that merge fails optimistic
     * locking instead, so the id is cleared and the save stays an insert (BOOT-01).
     */
    private UserNotificationPreference newPreference(NotificationPreferenceDTO preferenceDTO) {
        UserNotificationPreference created = preferenceMapper.toEntity(preferenceDTO);
        created.setId(null);
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreferenceDTO> getNotificationPreferencesByUserId(UUID userId) {
        return preferenceMapper.toDTOList(preferenceRepository.findByUserId(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPreferenceDTO getNotificationPreferenceById(UUID preferenceId) {
        return preferenceRepository.findById(preferenceId)
                .map(preferenceMapper::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("NotificationPreference", "id", preferenceId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationPreferenceDTO> getAllNotificationPreferences(Pageable pageable) {
        return preferenceRepository.findAll(pageable).map(preferenceMapper::toDTO);
    }

    @Override
    @Transactional
    public void deleteNotificationPreference(UUID preferenceId) {
        if (!preferenceRepository.existsById(preferenceId)) {
            throw new ResourceNotFoundException("NotificationPreference", "id", preferenceId);
        }
        preferenceRepository.deleteById(preferenceId);
    }

    @Override
    public void sendNotification(NotificationRequestDTO request) {
        if (CollectionUtils.isEmpty(request.getChannels())) {
            log.warn("No channels specified in notification request. Subject: {}", request.getSubject());
            return;
        }

        List<UserNotificationPreference> preferences = Collections.emptyList();

        if (!CollectionUtils.isEmpty(request.getTargetUserIds())) {
            // Fetch preferences for specific users and filter by requested channels
            preferences = preferenceRepository.findAllById(request.getTargetUserIds()).stream()
                    .filter(UserNotificationPreference::isEnabled)
                    .filter(p -> request.getChannels().contains(p.getChannel()))
                    .collect(Collectors.toList());
            // We might also need to handle cases where a user ID is given but they have no matching preference for the channel.
            // This current logic assumes we only notify based on *existing* preferences.
            // If direct notification to user without preference is needed, that's a different path.

        } else if (request.getNotificationTypeKey() != null) {
            // Fetch all enabled preferences for the given notification type key and requested channels
            preferences = request.getChannels().stream()
                .flatMap(channel -> 
                    preferenceRepository.findByNotificationTypeKeyAndChannelAndEnabled(
                        request.getNotificationTypeKey(), channel, true).stream())
                .distinct() // Avoid duplicate if user subscribed via multiple ways to same effective outcome (though our model prevents this row-wise)
                .collect(Collectors.toList());
        } else {
            log.warn("Notification request must specify targetUserIds or a notificationTypeKey. Subject: {}", request.getSubject());
            return;
        }

        if (CollectionUtils.isEmpty(preferences)) {
            log.info("No matching user preferences found for notification. Subject: {}", request.getSubject());
            return;
        }

        for (UserNotificationPreference preference : preferences) {
            INotificationProvider provider = notificationProviders.get(preference.getChannel());
            if (provider != null) {
                try {
                    // Apply overrides if present
                    NotificationRequestDTO finalRequest = request;
                    if (request.getChannelOverrides() != null && request.getChannelOverrides().containsKey(preference.getChannel().name())) {
                        // This is a simplification. A real override might need deeper merging.
                        // For now, let's assume direct send is used if overrides are complex.
                        log.warn("Channel specific overrides are present but not fully implemented for preference-based send. Consider direct send. Channel: {}", preference.getChannel());
                    }
                    provider.send(preference, finalRequest);
                    log.info("Sent notification '{}' to user {} via {}", request.getSubject(), preference.getUserId(), preference.getChannel());
                } catch (Exception e) {
                    log.error("Failed to send notification to user {} via {}. Subject: {}. Error: {}",
                            preference.getUserId(), preference.getChannel(), request.getSubject(), e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void sendTestNotification(UUID userId, String channelName) {
        NotificationChannel channel;
        try {
            channel = NotificationChannel.valueOf(channelName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid notification channel: " + channelName);
        }

        INotificationProvider provider = notificationProviders.get(channel);
        if (provider == null) {
            throw new BadRequestException("No provider configured for channel: " + channelName);
        }

        // For a test, we might fetch a user's primary contact for that channel or use a default test address.
        // Here, we'll try to find a preference or send to a default if not found.
        UserNotificationPreference testPreference = preferenceRepository
                .findByUserIdAndNotificationTypeKeyAndChannel(userId, "TEST_NOTIFICATION", channel)
                .orElseGet(() -> {
                    UserNotificationPreference tempPref = new UserNotificationPreference();
                    tempPref.setUserId(userId);
                    tempPref.setChannel(channel);
                    tempPref.setEnabled(true);
                    // tempPref.setChannelConfiguration(...); // Potentially set a test email/slackId if not found in profile
                    return tempPref;
                });
        
        if (!testPreference.isEnabled()) {
            throw new BadRequestException("Test notifications are disabled for this user and channel through preferences.");
        }

        NotificationRequestDTO testRequest = NotificationRequestDTO.builder()
                .subject("Test Notification")
                .body("This is a test notification from DLAP Reporting Service.")
                .channels(Collections.singletonList(channel))
                .targetUserIds(Collections.singletonList(userId)) // For context, though provider.send uses preference
                .build();

        try {
            provider.send(testPreference, testRequest);
            log.info("Sent test notification to user {} via {}", userId, channel);
        } catch (Exception e) {
            log.error("Failed to send test notification to user {} via {}: {}", userId, channel, e.getMessage(), e);
            throw new RuntimeException("Failed to send test notification: " + e.getMessage(), e);
        }
    }
} 