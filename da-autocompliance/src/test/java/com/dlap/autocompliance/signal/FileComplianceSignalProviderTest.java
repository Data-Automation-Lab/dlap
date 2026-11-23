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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dlap.autocompliance.signal.ComplianceSignal.ComplianceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

class FileComplianceSignalProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsSignalsFromJsonFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("signals.json");
        Files.writeString(file, """
                [
                  {"assetUri":"gs://b/pii","framework":"gdpr","controlId":"art-17",
                   "status":"VIOLATION","detail":"residency"},
                  {"assetUri":"s3://b/phi","framework":"hipaa","controlId":"164.312","status":"COMPLIANT"}
                ]
                """);

        List<ComplianceSignal> signals =
                new FileComplianceSignalProvider(file.toString(), objectMapper).fetchSignals();

        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).assetUri()).isEqualTo("gs://b/pii");
        assertThat(signals.get(0).framework()).isEqualTo("gdpr");
        assertThat(signals.get(0).status()).isEqualTo(ComplianceStatus.VIOLATION);
        assertThat(signals.get(1).status()).isEqualTo(ComplianceStatus.COMPLIANT);
    }

    @Test
    void missingFileYieldsNoSignals(@TempDir Path dir) {
        List<ComplianceSignal> signals =
                new FileComplianceSignalProvider(dir.resolve("absent.json").toString(), objectMapper)
                        .fetchSignals();
        assertThat(signals).isEmpty();
    }

    @Test
    void blankPathYieldsNoSignals() {
        assertThat(new FileComplianceSignalProvider("", objectMapper).fetchSignals()).isEmpty();
    }

    @Test
    void isEnabledIsTrueForAnActiveFileProvider() {
        assertThat(new FileComplianceSignalProvider("x", objectMapper).isEnabled()).isTrue();
    }
}
