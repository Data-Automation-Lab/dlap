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

package com.dlap.adminservice.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The wiring of {@link AuditConfiguration}: the service starts without any {@code dlap.audit}
 * setting, a checkpoint job without a key or a sink stops startup (fail closed), and with both the
 * scheduler exists. No database is touched: the pool connects lazily.
 */
class AuditConfigurationTest {

    @TempDir
    Path dir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AuditConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withPropertyValues("spring.datasource.url=jdbc:postgresql://db.internal:5432/da_admin?sslmode=require",
                    "spring.datasource.username=da_admin_user", "spring.datasource.password=from-service");

    @Test
    void withoutSettingsTheVerifierExistsAndNoJobIsScheduled() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(AuditVerificationService.class).doesNotHaveBean(CheckpointScheduler.class);
            assertThat(ctx.getBean(AuditDatabase.class).url())
                    .isEqualTo("jdbc:postgresql://db.internal:5432/dlap_common?sslmode=require");
            assertThat(ctx.getBean(CheckpointService.class).isConfigured()).isFalse();
        });
    }

    @Test
    void anExplicitDatasourceWins() {
        runner.withPropertyValues("dlap.audit.datasource.url=jdbc:postgresql://other:5433/dlap_common")
                .run(ctx -> assertThat(ctx.getBean(AuditDatabase.class).url())
                        .isEqualTo("jdbc:postgresql://other:5433/dlap_common"));
    }

    @Test
    void anEnabledJobWithoutAKeyOrASinkStopsStartup() {
        runner.withPropertyValues("dlap.audit.checkpoint.enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("dlap.audit.signing.private-key-file");
        });
        AuditProperties.Signing s = AuditTestKeys.write(AuditTestKeys.newPair(), dir, "k");
        runner.withPropertyValues("dlap.audit.checkpoint.enabled=true",
                "dlap.audit.signing.private-key-file=" + s.getPrivateKeyFile(),
                "dlap.audit.signing.public-key-file=" + s.getPublicKeyFile()).run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("dlap.audit.export.sink");
                });
    }

    @Test
    void anEnabledJobWithKeyAndSinkIsScheduled() {
        AuditProperties.Signing s = AuditTestKeys.write(AuditTestKeys.newPair(), dir, "k");
        runner.withPropertyValues("dlap.audit.checkpoint.enabled=true",
                "dlap.audit.checkpoint.interval=PT10M",
                "dlap.audit.signing.private-key-file=" + s.getPrivateKeyFile(),
                "dlap.audit.signing.public-key-file=" + s.getPublicKeyFile(),
                "dlap.audit.export.sink=FILESYSTEM",
                "dlap.audit.export.filesystem.directory=" + dir).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(CheckpointScheduler.class);
                    assertThat(ctx.getBean(CheckpointService.class).isConfigured()).isTrue();
                    assertThat(ctx.getBean(AuditProperties.class).getCheckpoint().getInterval().toMinutes()).isEqualTo(10);
                });
    }

    @Test
    void theCommonUrlKeepsHostPortAndParameters() {
        assertThat(AuditDatabase.commonUrlNextTo("jdbc:postgresql://postgresql:5432/da_admin"))
                .isEqualTo("jdbc:postgresql://postgresql:5432/dlap_common");
        assertThat(AuditDatabase.commonUrlNextTo(" jdbc:postgresql://h/da_admin?ssl=true&sslmode=verify-full "))
                .isEqualTo("jdbc:postgresql://h/dlap_common?ssl=true&sslmode=verify-full");
        assertThatThrownBy(() -> AuditDatabase.commonUrlNextTo("jdbc:h2:mem:x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditDatabase.commonUrlNextTo(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
