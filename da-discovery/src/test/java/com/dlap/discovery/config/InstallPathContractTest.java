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

package com.dlap.discovery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * INST-01: the install path is a contract between {@code .env.example}, the dev compose pair
 * and the README. These tests read those files from the repo checkout and fail when they
 * drift apart, which is how the original defects (an ignored {@code .env}, an invalid
 * encryption key, undocumented and unpassed variables) went unnoticed.
 *
 * <p>The tests are skipped, not failed, when the checkout does not contain the repo root
 * (for example when the module is built in isolation).
 */
class InstallPathContractTest {

    private static Path envExample;
    private static List<Path> composeFiles;
    private static Path readme;

    @BeforeAll
    static void locateRepoFiles() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".env.example"))) {
            dir = dir.getParent();
        }
        assumeTrue(dir != null, "repo root with .env.example not found; skipping install-path contract");
        envExample = dir.resolve(".env.example");
        Path dev = dir.resolve("deployment/environments/dev");
        composeFiles = List.of(dev.resolve("docker-compose.yml"), dev.resolve("docker-compose.microservices.yml"));
        readme = dir.resolve("README.md");
        assumeTrue(composeFiles.stream().allMatch(Files::exists) && Files.exists(readme),
                "dev compose pair or README.md missing; skipping install-path contract");
    }

    /** Active (uncommented) {@code KEY=value} assignments of {@code .env.example}, quotes stripped. */
    private static Map<String, String> activeEnvAssignments() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        Pattern assignment = Pattern.compile("^([A-Z][A-Z0-9_]*)=(.*)$");
        for (String line : Files.readAllLines(envExample, StandardCharsets.UTF_8)) {
            Matcher m = assignment.matcher(line);
            if (m.matches()) {
                String value = m.group(2).trim();
                if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(m.group(1), value);
            }
        }
        return result;
    }

    /** Names defined in {@code .env.example}, active or shown commented out as {@code # NAME=}. */
    private static Set<String> documentedEnvNames() throws IOException {
        Set<String> names = new HashSet<>();
        Pattern definition = Pattern.compile("^#?\\s*([A-Z][A-Z0-9_]*)=");
        for (String line : Files.readAllLines(envExample, StandardCharsets.UTF_8)) {
            Matcher m = definition.matcher(line);
            if (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /** The environment list of one service of the microservices compose file. */
    @SuppressWarnings("unchecked")
    private static List<String> environmentOf(String service) throws IOException {
        try (var reader = Files.newBufferedReader(composeFiles.get(1), StandardCharsets.UTF_8)) {
            Map<String, Object> doc = new Yaml().load(reader);
            Map<String, Object> services = (Map<String, Object>) doc.get("services");
            Map<String, Object> svc = (Map<String, Object>) services.get(service);
            return (List<String>) svc.get("environment");
        }
    }

    /** Mirrors {@code BasicEncryptionServiceImpl.resolveKeyBytes}: 64 hex chars decode to 32 bytes. */
    private static int keyBytes(String key) {
        return key.matches("[0-9a-fA-F]{64}") ? 32 : key.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void theExampleEncryptionKeyIsExactlyThirtyTwoBytes() throws IOException {
        String key = activeEnvAssignments().get("DLAP_ENCRYPTION_KEY");

        assertThat(key).isNotNull();
        assertThat(keyBytes(key)).isEqualTo(32);
    }

    @Test
    void theComposeDefaultEncryptionKeyIsExactlyThirtyTwoBytes() throws IOException {
        Matcher m = Pattern.compile("DLAP_ENCRYPTION_KEY=\\$\\{DLAP_ENCRYPTION_KEY:-([^}]*)}")
                .matcher(Files.readString(composeFiles.get(1), StandardCharsets.UTF_8));

        assertThat(m.find()).as("compose default for DLAP_ENCRYPTION_KEY").isTrue();
        assertThat(keyBytes(m.group(1))).isEqualTo(32);
    }

    @Test
    void everyVariableTheComposePairReadsIsDocumentedInTheExample() throws IOException {
        Pattern reference = Pattern.compile("(?<!\\$)\\$\\{([A-Z][A-Z0-9_]*)");
        Set<String> referenced = new TreeSet<>();
        for (Path compose : composeFiles) {
            for (String line : Files.readAllLines(compose, StandardCharsets.UTF_8)) {
                if (line.trim().startsWith("#")) {
                    continue;
                }
                Matcher m = reference.matcher(line);
                while (m.find()) {
                    referenced.add(m.group(1));
                }
            }
        }

        Set<String> undocumented = new TreeSet<>(referenced);
        undocumented.removeAll(documentedEnvNames());
        assertThat(referenced).isNotEmpty();
        assertThat(undocumented).as("compose variables missing from .env.example").isEmpty();
    }

    @Test
    void everyRealCloudSourceDefaultsToOffInTheExample() throws IOException {
        Map<String, String> env = activeEnvAssignments();

        for (String flag : List.of("DLAP_AWS_ENABLED", "DLAP_LOGINGEST_GCP_ENABLED", "DLAP_LOGINGEST_AWS_ENABLED",
                "DLAP_LOGINGEST_DEMO_SEED_ENABLED", "DLAP_UNITY_ENABLED", "CLOUD_PROVIDER_UNITY_ENABLED")) {
            assertThat(env).as(flag).containsEntry(flag, "false");
        }
    }

    @Test
    void logIngestReceivesTheGcpAndAwsVariablesAndTheyDefaultToOff() throws IOException {
        List<String> env = environmentOf("da-logingest");

        assertThat(env).contains(
                "DLAP_LOGINGEST_GCP_ENABLED=${DLAP_LOGINGEST_GCP_ENABLED:-false}",
                "DLAP_LOGINGEST_AWS_ENABLED=${DLAP_LOGINGEST_AWS_ENABLED:-false}",
                "SPRING_PROFILES_ACTIVE=${DLAP_LOGINGEST_PROFILES:-dev}");
        for (String name : List.of(
                "DLAP_LOGINGEST_GCP_PROJECTS", "DLAP_LOGINGEST_GCP_POLL_CRON", "DLAP_LOGINGEST_GCP_PAGE_SIZE",
                "DLAP_LOGINGEST_GCP_INITIAL_LOOKBACK_MINUTES", "DLAP_LOGINGEST_AWS_REGIONS",
                "DLAP_LOGINGEST_AWS_POLL_CRON", "DLAP_LOGINGEST_AWS_MAX_RESULTS",
                "DLAP_LOGINGEST_AWS_LOOKBACK_MINUTES", "DLAP_LOGINGEST_AWS_ENDPOINT",
                "DLAP_LOGINGEST_AWS_ACCESS_KEY", "DLAP_LOGINGEST_AWS_SECRET_KEY")) {
            assertThat(env).as(name).anyMatch(e -> e.startsWith(name + "=${" + name + ":-"));
        }
    }

    @Test
    void discoveryReceivesTheOneDocumentedAwsVariableSetAndItDefaultsToOff() throws IOException {
        List<String> env = environmentOf("da-discovery");

        assertThat(env).contains(
                "DLAP_AWS_ENABLED=${DLAP_AWS_ENABLED:-false}",
                "CLOUD_PROVIDER_AWS_ENABLED=${DLAP_AWS_ENABLED:-false}");
        for (String name : List.of("DLAP_AWS_REGION", "DLAP_AWS_ENDPOINT", "DLAP_AWS_ACCESS_KEY",
                "DLAP_AWS_SECRET_KEY")) {
            assertThat(env).as(name).anyMatch(e -> e.startsWith(name + "=${" + name + ":-"));
        }
    }

    @Test
    void theReadmeHealthExamplesUseActuatorForEveryService() throws IOException {
        String text = Files.readString(readme, StandardCharsets.UTF_8);

        assertThat(text).contains("curl -sf http://localhost:8091/actuator/health");
        assertThat(text).doesNotContain("/management/health");
        assertThat(text).contains("--recurse-submodules").contains("--env-file .env");
    }
}
