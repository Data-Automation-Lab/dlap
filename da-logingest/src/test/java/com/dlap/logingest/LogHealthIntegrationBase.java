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

package com.dlap.logingest;

import com.dlap.logingest.health.BindingStatusTopicPublisher;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.metrics.ParseTelemetry;
import com.dlap.logingest.quarantine.QuarantineTopicPublisher;
import com.dlap.logingest.repository.LogIngestPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The service with the registry pipeline on PostgreSQL (Testcontainers, needs Docker) and the three
 * Kafka-facing beans mocked, so no broker is needed: the event publisher, the quarantine topic
 * publisher and the binding-status topic publisher. The scheduled jobs are pushed a day out; tests
 * call the services directly. Subclasses share one Spring context and one database.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class LogHealthIntegrationBase {

    protected static final DataSource DB = LogIngestPostgres.newDatabase();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> LogIngestPostgres.url(DB));
        r.add("spring.datasource.username", LogIngestPostgres::user);
        r.add("spring.datasource.password", LogIngestPostgres::password);
        r.add("dlap.logingest.pipeline", () -> "registry");
        r.add("dlap.logingest.health.status-initial-delay", () -> "PT24H");
        r.add("dlap.logingest.metrics.snapshot-initial-delay", () -> "PT24H");
        r.add("management.prometheus.metrics.export.enabled", () -> "true");
        r.add("KAFKA_BOOTSTRAP_SERVERS", () -> "localhost:1");
        r.add("KEYCLOAK_AUTH_SERVER_URL", () -> "http://localhost:1");
        r.add("logging.level.org.apache.kafka", () -> "ERROR");
    }

    @MockitoBean
    protected LogEventPublisher publisher;

    @MockitoBean
    protected QuarantineTopicPublisher quarantineTopic;

    @MockitoBean
    protected BindingStatusTopicPublisher statusTopic;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MeterRegistry meters;

    @Autowired
    protected ParseTelemetry telemetry;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    protected void cleanTables() {
        jdbc.update("DELETE FROM dlap_logingest.quarantine_record");
        jdbc.update("DELETE FROM dlap_logingest.log_events");
        jdbc.update("DELETE FROM dlap_logingest.format_observation");
        org.mockito.Mockito.clearInvocations(publisher, quarantineTopic, statusTopic);
    }
}
