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

package com.dlap.autocompliance.signal;

import java.io.File;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reference {@link ComplianceSignalProvider} that reads {@link ComplianceSignal}s from a
 * JSON file - the "bring your export" integration. Zero external dependencies, works
 * air-gapped, and doubles as the demo/evaluation path so the compliance-via-labels feature
 * (ADR-0035) is exercisable end-to-end without writing code.
 *
 * <p>Activated with {@code dlap.compliance.signal-provider=file}; the file location is
 * {@code dlap.compliance.signal-provider.file.path}. When active it becomes THE
 * {@code ComplianceSignalProvider} bean and the no-op default yields
 * ({@code @ConditionalOnMissingBean} in {@link ComplianceSignalConfiguration}).
 *
 * <p>File format - a JSON array of signals:
 * <pre>
 * [
 *   {"assetUri":"gs://b/pii","framework":"gdpr","controlId":"art-17","status":"VIOLATION","detail":"..."},
 *   {"assetUri":"s3://b/phi","framework":"hipaa","controlId":"164.312","status":"COMPLIANT"}
 * ]
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "dlap.compliance.signal-provider", havingValue = "file")
public class FileComplianceSignalProvider implements ComplianceSignalProvider {

    private static final Logger log = LoggerFactory.getLogger(FileComplianceSignalProvider.class);

    private final String path;
    private final ObjectMapper objectMapper;

    public FileComplianceSignalProvider(
            @Value("${dlap.compliance.signal-provider.file.path:}") String path,
            ObjectMapper objectMapper) {
        this.path = path;
        this.objectMapper = objectMapper;
        log.info("FileComplianceSignalProvider active (path='{}').", path);
    }

    @Override
    public List<ComplianceSignal> fetchSignals() {
        if (path == null || path.isBlank()) {
            log.warn("dlap.compliance.signal-provider.file.path is not set; no signals.");
            return List.of();
        }
        File file = new File(path);
        if (!file.isFile()) {
            log.warn("Compliance signal file not found: {}; no signals.", path);
            return List.of();
        }
        try {
            ComplianceSignal[] signals = objectMapper.readValue(file, ComplianceSignal[].class);
            return signals != null ? List.of(signals) : List.of();
        } catch (Exception e) {
            log.error("Failed to read compliance signals from {}: {}", path, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
