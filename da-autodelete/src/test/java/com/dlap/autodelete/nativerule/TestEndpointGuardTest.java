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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * EXEC-01 (Q-745): the delete executor refuses to start under the prod profile with any test-only endpoint set.
 * The message names the property, never the value; the other profiles keep the endpoint.
 */
class TestEndpointGuardTest {

    @Configuration
    @EnableConfigurationProperties(TestEndpoints.class)
    static class Settings {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Settings.class, TestEndpointGuard.class);

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /** Q-745: with nothing set the service starts under prod and under dev. */
    @Test
    void nothingSetStartsUnderProdAndUnderDev() {
        runner.withPropertyValues("spring.profiles.active=prod").run(ctx -> assertThat(ctx).hasNotFailed());
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** Q-745: the path-style switch alone is no endpoint and does not stop a prod start. */
    @Test
    void thePathStyleSwitchAloneIsNotAnEndpoint() {
        runner.withPropertyValues("spring.profiles.active=prod", "dlap.autodelete.test-endpoint.s3-path-style=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** Q-745: an S3 test endpoint is refused under prod, with the property named and the value never printed. */
    @Test
    void anS3TestEndpointIsRefusedUnderProdWithoutPrintingTheValue() {
        runner.withPropertyValues("spring.profiles.active=prod",
                "dlap.autodelete.test-endpoint.s3=http://localstack:4566").run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure()))
                            .contains("dlap.autodelete.test-endpoint.s3")
                            .contains("refuses to start under the prod profile")
                            .doesNotContain("localstack");
                });
    }

    /** Q-745: GCS and Azure test endpoints are refused under prod too, all named in one message. */
    @Test
    void gcsAndAzureTestEndpointsAreRefusedUnderProd() {
        runner.withPropertyValues("spring.profiles.active=prod",
                "dlap.autodelete.test-endpoint.gcs=http://fake-gcs:4443",
                "dlap.autodelete.test-endpoint.azure=https://arm-stub:8443/").run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure()))
                            .contains("dlap.autodelete.test-endpoint.gcs")
                            .contains("dlap.autodelete.test-endpoint.azure")
                            .doesNotContain("fake-gcs").doesNotContain("arm-stub");
                });
    }

    /** Q-745: the other profiles keep the endpoint (the harness and the container tests rely on it). */
    @Test
    void theDevProfileAndNoProfileKeepTheEndpoint() {
        runner.withPropertyValues("spring.profiles.active=dev", "dlap.autodelete.test-endpoint.s3=http://localstack:4566")
                .run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("dlap.autodelete.test-endpoint.gcs=http://fake-gcs:4443")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** Q-745: the shipped defaults are empty, so a prod start with the shipped file is allowed. */
    @Test
    void theShippedDefaultsAreEmpty() throws Exception {
        java.util.Properties shipped = new java.util.Properties();
        try (var in = TestEndpointGuardTest.class.getResourceAsStream("/application.properties")) {
            shipped.load(in);
        }
        assertThat(shipped.getProperty("dlap.autodelete.test-endpoint.s3")).isEqualTo("${DLAP_AUTODELETE_TEST_ENDPOINT_S3:}");
        assertThat(shipped.getProperty("dlap.autodelete.test-endpoint.gcs")).isEqualTo("${DLAP_AUTODELETE_TEST_ENDPOINT_GCS:}");
        assertThat(shipped.getProperty("dlap.autodelete.test-endpoint.azure"))
                .isEqualTo("${DLAP_AUTODELETE_TEST_ENDPOINT_AZURE:}");
    }
}
