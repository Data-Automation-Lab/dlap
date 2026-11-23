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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import com.dlap.autocompliance.signal.ComplianceSignal.ComplianceStatus;

class HttpComplianceSignalProviderTest {

    private static final String URL = "https://grc.example.com/dlap/signals";

    @Test
    void fetchesAndMapsSignalsFromEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ComplianceSignal[] body = {
            new ComplianceSignal("gs://b/pii", "gdpr", "art-17", ComplianceStatus.VIOLATION, "residency"),
            new ComplianceSignal("s3://b/phi", "hipaa", "164.312", ComplianceStatus.COMPLIANT, null),
        };
        when(restTemplate.getForObject(eq(URL), eq(ComplianceSignal[].class))).thenReturn(body);

        List<ComplianceSignal> signals = new HttpComplianceSignalProvider(restTemplate, URL).fetchSignals();

        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).assetUri()).isEqualTo("gs://b/pii");
        assertThat(signals.get(0).status()).isEqualTo(ComplianceStatus.VIOLATION);
    }

    @Test
    void transportFailureYieldsNoSignals() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(eq(URL), eq(ComplianceSignal[].class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connect timed out"));

        assertThat(new HttpComplianceSignalProvider(restTemplate, URL).fetchSignals()).isEmpty();
    }

    @Test
    void blankUrlYieldsNoSignals() {
        assertThat(new HttpComplianceSignalProvider(mock(RestTemplate.class), "").fetchSignals()).isEmpty();
    }

    @Test
    void isEnabledIsTrueForAnActiveHttpProvider() {
        assertThat(new HttpComplianceSignalProvider(mock(RestTemplate.class), URL).isEnabled()).isTrue();
    }
}
