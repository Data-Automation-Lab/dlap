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

package com.dlap.reporting.controller;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
// import org.springframework.security.core.annotation.AuthenticationPrincipal;
// import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.common.security.SecurityUtils;
import com.dlap.reporting.dto.NotificationPreferenceDTO;
import com.dlap.reporting.service.INotificationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/reporting/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final INotificationService notificationService;

    @PostMapping("/preferences")
    @PreAuthorize("isAuthenticated()") // User manages their own preferences
    public ResponseEntity<NotificationPreferenceDTO> saveNotificationPreference(@Valid @RequestBody NotificationPreferenceDTO preferenceDTO) {
        // Ensure the userId in DTO matches the authenticated user, or is set by the backend
        UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
        if (preferenceDTO.getUserId() == null) {
            preferenceDTO.setUserId(authenticatedUserId);
        } else if (!preferenceDTO.getUserId().equals(authenticatedUserId)) {
            // Non-admins should not be able to set preferences for other users.
            // Admins might have a separate endpoint or logic.
            // For now, let's assume this endpoint is for the authenticated user only.
            throw new org.springframework.security.access.AccessDeniedException("Cannot set preferences for another user.");
        }
        return new ResponseEntity<>(notificationService.saveNotificationPreference(preferenceDTO), HttpStatus.OK);
    }

    @GetMapping("/preferences/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<NotificationPreferenceDTO>> getMyNotificationPreferences() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(notificationService.getNotificationPreferencesByUserId(userId));
    }
    
    @GetMapping("/preferences/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')") // Admin can view others' preferences
    public ResponseEntity<List<NotificationPreferenceDTO>> getUserNotificationPreferences(@PathVariable UUID userId) {
        return ResponseEntity.ok(notificationService.getNotificationPreferencesByUserId(userId));
    }

    @GetMapping("/preferences/{preferenceId}")
    @PreAuthorize("isAuthenticated()") // User can get their own, admin can get any
    public ResponseEntity<NotificationPreferenceDTO> getNotificationPreferenceById(@PathVariable UUID preferenceId) {
        NotificationPreferenceDTO dto = notificationService.getNotificationPreferenceById(preferenceId);
        assertPreferenceAccess(dto);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/preferences")
    @PreAuthorize("hasRole('ADMIN')") // Admin only: get all preferences
    public ResponseEntity<Page<NotificationPreferenceDTO>> getAllNotificationPreferences(Pageable pageable) {
        return ResponseEntity.ok(notificationService.getAllNotificationPreferences(pageable));
    }

    @DeleteMapping("/preferences/{preferenceId}")
    @PreAuthorize("isAuthenticated()") // User can delete their own, admin can delete any
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNotificationPreference(@PathVariable UUID preferenceId) {
        NotificationPreferenceDTO dto = notificationService.getNotificationPreferenceById(preferenceId);
        assertPreferenceAccess(dto);
        notificationService.deleteNotificationPreference(preferenceId);
    }

    @PostMapping("/test/{channelName}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> sendTestNotification(@PathVariable String channelName) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        notificationService.sendTestNotification(userId, channelName);
        return ResponseEntity.ok("Test notification sent to user " + userId + " via " + channelName);
    }
    
    private static boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private void assertPreferenceAccess(NotificationPreferenceDTO dto) {
        if (isCurrentUserAdmin()) {
            return;
        }
        UUID current = SecurityUtils.getAuthenticatedUserId();
        if (dto.getUserId() == null || !dto.getUserId().equals(current)) {
            throw new AccessDeniedException("Not allowed to access this notification preference.");
        }
    }

    // Note: A general purpose POST /notifications to send ad-hoc notifications is not exposed here.
    // That would typically be an internal capability or a highly restricted admin API,
    // as it could be misused for spam if `NotificationRequestDTO` is directly accepted.
    // Notifications are primarily driven by events, report completions, or specific system actions.
} 