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

package com.dlap.discovery.test.util;

import com.dlap.discovery.config.dto.ConnectionDiscoveryConfigDTO;
import com.dlap.discovery.config.dto.GlobalDiscoveryConfigDTO;
import com.dlap.discovery.config.service.IDiscoveryConfigService;
import java.time.Instant;
import java.util.Optional;

/**
 * In-memory per-connection discovery configuration for tests. Hand-rolled instead of a
 * Mockito mock: the Mockito ByteBuddy agent is unstable on this JDK.
 */
public final class FakeConfigService implements IDiscoveryConfigService {

    /** The configuration returned for every connection; null means "none stored". */
    public ConnectionDiscoveryConfigDTO config;
    /** Connection id most recently passed to {@link #recordScheduledScanStarted}. */
    public String recordedFor;
    /** Start time most recently passed to {@link #recordScheduledScanStarted}. */
    public Instant recordedAt;

    @Override
    public Optional<ConnectionDiscoveryConfigDTO> getConnectionDiscoveryConfig(String id) {
        return Optional.ofNullable(config);
    }

    @Override
    public void recordScheduledScanStarted(String id, Instant startedAt) {
        this.recordedFor = id;
        this.recordedAt = startedAt;
    }

    @Override
    public GlobalDiscoveryConfigDTO getGlobalDiscoveryConfig() {
        return null;
    }

    @Override
    public void saveGlobalDiscoveryConfig(GlobalDiscoveryConfigDTO dto) {
    }

    @Override
    public ConnectionDiscoveryConfigDTO saveConnectionDiscoveryConfig(String id, ConnectionDiscoveryConfigDTO dto) {
        return dto;
    }

    @Override
    public void deleteConnectionDiscoveryConfig(String id) {
    }
}
