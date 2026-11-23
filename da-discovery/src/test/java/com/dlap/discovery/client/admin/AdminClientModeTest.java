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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.discovery.common.model.enums.CloudProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * DISC-01: exactly one {@link IAdminServiceClient} is wired for every profile, and any
 * profile other than {@code dev} and {@code test} gets the da-admin-service client, never the
 * mock. The context runner registers the real component classes, so the {@code @Profile} and
 * {@code @Conditional} annotations on them are what is under test.
 */
class AdminClientModeTest {

    private static MockEnvironment env(String property, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        if (property != null) {
            env.setProperty(AdminClientMode.PROPERTY, property);
        }
        return env;
    }

    @Test
    void devAndTestProfiles_useTheMock_everythingElseUsesHttp() {
        assertThat(AdminClientMode.useHttp(env(null, "dev"))).isFalse();
        assertThat(AdminClientMode.useHttp(env(null, "test"))).isFalse();
        assertThat(AdminClientMode.useHttp(env(null, "dev", "tls"))).isFalse();
        assertThat(AdminClientMode.useHttp(env(null, "prod"))).isTrue();
        assertThat(AdminClientMode.useHttp(env(null, "gcp"))).isTrue();
        assertThat(AdminClientMode.useHttp(env(null))).as("no active profile").isTrue();
    }

    @Test
    void theModePropertyForcesHttpOnDev_butNeverTheMockOutsideDevAndTest() {
        assertThat(AdminClientMode.useHttp(env("http", "dev"))).isTrue();
        assertThat(AdminClientMode.useHttp(env("HTTP", "test"))).isTrue();
        assertThat(AdminClientMode.useHttp(env("mock", "prod")))
                .as("a mock can never be selected on a production profile").isTrue();
        assertThat(AdminClientMode.useHttp(env("mock", "dev"))).isFalse();
    }

    /**
     * Runner with exactly the given active profiles. The module's test task sets
     * {@code spring.profiles.active=test} as a system property, which an empty
     * {@code setActiveProfiles()} would silently fall back to, so the "no profile" case blanks it.
     */
    private ApplicationContextRunner runner(String... profiles) {
        ApplicationContextRunner base = new ApplicationContextRunner()
                .withUserConfiguration(MockAdminServiceClient.class, HttpAdminServiceClient.class);
        if (profiles.length == 0) {
            return base.withPropertyValues("spring.profiles.active=");
        }
        return base.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profiles));
    }

    @Test
    void devProfile_wiresOnlyTheMock() {
        runner("dev").run(ctx -> {
            assertThat(ctx).hasSingleBean(IAdminServiceClient.class);
            assertThat(ctx).hasSingleBean(MockAdminServiceClient.class);
            assertThat(ctx).doesNotHaveBean(HttpAdminServiceClient.class);
        });
    }

    @Test
    void testProfile_wiresOnlyTheMock() {
        runner("test").run(ctx -> {
            assertThat(ctx).hasSingleBean(IAdminServiceClient.class);
            assertThat(ctx).hasSingleBean(MockAdminServiceClient.class);
        });
    }

    @Test
    void prodProfile_wiresOnlyTheHttpClient() {
        runner("prod").run(ctx -> {
            assertThat(ctx).hasSingleBean(IAdminServiceClient.class);
            assertThat(ctx).hasSingleBean(HttpAdminServiceClient.class);
            assertThat(ctx).doesNotHaveBean(MockAdminServiceClient.class);
        });
    }

    @Test
    void noProfile_wiresOnlyTheHttpClient() {
        runner().run(ctx -> {
            assertThat(ctx).hasSingleBean(IAdminServiceClient.class);
            assertThat(ctx).hasSingleBean(HttpAdminServiceClient.class);
        });
    }

    @Test
    void devProfileWithModeHttp_wiresOnlyTheHttpClient() {
        runner("dev").withPropertyValues(AdminClientMode.PROPERTY + "=http").run(ctx -> {
            assertThat(ctx).hasSingleBean(IAdminServiceClient.class);
            assertThat(ctx).hasSingleBean(HttpAdminServiceClient.class);
            assertThat(ctx).doesNotHaveBean(MockAdminServiceClient.class);
        });
    }

    @Test
    void adminServiceUrlAndTimeoutsComeFromPropertiesWithDefaults() {
        runner("prod").withPropertyValues(
                "dlap.admin-service.url=http://admin.example:9",
                "dlap.admin-service.connect-timeout-ms=111",
                "dlap.admin-service.read-timeout-ms=222").run(ctx -> assertThat(ctx).hasNotFailed());
        runner("prod").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void theMockOffersOnlyTheGcpConnection_theOthersStayResolvableById() {
        MockAdminServiceClient mock = new MockAdminServiceClient();

        List<AdminConnectionSummary> offered = mock.listConnections();

        assertThat(offered).containsExactly(
                new AdminConnectionSummary("mock-gcp-conn-id", "Mock GCP Connection", "GCP", true, true));
        assertThat(mock.getConnectionDetails("mock-aws-conn-id").getProvider()).isEqualTo(CloudProvider.AWS);
    }

    @Test
    void aSingleMethodLambdaStillImplementsTheInterface_andRefusesToList() {
        IAdminServiceClient lambda = id -> new AdminConnectionDetails("a", CloudProvider.GCP, id);

        org.assertj.core.api.Assertions.assertThatThrownBy(lambda::listConnections)
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
