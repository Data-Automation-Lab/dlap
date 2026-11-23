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

package com.dlap.reporting.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Q-487: the files the pilot ships for the identity map are valid seed files, and the pilot overlay
 * hands da-reporting the variable that {@link IdentityMapSeeder} reads. A syntax error in the example
 * or a renamed property would otherwise show up only as a pilot that does not start, or as one that
 * silently maps nobody.
 */
class IdentityMapPilotFilesTest {

    private static final Path REPO_ROOT = repoRoot();

    private static final Path EXAMPLE = REPO_ROOT.resolve("deployment/examples/identity-map/identity-map.example.csv");
    private static final Path PILOT_DEFAULT = REPO_ROOT.resolve("deployment/environments/pilot/identity-map/identity-map.csv");
    private static final Path OVERLAY =
            REPO_ROOT.resolve("deployment/environments/pilot/docker-compose.pilot.identity-map.yml");

    @Test
    void theExampleFileParsesAndMapsEachProviderScopeItShows() throws IOException {
        List<IdentityMapSeedFile.Entry> entries = IdentityMapSeedFile.parse(read(EXAMPLE));

        assertThat(entries).containsExactly(
                new IdentityMapSeedFile.Entry(IdentityProviderScope.GCP,
                        "marketing-agent@acme-prod.iam.gserviceaccount.com", "svc-marketing-agent"),
                new IdentityMapSeedFile.Entry(IdentityProviderScope.GCP,
                        "etl-runner@acme-prod.iam.gserviceaccount.com", "svc-etl-runner"),
                new IdentityMapSeedFile.Entry(IdentityProviderScope.AWS,
                        "arn:aws:iam::123456789012:role/analytics-reader", "svc-analytics"),
                new IdentityMapSeedFile.Entry(IdentityProviderScope.AZURE,
                        "3f2504e0-4f89-11d3-9a0c-0305e82c3301", "svc-reporting-pipeline"),
                new IdentityMapSeedFile.Entry(IdentityProviderScope.ANY, "alice@example.com", "alice"));
    }

    @Test
    void theExampleNamesNoRealAccount() throws IOException {
        String text = read(EXAMPLE).toLowerCase(Locale.ROOT);

        // The documentation account id and a made-up project; the example must not carry real data (R14).
        assertThat(text).contains("123456789012").contains("acme-prod").contains("example.com");
        assertThat(text).doesNotContain("password").doesNotContain("secret");
    }

    @Test
    void thePilotDefaultIsAValidFileWithNoEntries() throws IOException {
        assertThat(IdentityMapSeedFile.parse(read(PILOT_DEFAULT))).isEmpty();
    }

    @Test
    void theOverlayMountsTheFileReadOnlyAndNamesTheVariableTheSeederReads() throws IOException {
        String overlay = read(OVERLAY);
        // Spring's relaxed binding: dlap.reporting.identity-map.seed-file is DLAP_REPORTING_IDENTITY_MAP_SEED_FILE.
        String variable = IdentityMapSeeder.SEED_FILE_PROPERTY.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');

        assertThat(variable).isEqualTo("DLAP_REPORTING_IDENTITY_MAP_SEED_FILE");
        assertThat(overlay).contains("- " + variable + "=/etc/dlap/identity-map.csv");
        assertThat(overlay).contains(":/etc/dlap/identity-map.csv:ro");
        assertThat(overlay).contains("${PILOT_IDENTITY_MAP_FILE:-../pilot/identity-map/identity-map.csv}");
        assertThat(overlay).contains("  da-reporting:");
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The checkout root: the nearest parent of the working directory that holds the deployment folder. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("deployment/examples"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("no deployment/examples folder above " + Path.of("").toAbsolutePath());
        }
        return dir;
    }
}
