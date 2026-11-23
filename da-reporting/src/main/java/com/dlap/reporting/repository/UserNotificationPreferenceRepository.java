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

package com.dlap.reporting.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.dlap.reporting.model.NotificationChannel;
import com.dlap.reporting.model.UserNotificationPreference;

@Repository
public interface UserNotificationPreferenceRepository extends JpaRepository<UserNotificationPreference, UUID>, JpaSpecificationExecutor<UserNotificationPreference> {
    List<UserNotificationPreference> findByUserId(UUID userId);
    Optional<UserNotificationPreference> findByUserIdAndNotificationTypeKeyAndChannel(UUID userId, String notificationTypeKey, NotificationChannel channel);
    List<UserNotificationPreference> findByNotificationTypeKeyAndChannelAndEnabled(String notificationTypeKey, NotificationChannel channel, boolean enabled);
    List<UserNotificationPreference> findByUserIdAndEnabled(UUID userId, boolean enabled);
} 