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

package com.dlap.autodelete.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves the correct {@link ICloudDeletionService} implementation for a given
 * cloud provider identifier.
 *
 * <p>The factory discovers every {@code ICloudDeletionService} bean on the classpath
 * at construction time and routes based on {@link ICloudDeletionService#supportsCloudProvider(String)}.
 * Per repository convention, each concrete implementation is a {@code @Service}
 * (see {@code service/storage/impl/*CloudDeletionService.java}).
 */
@Component
@Slf4j
public class CloudDeletionServiceFactory {

    private final List<ICloudDeletionService> implementations;

    public CloudDeletionServiceFactory(List<ICloudDeletionService> implementations) {
        this.implementations = Objects.requireNonNullElse(implementations, List.of());
        log.info("CloudDeletionServiceFactory initialised with {} provider implementation(s)", this.implementations.size());
    }

    /**
     * @param cloudProvider upper-case provider identifier (AWS, AZURE, GCP).
     * @return a matching {@link ICloudDeletionService}, never null.
     * @throws IllegalArgumentException if {@code cloudProvider} is null/blank.
     * @throws UnsupportedOperationException if no implementation supports the requested provider.
     */
    public ICloudDeletionService forProvider(String cloudProvider) {
        if (cloudProvider == null || cloudProvider.isBlank()) {
            throw new IllegalArgumentException("cloudProvider must not be null or blank");
        }
        String normalised = cloudProvider.trim().toUpperCase(Locale.ROOT);
        return implementations.stream()
            .filter(impl -> impl.supportsCloudProvider(normalised))
            .findFirst()
            .orElseThrow(() -> new UnsupportedOperationException(
                "No ICloudDeletionService implementation registered for provider: " + normalised));
    }
}
