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

package com.dlap.discovery.client.admin;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Chooses which {@link IAdminServiceClient} the service wires.
 *
 * <p>Exactly one implementation exists for every configuration, so the scan API can
 * never start with a missing client and can never silently fall back to a stub:
 *
 * <ul>
 *   <li>profiles {@code dev} or {@code test} (and nothing overriding): the in-process
 *       {@link MockAdminServiceClient};</li>
 *   <li>any other profile: {@link HttpAdminServiceClient}, which reads connections from
 *       da-admin-service over a service-account (M2M) token;</li>
 *   <li>{@value #PROPERTY}{@code =http} on any profile, including {@code dev}: the HTTP
 *       client. This is how a dev or demo stack scans the connections an administrator
 *       actually registered instead of the mock accounts.</li>
 * </ul>
 */
public final class AdminClientMode {

    /** Property that forces the HTTP client when set to {@value #HTTP}. */
    public static final String PROPERTY = "dlap.discovery.admin-client.mode";

    /** Value of {@link #PROPERTY} that selects {@link HttpAdminServiceClient}. */
    public static final String HTTP = "http";

    private static final Profiles MOCK_PROFILES = Profiles.of("dev", "test");

    private AdminClientMode() {
    }

    /**
     * Tells whether the HTTP client is the selected implementation.
     *
     * @param env the Spring environment
     * @return true for the HTTP client, false for the mock
     */
    public static boolean useHttp(Environment env) {
        if (HTTP.equalsIgnoreCase(env.getProperty(PROPERTY, ""))) {
            return true;
        }
        return !env.acceptsProfiles(MOCK_PROFILES);
    }

    /** Matches when {@link HttpAdminServiceClient} is the selected implementation. */
    public static class HttpSelected implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return useHttp(context.getEnvironment());
        }
    }

    /** Matches when {@link MockAdminServiceClient} is the selected implementation. */
    public static class MockSelected implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !useHttp(context.getEnvironment());
        }
    }
}
