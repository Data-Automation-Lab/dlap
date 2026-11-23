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

import com.dlap.logingest.model.BindingState;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.model.SourceBindingEntity;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.logingest.repository.SourceBindingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The configuration mirror (design B2 "Where the config lives") on the V3 table, through JPA with
 * {@code ddl-auto=validate} (Testcontainers; requires Docker): file and built-in bindings are
 * written with their state and hashes, a binding that leaves the file becomes REMOVED and keeps
 * its first-sync time when it comes back, and V4's MIGRATED rows are left alone.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LogSourceConfigSynchronizer.class, LogSourceConfigSynchronizerTest.Empty.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LogSourceConfigSynchronizerTest {

    private static final DataSource DB = LogIngestPostgres.newDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> LogIngestPostgres.url(DB));
        r.add("spring.datasource.username", LogIngestPostgres::user);
        r.add("spring.datasource.password", LogIngestPostgres::password);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /** The startup mirror runs on an empty configuration. */
    @TestConfiguration
    static class Empty {
        @Bean
        EffectiveLogSources effectiveLogSources() {
            return new EffectiveLogSources(PipelineMode.LEGACY, "FILE", List.of(), ValidationReport.CLEAN);
        }
    }

    @Autowired
    private LogSourceConfigSynchronizer synchronizer;

    @Autowired
    private SourceBindingRepository rows;

    private static EffectiveLogSources sources(String yaml, PipelineMode pipeline) {
        LogSourceConfigValidator.Result r = TestFamilies.validate(yaml, pipeline);
        List<EffectiveBinding> all = new ArrayList<>(r.bindings());
        if (pipeline == PipelineMode.REGISTRY) {
            all.addAll(BuiltinBindings.all());
        }
        return new EffectiveLogSources(pipeline, "FILE", all, r.report());
    }

    private static final String TWO = """
            dlap:
              logingest:
                sources:
                  - id: aws
                    kind: TEST_AWS
                    bindings:
                      - { id: acme-a, scope: { account-id: "111111111111" }, stream: { transport: LOOKUP_EVENTS }, format: { envelope: lookup-events } }
                      - { id: acme-b, enabled: false, scope: { account-id: "222222222222" }, stream: { transport: LOOKUP_EVENTS }, format: { envelope: lookup-events } }
                      - { id: acme-c, scope: { account-id: "333333333333" }, stream: { transport: LOOKUP_EVENTS }, format: { envelope: lookup-events, version: "9.0" } }
            """;

    @Test
    void mirrorsBindingsWithStateAndHashesAndMarksRemovedOnes() {
        SourceBindingEntity migrated = new SourceBindingEntity();
        migrated.setBindingId("legacy-aws-us-east-1");
        migrated.setSourceId("legacy-aws");
        migrated.setSourceKind("AWS_CLOUDTRAIL");
        migrated.setConfigOrigin(ConfigOrigin.MIGRATED);
        migrated.setState(BindingState.MIGRATED);
        migrated.setScope(Map.of("regions", List.of("us-east-1")));
        migrated.setEffectiveConfig(Map.of());
        migrated.setConfigHash("1".repeat(64));
        migrated.setFirstSyncedAt(Instant.parse("2026-10-01T00:00:00Z"));
        migrated.setLastSyncedAt(Instant.parse("2026-10-01T00:00:00Z"));
        rows.save(migrated);

        EffectiveLogSources first = sources(TWO, PipelineMode.REGISTRY);
        LogSourceConfigSynchronizer.Result r1 = synchronizer.sync(first);

        assertThat(r1.created()).isEqualTo(3 + BuiltinBindings.all().size());
        assertThat(rows.findById("acme-a")).hasValueSatisfying(b -> {
            assertThat(b.getConfigOrigin()).isEqualTo(ConfigOrigin.FILE);
            assertThat(b.getState()).isEqualTo(BindingState.ACTIVE);
            assertThat(b.getConfigHash()).isEqualTo(first.binding("acme-a").orElseThrow().configHash());
            assertThat(b.getDeploymentConfigHash()).isEqualTo(first.configHash());
            assertThat(b.getScope()).containsEntry("account-id", "111111111111");
            assertThat(b.getEffectiveConfig()).containsKey("streams");
        });
        assertThat(rows.findById("acme-b")).hasValueSatisfying(b -> assertThat(b.getState()).isEqualTo(BindingState.DISABLED));
        assertThat(rows.findById("acme-c")).hasValueSatisfying(b -> assertThat(b.getState()).isEqualTo(BindingState.INVALID));
        assertThat(rows.findById("dlap-rest-aws")).hasValueSatisfying(b -> {
            assertThat(b.getConfigOrigin()).isEqualTo(ConfigOrigin.BUILTIN);
            assertThat(b.getState()).isEqualTo(BindingState.ACTIVE);
        });
        Instant firstSynced = rows.findById("acme-a").orElseThrow().getFirstSyncedAt();

        String withoutA = TWO.lines().filter(l -> !l.contains("id: acme-a,"))
                .collect(java.util.stream.Collectors.joining("\n"));
        LogSourceConfigSynchronizer.Result r2 = synchronizer.sync(sources(withoutA, PipelineMode.LEGACY));

        assertThat(r2.removed()).isEqualTo(1 + BuiltinBindings.all().size());
        assertThat(rows.findById("acme-a")).hasValueSatisfying(b -> {
            assertThat(b.getState()).isEqualTo(BindingState.REMOVED);
            assertThat(b.getRemovedAt()).isNotNull();
        });
        assertThat(rows.findById("dlap-rest-aws")).hasValueSatisfying(b ->
                assertThat(b.getState()).isEqualTo(BindingState.REMOVED));
        assertThat(rows.findById("legacy-aws-us-east-1")).hasValueSatisfying(b -> {
            assertThat(b.getState()).as("MIGRATED rows are left alone").isEqualTo(BindingState.MIGRATED);
            assertThat(b.getRemovedAt()).isNull();
        });

        synchronizer.sync(first);
        assertThat(rows.findById("acme-a")).hasValueSatisfying(b -> {
            assertThat(b.getState()).isEqualTo(BindingState.ACTIVE);
            assertThat(b.getRemovedAt()).isNull();
            assertThat(b.getFirstSyncedAt()).isEqualTo(firstSynced);
        });
    }
}
