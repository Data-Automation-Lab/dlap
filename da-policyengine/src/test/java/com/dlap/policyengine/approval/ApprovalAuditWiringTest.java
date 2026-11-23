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

package com.dlap.policyengine.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.dlap.common.audit.AuditLogWriter;

/**
 * The audit writer of the withdrawal is a bean of the approval configuration, built from the
 * service's own data source settings without opening a connection (the service starts without the
 * audit database, and a withdrawal then fails closed).
 */
class ApprovalAuditWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC))
            .withUserConfiguration(ApprovalConfiguration.class)
            .withPropertyValues("dlap.kafka.provision-topics=false");

    @Test
    void theWriterIsBuiltFromTheServiceDataSourceSettingsWithoutConnecting() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://nowhere.invalid:5432/da_policyengine",
                        "spring.datasource.username=da_policyengine_user", "spring.datasource.password=unused")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuditLogWriter.class);
                });
    }

    @Test
    void theWriterNeedsNoSettingsToExistSoTheServiceStartsWithoutTheAuditDatabase() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuditLogWriter.class);
        });
    }

    @Test
    void anExplicitAuditUrlWins() {
        runner.withPropertyValues("dlap.audit.datasource.url=jdbc:postgresql://audit.invalid:5432/dlap_common",
                        "dlap.audit.datasource.username=audit_user", "dlap.audit.datasource.password=unused")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AuditLogWriter.class));
    }
}
