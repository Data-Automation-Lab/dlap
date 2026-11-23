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

package com.dlap.autoarchival.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start under the {@code prod} profile when a test-only emulator endpoint, or the deprecated Azure
 * connection string, is configured (Q-713, EXEC-01 Q-740, Q-724).
 *
 * <p>The archive appliers can be pointed at an S3-compatible endpoint, a GCS emulator host or an Azure blob stub
 * ({@code dlap.autoarchival.test-endpoint.s3}, {@code .gcs}, {@code .azure}) so that the lifecycle harness and the
 * container tests can apply, read back and remove a rule without a real cloud account. Those settings are off by
 * default (empty). In production they would send the service's writes to the wrong place, or to a host an attacker
 * controls, so a prod start with any of them set fails fast. The RET-03 names ({@code aws.endpoint-override},
 * {@code aws.endpoint-access-key}, {@code aws.endpoint-secret-key}, {@code gcs.endpoint-override}) keep working for
 * one release as deprecated aliases: they are refused under prod the same way, and elsewhere each one logs a WARN
 * naming its replacement. The Azure connection string
 * ({@code dlap.autoarchival.azure.connection-string}) is a deprecated fallback to {@code DefaultAzureCredential}
 * and an account endpoint; any value of it is refused under prod. The message names the property, never the value,
 * because the value may carry a key.
 *
 * <p>Only the {@code prod} profile is guarded; dev, demo and test runs keep the override.
 */
@Component
public class EmulatorEndpointGuard {

    private static final Logger LOG = LoggerFactory.getLogger(EmulatorEndpointGuard.class);

    /** Profile in which the override is refused. */
    static final String PROD_PROFILE = "prod";

    /** The test-only endpoint properties (EXEC-01 scheme) that are the override when non-empty. */
    static final List<String> TEST_ENDPOINT_PROPERTIES = List.of(
            "dlap.autoarchival.test-endpoint.s3",
            "dlap.autoarchival.test-endpoint.gcs",
            "dlap.autoarchival.test-endpoint.azure");

    /** Properties that are the test-only override when non-empty: the new names, then the deprecated aliases. */
    static final List<String> OVERRIDE_PROPERTIES;

    static {
        List<String> all = new ArrayList<>(TEST_ENDPOINT_PROPERTIES);
        all.addAll(TestEndpointProperties.DEPRECATED_ALIASES.keySet());
        OVERRIDE_PROPERTIES = List.copyOf(all);
    }

    /** The Azure connection string property: a deprecated fallback, and the Azurite override for the dev store. */
    static final String AZURE_CONNECTION_STRING = "dlap.autoarchival.azure.connection-string";

    /**
     * Checks the environment at startup.
     *
     * @param environment the Spring environment
     * @throws IllegalStateException under the {@code prod} profile when an emulator endpoint or the Azure
     *                               connection string is configured
     */
    public EmulatorEndpointGuard(Environment environment) {
        List<String> found = violations(environment);
        if (!found.isEmpty() && environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            throw new IllegalStateException("da-autoarchival refuses to start under the prod profile with a test-only "
                    + "endpoint or the deprecated Azure connection string configured: " + String.join(", ", found)
                    + ". Unset them; the endpoints exist for the lifecycle harness and the container tests only, and "
                    + "Azure is reached with DefaultAzureCredential and dlap.autoarchival.azure.account-endpoint.");
        }
        if (!found.isEmpty()) {
            LOG.warn("da-autoarchival is using test-only endpoints or a deprecated setting ({}); this is refused "
                    + "under the prod profile", String.join(", ", found));
        }
        deprecations(environment).forEach((old, replacement) -> LOG.warn(
                "{} is DEPRECATED and will be removed in the next release; use {} instead", old, replacement));
    }

    /**
     * Lists the test-only endpoint properties that are set, and the Azure connection string when it is set.
     *
     * @param environment the Spring environment
     * @return the property names (never values), in a stable order; empty when none is set
     */
    static List<String> violations(Environment environment) {
        List<String> found = new ArrayList<>();
        for (String name : OVERRIDE_PROPERTIES) {
            String v = environment.getProperty(name);
            if (v != null && !v.isBlank()) {
                found.add(name);
            }
        }
        String azure = environment.getProperty(AZURE_CONNECTION_STRING);
        if (azure != null && !azure.isBlank()) {
            String lower = azure.toLowerCase(Locale.ROOT);
            boolean azurite = lower.contains("usedevelopmentstorage=true") || lower.contains("devstoreaccount1");
            found.add(AZURE_CONNECTION_STRING + (azurite ? " (Azurite development store)" : " (deprecated)"));
        }
        return found;
    }

    /**
     * The deprecated aliases that are set, each with what replaces it.
     *
     * @param environment the Spring environment
     * @return deprecated property to replacement, in a stable order; the connection string is included
     */
    static Map<String, String> deprecations(Environment environment) {
        Map<String, String> found = new LinkedHashMap<>();
        TestEndpointProperties.DEPRECATED_ALIASES.forEach((old, replacement) -> {
            String v = environment.getProperty(old);
            if (v != null && !v.isBlank()) {
                found.put(old, replacement);
            }
        });
        String azure = environment.getProperty(AZURE_CONNECTION_STRING);
        if (azure != null && !azure.isBlank()) {
            found.put(AZURE_CONNECTION_STRING,
                    "dlap.autoarchival.azure.account-endpoint (DefaultAzureCredential)");
        }
        return found;
    }
}
