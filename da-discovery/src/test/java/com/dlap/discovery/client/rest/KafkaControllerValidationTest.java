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
package com.dlap.discovery.client.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.discovery.common.service.DiscoveryKafkaProducer;

/**
 * SEC-03 (Q-027): the body of {@code POST /api/kafka/publish} is validated before the producer is
 * reached. The role rule is unchanged (ADMIN only) and is pinned here too.
 */
@WebMvcTest(
    controllers = KafkaController.class,
    excludeAutoConfiguration = { OAuth2ClientAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class }
)
@ActiveProfiles("test")
class KafkaControllerValidationTest {

    @SpringBootConfiguration
    @Import({ TestWebSecurityConfiguration.class, KafkaController.class })
    static class TestApp {}

    private static final String PATH = "/api/kafka/publish";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiscoveryKafkaProducer producer;

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aWellFormedMessageIsSent() throws Exception {
        mockMvc
            .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"topic\":\"demo.topic-1_a\",\"message\":\"hi\"}"))
            .andExpect(status().isOk());

        verify(producer).sendMessage("demo.topic-1_a", "hi");
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aMissingOrBlankTopicIsABadRequest() throws Exception {
        for (String body : new String[] {
            "{\"message\":\"hi\"}",
            "{\"topic\":\"\",\"message\":\"hi\"}",
            "{\"topic\":\"   \",\"message\":\"hi\"}",
        }) {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(producer);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aTopicWithIllegalCharactersOrTooLongIsABadRequest() throws Exception {
        String tooLong = "t".repeat(KafkaController.KafkaMessageRequest.MAX_TOPIC_LENGTH + 1);
        for (String topic : new String[] {"a b", "a/b", "../etc", "a,b", "topic\\u0000", tooLong}) {
            mockMvc
                .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"topic\":\"" + topic + "\",\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(producer);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aMissingOrOversizedMessageIsABadRequest() throws Exception {
        String huge = "m".repeat(KafkaController.KafkaMessageRequest.MAX_MESSAGE_LENGTH + 1);
        for (String body : new String[] {"{\"topic\":\"demo\"}", "{\"topic\":\"demo\",\"message\":\"" + huge + "\"}"}) {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(producer);
    }

    @Test
    @WithMockUser(authorities = "ROLE_DATA_ANALYST")
    void aNonAdminIsStillForbidden() throws Exception {
        mockMvc
            .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"topic\":\"demo\",\"message\":\"hi\"}"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(producer);
    }
}
