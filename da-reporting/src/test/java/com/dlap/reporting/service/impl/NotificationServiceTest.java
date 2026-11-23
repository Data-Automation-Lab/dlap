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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dlap.reporting.dto.NotificationPreferenceDTO;
import com.dlap.reporting.mapper.NotificationPreferenceMapper;
import com.dlap.reporting.model.NotificationChannel;
import com.dlap.reporting.model.UserNotificationPreference;
import com.dlap.reporting.repository.UserNotificationPreferenceRepository;

/**
 * BOOT-01: saving a new notification preference stays an insert when the request carries an id.
 * Since Hibernate ORM 6.6 saving a generated-id entity with a preset id is a merge that fails when no
 * row exists, so the service clears the id of a new preference; an existing one keeps its own.
 */
class NotificationServiceTest {

    private final UserNotificationPreferenceRepository repository = mock(UserNotificationPreferenceRepository.class);
    private final NotificationPreferenceMapper mapper = mock(NotificationPreferenceMapper.class);
    private final NotificationService service = new NotificationService(repository, mapper, List.of());

    @Test
    void aNewPreferenceIsInsertedWithoutTheClientSentId() {
        NotificationPreferenceDTO request = request();
        UserNotificationPreference mapped = new UserNotificationPreference();
        mapped.setId(request.getId());
        when(repository.findByUserIdAndNotificationTypeKeyAndChannel(request.getUserId(), "REPORT_READY",
                NotificationChannel.EMAIL)).thenReturn(Optional.empty());
        when(mapper.toEntity(request)).thenReturn(mapped);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveNotificationPreference(request);

        ArgumentCaptor<UserNotificationPreference> saved = ArgumentCaptor.forClass(UserNotificationPreference.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
    }

    @Test
    void anExistingPreferenceKeepsItsId() {
        NotificationPreferenceDTO request = request();
        UUID storedId = UUID.randomUUID();
        UserNotificationPreference stored = new UserNotificationPreference();
        stored.setId(storedId);
        when(repository.findByUserIdAndNotificationTypeKeyAndChannel(request.getUserId(), "REPORT_READY",
                NotificationChannel.EMAIL)).thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveNotificationPreference(request);

        ArgumentCaptor<UserNotificationPreference> saved = ArgumentCaptor.forClass(UserNotificationPreference.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(storedId);
        assertThat(saved.getValue().isEnabled()).isTrue();
    }

    private static NotificationPreferenceDTO request() {
        NotificationPreferenceDTO request = new NotificationPreferenceDTO();
        request.setId(UUID.randomUUID());
        request.setUserId(UUID.randomUUID());
        request.setNotificationTypeKey("REPORT_READY");
        request.setChannel(NotificationChannel.EMAIL);
        request.setEnabled(true);
        return request;
    }
}
