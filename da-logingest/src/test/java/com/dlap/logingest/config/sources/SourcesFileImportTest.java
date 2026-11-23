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

package com.dlap.logingest.config.sources;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The shipped {@code application.properties} loads the bindings file through
 * {@code spring.config.import=optional:file:${DLAP_LOGINGEST_SOURCES_FILE:/etc/dlap/log-sources.yml}}:
 * with the variable unset (and no file at the default path) the application starts with no
 * bindings, and with it set the file's {@code dlap.logingest} keys bind into
 * {@link LogSourcesProperties}. The default exists because Spring Boot refuses an import whose
 * placeholder does not resolve.
 */
class SourcesFileImportTest {

    /** No beans: only the environment is under test. */
    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    private static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args);
    }

    @Test
    void withoutTheVariableTheApplicationStartsWithNoBindings() {
        try (ConfigurableApplicationContext ctx = start()) {
            assertThat(ctx.getEnvironment().getProperty("spring.config.import"))
                    .isEqualTo("optional:file:/etc/dlap/log-sources.yml");
            assertThat(ctx.getEnvironment().getProperty("dlap.logingest.sources[0].id")).isNull();
        }
    }

    @Test
    void theNamedFileIsImportedAndBinds(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("log-sources.yml");
        Files.writeString(file, """
                dlap:
                  logingest:
                    pipeline: registry
                    sources:
                      - id: gcp
                        kind: GCP_CLOUD_AUDIT
                        bindings:
                          - { id: gcp-p1, scope: { project-id: my-project-1 }, stream: { transport: LOGGING_API } }
                """);

        try (ConfigurableApplicationContext ctx = start("--DLAP_LOGINGEST_SOURCES_FILE=" + file)) {
            LogSourcesProperties p = Binder.get(ctx.getEnvironment())
                    .bind(LogSourcesProperties.PREFIX, LogSourcesProperties.class).get();
            assertThat(p.getSources()).singleElement().satisfies(s -> {
                assertThat(s.getKind()).isEqualTo("GCP_CLOUD_AUDIT");
                assertThat(s.getBindings().get(0).getScope().getProjectId()).isEqualTo("my-project-1");
            });
            assertThat(ctx.getEnvironment().getProperty("dlap.logingest.pipeline"))
                    .as("the file overrides application.properties").isEqualTo("registry");
        }
    }
}
