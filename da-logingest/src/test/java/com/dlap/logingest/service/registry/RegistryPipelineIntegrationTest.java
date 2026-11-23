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

package com.dlap.logingest.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.LogSourcesInfoContributor;
import com.dlap.logingest.demo.FixtureReplaySeeder;
import com.dlap.logingest.health.BindingStatusTopicPublisher;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.model.BindingState;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.quarantine.QuarantineTopicPublisher;
import com.dlap.logingest.repository.LogEventPartitionRepository;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.logingest.repository.SourceBindingRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * LOG-02bc acceptance: with {@code dlap.logingest.pipeline=registry} the whole service runs REST
 * ingest and the fixture replay through the parser registry, on PostgreSQL with Flyway V1 to V4
 * and {@code ddl-auto=validate} (Testcontainers; requires Docker; Kafka is not started, the
 * publisher is a mock).
 *
 * <p>Asserted: the replay seeder stored its fixtures under {@code dlap-replay-aws} with
 * {@code ORIGIN_REPLAY} at startup and does not republish on a second run; a REST event is
 * stored under {@code dlap-rest-aws} with the registry columns and published once, its duplicate
 * answers {@code DUPLICATE} and is not published again, and a REST copy of a replayed fixture
 * event dedups across channels; the built-in bindings are mirrored ACTIVE with the deployment
 * hash; {@code /actuator/info} carries the hash; {@code log_events} is partitioned.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistryPipelineIntegrationTest {

    private static final DataSource DB = LogIngestPostgres.newDatabase();
    private static final Path FIXTURES = fixtures();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> LogIngestPostgres.url(DB));
        r.add("spring.datasource.username", LogIngestPostgres::user);
        r.add("spring.datasource.password", LogIngestPostgres::password);
        r.add("dlap.logingest.pipeline", () -> "registry");
        r.add("dlap.logingest.replay.enabled", () -> "true");
        r.add("dlap.logingest.replay.dir", FIXTURES::toString);
        r.add("KAFKA_BOOTSTRAP_SERVERS", () -> "localhost:1");
        r.add("KEYCLOAK_AUTH_SERVER_URL", () -> "http://localhost:1");
        r.add("logging.level.org.apache.kafka", () -> "ERROR");
    }

    private static Path fixtures() {
        try {
            Path dir = Files.createTempDirectory("log02bc-replay");
            Files.writeString(dir.resolve("aws-events.json"), """
                    [
                      {"sourceProvider":"AWS","sourceService":"s3.amazonaws.com","operation":"PutBucket",
                       "changeType":"CREATE","resultStatus":"SUCCESS","eventTime":"2026-07-05T10:00:00Z",
                       "principalId":"AIDAEXAMPLE0001","principalType":"IAMUser","targetResourceKind":"AWS::S3::Bucket",
                       "targetResourceId":"s3://dlap-it-logs","targetProject":"123456789012","targetRegion":"us-east-1",
                       "requestId":"it-evt-0001","rawEvent":{"eventName":"CreateBucket"}},
                      {"sourceProvider":"AWS","sourceService":"s3.amazonaws.com","operation":"GetObject",
                       "changeType":"ACCESS","resultStatus":"SUCCESS","eventTime":"2026-07-05T11:00:00Z",
                       "principalId":"AIDAEXAMPLE0002","targetResourceId":"s3://dlap-it-logs/a.csv",
                       "requestId":"it-evt-0002","rawEvent":{"eventName":"GetObject"}}
                    ]
                    """);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @MockitoBean
    private LogEventPublisher publisher;

    // LOG-03: the quarantine sink and the status publisher would send to a Kafka that is not running here.
    @MockitoBean
    private QuarantineTopicPublisher quarantineTopic;

    @MockitoBean
    private BindingStatusTopicPublisher statusTopic;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EffectiveLogSources sources;

    @Autowired
    private SourceBindingRepository bindings;

    @Autowired
    private LogSourcesInfoContributor info;

    @Autowired
    private FixtureReplaySeeder seeder;

    @Autowired
    private LogEventPartitionRepository partitions;

    @Autowired
    private DataSource dataSource;

    private static final String REST_EVENT = """
            {"sourceProvider":"AWS","sourceService":"s3.amazonaws.com","operation":"DeleteObject",
             "changeType":"DELETE","resultStatus":"SUCCESS","eventTime":"2026-07-06T09:00:00Z",
             "targetResourceId":"s3://dlap-it-logs/b.csv","requestId":"it-rest-0001","rawEvent":{"eventName":"DeleteObject"}}
            """;

    @Test
    @WithMockUser(roles = "ADMIN")
    void restAndReplayGoThroughTheRegistryAndDuplicatesAreNotRepublished() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Startup: the seeder replayed both fixture events on the replay channel. (The mock's
        // startup invocations are reset after whichever test method runs first, so the rows are
        // the evidence here.)
        List<Map<String, Object>> replayed = jdbc.queryForList("SELECT binding_id, origin, source_format, "
                + "parse_status FROM dlap_logingest.log_events WHERE binding_id = 'dlap-replay-aws'");
        assertThat(replayed).hasSize(2).allSatisfy(r -> {
            assertThat(r).containsEntry("origin", "ORIGIN_REPLAY")
                    .containsEntry("source_format", "DLAP_NORMALIZED_AWS/ingest-dto")
                    .containsEntry("parse_status", "PARSE_STATUS_WARNINGS");
        });
        clearInvocations(publisher);

        // A second start of the seeder replays once: nothing stored is published again.
        seeder.run(new DefaultApplicationArguments());
        verify(publisher, never()).publish(anyString(), anyMap());

        // REST: first delivery published, the same event again is a duplicate.
        mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content(REST_EVENT))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.outcome").value("PARSED_WITH_WARNINGS"));
        mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content(REST_EVENT))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
        verify(publisher, times(1)).publish(anyString(), anyMap());
        Map<String, Object> row = jdbc.queryForMap("SELECT binding_id, origin, event_category, change_type, "
                + "target_canonical_uri, native_event_id, raw_event::text AS raw FROM dlap_logingest.log_events "
                + "WHERE native_event_id = 'it-rest-0001'");
        assertThat(row).containsEntry("binding_id", "dlap-rest-aws").containsEntry("origin", "ORIGIN_CLOUD_LOG")
                .containsEntry("event_category", "EVENT_CATEGORY_MANAGEMENT").containsEntry("change_type", "DELETE")
                .containsEntry("target_canonical_uri", "s3://dlap-it-logs/b.csv");
        assertThat((String) row.get("raw")).contains("DeleteObject");

        // A REST copy of a replayed fixture event has the same event id and is not published again.
        mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceProvider":"AWS","operation":"GetObject","changeType":"ACCESS","resultStatus":"SUCCESS",
                         "eventTime":"2026-07-05T11:00:00Z","targetResourceId":"s3://dlap-it-logs/a.csv",
                         "requestId":"it-evt-0002"}
                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
        verify(publisher, times(1)).publish(anyString(), anyMap());

        // A record without a result is quarantined; its pass-through keeps the audit fact.
        mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceProvider":"AWS","operation":"GetObject","changeType":"ACCESS",
                         "eventTime":"2026-07-05T12:00:00Z","targetResourceId":"s3://dlap-it-logs/c.csv",
                         "requestId":"it-rest-0003"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.quarantineId").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT parse_status FROM dlap_logingest.log_events WHERE native_event_id = "
                + "'it-rest-0003'", String.class)).isEqualTo("PARSE_STATUS_PARTIAL");
    }

    @Test
    void theBuiltInBindingsAreMirroredAndTheInfoEndpointShowsTheHash() {
        assertThat(bindings.findByConfigOriginIn(List.of(ConfigOrigin.BUILTIN)))
                .hasSize(BuiltinBindings.all().size())
                .allSatisfy(b -> {
                    assertThat(b.getState()).isEqualTo(BindingState.ACTIVE);
                    assertThat(b.getDeploymentConfigHash()).isEqualTo(sources.configHash());
                });
        Map<String, Object> details = new InfoEndpoint(List.of(info)).info();
        assertThat(details).extractingByKey("logSources").asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("configHash", sources.configHash())
                .containsEntry("pipeline", "registry");
        assertThat(partitions.isPartitioned()).isTrue();
    }
}
