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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * EXEC-01 (Q-740, Q-745): da-autoarchival uses the scheme of both retire executors,
 * {@code dlap.autoarchival.test-endpoint.{s3, s3-path-style, gcs, azure}}. The RET-03 names keep working for one
 * release as deprecated aliases that log a WARN naming the new property; the prod profile refuses every one of them.
 */
class TestEndpointSchemeTest {

    /** Reads the settings the way the appliers' constructors do. */
    @Configuration
    static class Probe {
        @Bean
        Map<String, String> endpoints(@Value(TestEndpointProperties.S3) String s3,
                @Value(TestEndpointProperties.GCS) String gcs, @Value(TestEndpointProperties.AZURE) String azure,
                @Value(TestEndpointProperties.S3_PATH_STYLE) String pathStyle) {
            return Map.of("s3", s3, "gcs", gcs, "azure", azure, "pathStyle", pathStyle);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Probe.class, EmulatorEndpointGuard.class);

    private ListAppender<ILoggingEvent> logs;
    private Logger guardLog;

    @BeforeEach
    void captureLogs() {
        guardLog = (Logger) LoggerFactory.getLogger(EmulatorEndpointGuard.class);
        logs = new ListAppender<>();
        logs.start();
        guardLog.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        guardLog.detachAppender(logs);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> endpoints(ApplicationContext ctx) {
        return (Map<String, String>) ctx.getBean("endpoints");
    }

    /** Q-740: nothing set means no test endpoint, and S3 is path-style for when it gets one. */
    @Test
    void theDefaultIsNoEndpointAndPathStyle() {
        runner.run(ctx -> {
            assertThat(endpoints(ctx)).containsEntry("s3", "").containsEntry("gcs", "").containsEntry("azure", "")
                    .containsEntry("pathStyle", "true");
            assertThat(warnings()).isEmpty();
        });
    }

    /** Q-740: the new names are read as they are, and win over the old names. */
    @Test
    void theNewNamesAreReadAndWinOverTheOldOnes() {
        runner.withPropertyValues("dlap.autoarchival.test-endpoint.s3=http://new-s3:4566",
                "dlap.autoarchival.test-endpoint.s3-path-style=false",
                "dlap.autoarchival.test-endpoint.gcs=http://new-gcs:4443",
                "dlap.autoarchival.test-endpoint.azure=https://new-azure:10000",
                "dlap.autoarchival.aws.endpoint-override=http://old-s3:4566",
                "dlap.autoarchival.gcs.endpoint-override=http://old-gcs:4443").run(ctx ->
                        assertThat(endpoints(ctx)).containsEntry("s3", "http://new-s3:4566")
                                .containsEntry("gcs", "http://new-gcs:4443")
                                .containsEntry("azure", "https://new-azure:10000")
                                .containsEntry("pathStyle", "false"));
    }

    /** Q-740: the harness sets the new names as environment variables; relaxed names reach the same expressions. */
    @Test
    void theEnvironmentVariablesOfTheNewNamesAreRead() {
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addLast(
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                        "DLAP_AUTOARCHIVAL_TEST_ENDPOINT_S3", "http://e2e-localstack:4566",
                        "DLAP_AUTOARCHIVAL_TEST_ENDPOINT_S3_PATH_STYLE", "true",
                        "DLAP_AUTOARCHIVAL_TEST_ENDPOINT_GCS", "http://e2e-gcs:4443")))).run(ctx ->
                                assertThat(endpoints(ctx)).containsEntry("s3", "http://e2e-localstack:4566")
                                        .containsEntry("gcs", "http://e2e-gcs:4443"));
    }

    /** Q-740: the RET-03 names still work, each logs a WARN naming its replacement; nothing is refused outside prod. */
    @Test
    void theDeprecatedAliasesStillWorkWithAWarn() {
        runner.withPropertyValues("dlap.autoarchival.aws.endpoint-override=http://old-s3:4566",
                "dlap.autoarchival.aws.endpoint-access-key=AKIAOLDKEY",
                "dlap.autoarchival.aws.endpoint-secret-key=old-secret",
                "dlap.autoarchival.gcs.endpoint-override=http://old-gcs:4443").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(endpoints(ctx)).containsEntry("s3", "http://old-s3:4566")
                            .containsEntry("gcs", "http://old-gcs:4443");
                    List<String> warned = warnings();
                    assertThat(warned).anyMatch(w -> w.contains("dlap.autoarchival.aws.endpoint-override")
                            && w.contains("DEPRECATED") && w.contains("dlap.autoarchival.test-endpoint.s3"));
                    assertThat(warned).anyMatch(w -> w.contains("dlap.autoarchival.gcs.endpoint-override")
                            && w.contains("dlap.autoarchival.test-endpoint.gcs"));
                    assertThat(warned).anyMatch(w -> w.contains("dlap.autoarchival.aws.endpoint-access-key"));
                    assertThat(String.join(" ", warned)).doesNotContain("AKIAOLDKEY").doesNotContain("old-secret")
                            .doesNotContain("old-s3");
                });
    }

    /** Q-740, Q-745: every new name and every deprecated alias is refused under prod; no value is printed. */
    @Test
    void everyNameIsRefusedUnderProdWithoutPrintingTheValue() {
        Map<String, String> names = Map.of(
                "dlap.autoarchival.test-endpoint.s3", "http://s3-secret-host:4566",
                "dlap.autoarchival.test-endpoint.gcs", "http://gcs-secret-host:4443",
                "dlap.autoarchival.test-endpoint.azure", "https://azure-secret-host:10000",
                "dlap.autoarchival.aws.endpoint-override", "http://old-s3-secret-host:4566",
                "dlap.autoarchival.aws.endpoint-access-key", "AKIASECRETKEYID",
                "dlap.autoarchival.aws.endpoint-secret-key", "very-secret",
                "dlap.autoarchival.gcs.endpoint-override", "http://old-gcs-secret-host:4443");
        names.forEach((name, value) -> runner.withPropertyValues("spring.profiles.active=prod", name + "=" + value)
                .run(ctx -> {
                    assertThat(ctx).as(name).hasFailed();
                    String message = String.valueOf(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ctx.getStartupFailure()).getMessage());
                    assertThat(message).contains(name).contains("refuses to start under the prod profile")
                            .doesNotContain("secret-host").doesNotContain("AKIASECRETKEYID")
                            .doesNotContain("very-secret");
                }));
    }

    /** Q-740: the path-style switch alone is no endpoint. */
    @Test
    void thePathStyleSwitchAloneStartsUnderProd() {
        runner.withPropertyValues("spring.profiles.active=prod", "dlap.autoarchival.test-endpoint.s3-path-style=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** Q-724: the connection string outside prod works but warns; it is named, its value is not. */
    @Test
    void theConnectionStringWarnsOutsideProd() {
        runner.withPropertyValues("dlap.autoarchival.azure.connection-string=DefaultEndpointsProtocol=https;"
                + "AccountName=acct;AccountKey=abc").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(warnings()).anyMatch(w -> w.contains("dlap.autoarchival.azure.connection-string")
                            && w.contains("DEPRECATED") && w.contains("account-endpoint"));
                    assertThat(String.join(" ", warnings())).doesNotContain("AccountKey").doesNotContain("acct");
                });
    }

    /** Q-740: the shipped properties set none of the endpoints, the connection string or the role. */
    @Test
    void theShippedDefaultsSetNoEndpointAndNoRole() throws Exception {
        Properties shipped = new Properties();
        try (var in = TestEndpointSchemeTest.class.getResourceAsStream("/application.properties")) {
            shipped.load(in);
        }
        for (String name : List.of("dlap.autoarchival.test-endpoint.s3", "dlap.autoarchival.test-endpoint.gcs",
                "dlap.autoarchival.test-endpoint.azure")) {
            assertThat(shipped.getProperty(name)).as(name + " is not defined in the shipped file").isNull();
        }
        assertThat(shipped.getProperty("dlap.autoarchival.aws.endpoint-override")).isEmpty();
        assertThat(shipped.getProperty("dlap.autoarchival.aws.credentials-ref"))
                .isEqualTo("${DLAP_AUTOARCHIVAL_AWS_CREDENTIALS_REF:}");
        assertThat(shipped.getProperty("dlap.autoarchival.azure.account-endpoint"))
                .isEqualTo("${DLAP_AUTOARCHIVAL_AZURE_ACCOUNT_ENDPOINT:}");
    }
}
