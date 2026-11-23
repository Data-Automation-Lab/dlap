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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.logingest.repository.SourceBindingRepository;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * LOG-02bc acceptance: with {@code dlap.logingest.pipeline=legacy}, the default, the REST path is
 * the legacy pipeline unchanged, on the partitioned V3 store (Testcontainers; requires Docker;
 * the publisher is a mock): the answer is {@code 202 PUBLISHED} every time, the published map has
 * the legacy shape, a duplicate is stored and published once, the registry columns stay empty,
 * and no registry bean exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LegacyPipelineIntegrationTest {

    private static final DataSource DB = LogIngestPostgres.newDatabase();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> LogIngestPostgres.url(DB));
        r.add("spring.datasource.username", LogIngestPostgres::user);
        r.add("spring.datasource.password", LogIngestPostgres::password);
        r.add("KAFKA_BOOTSTRAP_SERVERS", () -> "localhost:1");
        r.add("KEYCLOAK_AUTH_SERVER_URL", () -> "http://localhost:1");
        r.add("logging.level.org.apache.kafka", () -> "ERROR");
    }

    @MockitoBean
    private LogEventPublisher publisher;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SourceBindingRepository bindings;

    @Test
    @WithMockUser(roles = "DATA_ENGINEER")
    void theLegacyPathAnswersAndStoresAsBefore() throws Exception {
        String body = """
                {"sourceProvider":"AWS","sourceService":"s3.amazonaws.com","operation":"CreateBucket",
                 "changeType":"CREATE","resultStatus":"SUCCESS","eventTime":"2026-06-13T12:00:00Z",
                 "principalId":"AKIAEXAMPLE","targetResourceId":"dlap-demo","requestId":"legacy-req-1",
                 "rawEvent":{"eventVersion":"1.08"}}
                """;

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/logingest/events").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.topic").value("dlap.log.event.normalized"))
                    .andExpect(jsonPath("$.outcome").doesNotExist());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> published = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(publisher, times(1)).publish(eq("dlap-demo"), published.capture());
        assertThat(published.getValue()).containsEntry("sourceProvider", "AWS").containsEntry("changeType", "CREATE")
                .containsEntry("schemaVersion", "v1").containsKey("principal").doesNotContainKey("bindingId");
        Map<String, Object> row = new JdbcTemplate(dataSource).queryForMap("SELECT count(*) AS n, max(binding_id) "
                + "AS binding, max(source_format) AS format FROM dlap_logingest.log_events");
        assertThat(((Number) row.get("n")).intValue()).isEqualTo(1);
        assertThat(row.get("binding")).isNull();
        assertThat(row.get("format")).isNull();
        assertThat(context.getBeansOfType(RegistryIngestService.class)).isEmpty();
        assertThat(context.getBean(EffectiveLogSources.class).bindings()).isEmpty();
        assertThat(bindings.count()).isZero();
    }
}
