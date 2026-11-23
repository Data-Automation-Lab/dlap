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

package com.dlap.autodelete.nativerule;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start under the {@code prod} profile when a test-only endpoint is configured (EXEC-01, Q-745).
 *
 * <p>The appliers can be pointed at an S3-compatible emulator, a GCS emulator host or an Azure Resource Manager
 * stub ({@code dlap.autodelete.test-endpoint.*}) so that the lifecycle harness and the container tests can apply,
 * read back and remove a real rule without a cloud account. In production such a setting would send rule writes to
 * the wrong place, or to a host an attacker controls, so a prod start with any of them set fails fast, with a
 * message that names the property and never its value. The other profiles keep the endpoint; each applier logs a
 * WARN while its endpoint is set. da-autoarchival refuses its own endpoints the same way
 * ({@code EmulatorEndpointGuard}, ADR-0115).
 */
@Component
public class TestEndpointGuard {

    private static final Logger LOG = LoggerFactory.getLogger(TestEndpointGuard.class);

    /** Profile in which a test endpoint is refused. */
    static final String PROD_PROFILE = "prod";

    /**
     * Checks the settings at start-up.
     *
     * @param endpoints   the bound test endpoints
     * @param environment the Spring environment, for the active profiles
     * @throws IllegalStateException under the {@code prod} profile when a test endpoint is configured
     */
    public TestEndpointGuard(TestEndpoints endpoints, Environment environment) {
        List<String> found = violations(endpoints);
        if (found.isEmpty()) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            throw new IllegalStateException("da-autodelete refuses to start under the prod profile with a test-only "
                    + "endpoint configured: " + String.join(", ", found)
                    + ". Unset it; test endpoints exist for the lifecycle harness and the container tests only.");
        }
        LOG.warn("da-autodelete is using test-only endpoints ({}); this is refused under the prod profile",
                String.join(", ", found));
    }

    /**
     * Lists the test endpoint properties that are set.
     *
     * @param endpoints the bound test endpoints
     * @return the property names (never values), in a stable order; empty when none is set
     */
    static List<String> violations(TestEndpoints endpoints) {
        List<String> found = new ArrayList<>();
        if (!endpoints.s3().isEmpty()) {
            found.add("dlap.autodelete.test-endpoint.s3");
        }
        if (!endpoints.gcs().isEmpty()) {
            found.add("dlap.autodelete.test-endpoint.gcs");
        }
        if (!endpoints.azure().isEmpty()) {
            found.add("dlap.autodelete.test-endpoint.azure");
        }
        return found;
    }
}
