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

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Reference {@link ComplianceSignalProvider} that pulls {@link ComplianceSignal}s from a
 * configurable REST endpoint - the realistic generic integration for "your GRC tool (or a
 * thin adapter you run) exposes an HTTP endpoint returning signals". Cloud-neutral, uses only
 * the HTTP client already on the classpath.
 *
 * <p>Activated with {@code dlap.compliance.signal-provider=http}; the endpoint is
 * {@code dlap.compliance.signal-provider.http.url} and must return a JSON array of signals
 * (same shape as {@link FileComplianceSignalProvider}). When active it becomes THE
 * {@code ComplianceSignalProvider} bean and the no-op default yields.
 *
 * <p>Transport failures are non-fatal: they log a warning and yield no signals for that
 * sync round (the periodic {@link ComplianceSignalSyncService} retries on the next tick).
 */
@Component
@ConditionalOnProperty(name = "dlap.compliance.signal-provider", havingValue = "http")
public class HttpComplianceSignalProvider implements ComplianceSignalProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpComplianceSignalProvider.class);

    private final RestTemplate restTemplate;
    private final String url;

    public HttpComplianceSignalProvider(
            RestTemplateBuilder builder,
            @Value("${dlap.compliance.signal-provider.http.url:}") String url) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .build();
        this.url = url;
        log.info("HttpComplianceSignalProvider active (url='{}').", url);
    }

    /** Package-private constructor for tests (inject a mocked/bound RestTemplate). */
    HttpComplianceSignalProvider(RestTemplate restTemplate, String url) {
        this.restTemplate = restTemplate;
        this.url = url;
    }

    @Override
    public List<ComplianceSignal> fetchSignals() {
        if (url == null || url.isBlank()) {
            log.warn("dlap.compliance.signal-provider.http.url is not set; no signals.");
            return List.of();
        }
        try {
            ComplianceSignal[] signals = restTemplate.getForObject(url, ComplianceSignal[].class);
            return signals != null ? Arrays.asList(signals) : List.of();
        } catch (Exception e) {
            log.warn("Failed to fetch compliance signals from {}: {}", url, e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
