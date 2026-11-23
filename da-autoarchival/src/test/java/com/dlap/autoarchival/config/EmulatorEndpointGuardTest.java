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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * Q-713: the test-only emulator endpoints of the archive appliers are off by default and refused under the prod
 * profile. The message names the property, never the value, because the value may carry a key.
 */
class EmulatorEndpointGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmulatorEndpointGuard.class);

    @Test
    void q713_nothingSetStartsUnderProdAndUnderDev() {
        runner.withPropertyValues("spring.profiles.active=prod").run(ctx -> assertThat(ctx).hasNotFailed());
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void q713_theShippedDefaultsAreEmpty() throws Exception {
        java.util.Properties shipped = new java.util.Properties();
        try (var in = EmulatorEndpointGuardTest.class.getResourceAsStream("/application.properties")) {
            shipped.load(in);
        }
        assertThat(shipped.getProperty("dlap.autoarchival.aws.endpoint-override")).isEmpty();
        assertThat(shipped.getProperty("dlap.autoarchival.gcs.endpoint-override")).isEmpty();
        assertThat(shipped.getProperty("dlap.autoarchival.azure.connection-string", "")).doesNotContain("devstoreaccount1");
    }

    @Test
    void q713_anS3EndpointIsRefusedUnderProdAndTheKeysAreNeverPrinted() {
        runner.withPropertyValues("spring.profiles.active=prod",
                "dlap.autoarchival.aws.endpoint-override=http://localstack:4566",
                "dlap.autoarchival.aws.endpoint-access-key=AKIAEXAMPLEKEYID",
                "dlap.autoarchival.aws.endpoint-secret-key=s3cr3t-value").run(ctx -> {
                    assertThat(ctx).hasFailed();
                    String message = rootMessage(ctx.getStartupFailure());
                    assertThat(message).contains("dlap.autoarchival.aws.endpoint-override")
                            .contains("dlap.autoarchival.aws.endpoint-access-key")
                            .contains("dlap.autoarchival.aws.endpoint-secret-key")
                            .doesNotContain("localstack").doesNotContain("AKIAEXAMPLEKEYID").doesNotContain("s3cr3t");
                });
    }

    @Test
    void q713_aGcsEmulatorHostIsRefusedUnderProd() {
        runner.withPropertyValues("spring.profiles.active=prod", "dlap.autoarchival.gcs.endpoint-override=http://fake-gcs:4443")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("dlap.autoarchival.gcs.endpoint-override")
                            .doesNotContain("fake-gcs");
                });
    }

    @Test
    void q713_anyAzureConnectionStringIsRefusedUnderProd() {
        runner.withPropertyValues("spring.profiles.active=prod",
                "dlap.autoarchival.azure.connection-string=DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                        + "AccountKey=abc;BlobEndpoint=http://azurite:10000/devstoreaccount1;").run(ctx -> {
                            assertThat(ctx).hasFailed();
                            assertThat(rootMessage(ctx.getStartupFailure()))
                                    .contains("dlap.autoarchival.azure.connection-string").doesNotContain("azurite:10000");
                        });
        // EXEC-01 (Q-724): the connection string is a deprecated fallback; the prod profile refuses any value of it.
        runner.withPropertyValues("spring.profiles.active=prod",
                "dlap.autoarchival.azure.connection-string=DefaultEndpointsProtocol=https;AccountName=prodacct;"
                        + "AccountKey=abc;EndpointSuffix=core.windows.net").run(ctx -> {
                            assertThat(ctx).hasFailed();
                            assertThat(rootMessage(ctx.getStartupFailure()))
                                    .contains("dlap.autoarchival.azure.connection-string").doesNotContain("prodacct")
                                    .doesNotContain("AccountKey");
                        });
        runner.withPropertyValues("spring.profiles.active=prod",
                "dlap.autoarchival.azure.connection-string=UseDevelopmentStorage=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void q713_theOverrideStillWorksOutsideProd() {
        runner.withPropertyValues("spring.profiles.active=dev", "dlap.autoarchival.aws.endpoint-override=http://localstack:4566",
                "dlap.autoarchival.gcs.endpoint-override=http://fake-gcs:4443").run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("dlap.autoarchival.gcs.endpoint-override=http://fake-gcs:4443")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void q713_violationsListsOnlyTheNamesOfTheSetProperties() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("dlap.autoarchival.gcs.endpoint-override", "http://x")
                .withProperty("dlap.autoarchival.aws.endpoint-override", "  ");
        assertThat(EmulatorEndpointGuard.violations(env)).containsExactly("dlap.autoarchival.gcs.endpoint-override");
        assertThat(EmulatorEndpointGuard.violations(new MockEnvironment())).isEmpty();
    }

    @Test
    void q713_theConstructorFailsFastOnAProdEnvironment() {
        MockEnvironment env = new MockEnvironment().withProperty("dlap.autoarchival.gcs.endpoint-override", "http://x");
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> new EmulatorEndpointGuard(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod profile");
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        StringBuilder all = new StringBuilder();
        while (c != null) {
            all.append(c.getMessage()).append(" | ");
            c = c.getCause();
        }
        return all.toString();
    }
}
