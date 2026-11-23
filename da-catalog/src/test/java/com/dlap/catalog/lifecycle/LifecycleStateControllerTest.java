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

package com.dlap.catalog.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dlap.catalog.common.GlobalExceptionHandler;
import com.dlap.catalog.config.SecurityConfiguration;
import com.dlap.catalog.lifecycle.okf.OkfFrontmatterCheck;

/**
 * {@code @WebMvcTest} slice for {@link LifecycleStateController}: method security is enforced (every
 * reader role and the machine principal may read; only ADMIN may export), status codes, and the body
 * is the one form of the emitter's object.
 */
@WebMvcTest(LifecycleStateController.class)
@ContextConfiguration(classes = LifecycleStateControllerTest.TestApp.class)
@Import({SecurityConfiguration.class, GlobalExceptionHandler.class})
class LifecycleStateControllerTest {

    /** Web, security and validation only: no JPA, no Kafka. */
    @SpringBootConfiguration
    @ImportAutoConfiguration({WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, ValidationAutoConfiguration.class, SecurityAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class})
    @EnableConfigurationProperties(LifecycleStateProperties.class)
    @ComponentScan(basePackageClasses = LifecycleStateController.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = LifecycleStateController.class))
    static class TestApp {
    }

    private static final UUID ASSET = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final Instant AS_OF = Instant.parse("2026-10-31T12:00:00Z");
    private static final String ONE = "/api/v1/catalog/assets/" + ASSET + "/lifecycle-state";
    private static final String LIST = "/api/v1/catalog/lifecycle-states";
    private static final String OKF = "/api/v1/catalog/lifecycle-states/okf";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LifecycleStateEmitter emitter;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static LifecycleState azure() {
        return LifecycleStateJson.parse(LifecycleStateSchema.example("valid-azure-container-unknown-read.json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER", "DATA_SCIENTIST",
            "DATA_ANALYST", "AUDITOR", "VIEWER", "SERVICE_ACCOUNT"})
    void everyReaderRoleGetsTheObjectInItsOneForm(String role) throws Exception {
        given(emitter.emit(ASSET)).willReturn(Optional.of(new LifecycleStateEmitter.Outcome(ASSET, azure(), null)));

        MvcResult result = mockMvc.perform(get(ONE).with(user("u").roles(role)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).isEqualTo(LifecycleStateJson.compact(azure()));
        LifecycleStateSchema.assertValid(body);
    }

    @Test
    void readingNeedsARoleAndAToken() throws Exception {
        mockMvc.perform(get(ONE).with(user("u").roles("GUEST"))).andExpect(status().isForbidden());
        mockMvc.perform(get(ONE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(LIST)).andExpect(status().isUnauthorized());
        verify(emitter, never()).emit(any());
    }

    @Test
    void aMissingAssetIs404AndAnUnrepresentableOneIs422() throws Exception {
        given(emitter.emit(ASSET)).willReturn(Optional.empty());
        mockMvc.perform(get(ONE).with(user("u").roles("AUDITOR"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        given(emitter.emit(ASSET)).willReturn(Optional.of(new LifecycleStateEmitter.Outcome(ASSET, null,
                LifecycleStateEmitter.ASSET_URI_NOT_CANONICAL)));
        mockMvc.perform(get(ONE).with(user("u").roles("AUDITOR"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ASSET_URI_NOT_CANONICAL")));
    }

    @Test
    void theListUsesTheDefaultSizeAndOneAsOf() throws Exception {
        given(emitter.now()).willReturn(AS_OF);
        given(emitter.page(0, 100, AS_OF)).willReturn(new LifecycleStateEmitter.Page(AS_OF, 0, 100, 2,
                List.of(new LifecycleStateEmitter.Outcome(ASSET, azure(), null),
                        new LifecycleStateEmitter.Outcome(UUID.fromString("00000000-0000-0000-0000-000000000002"), null,
                                LifecycleStateEmitter.ASSET_URI_NOT_CANONICAL))));

        mockMvc.perform(get(LIST).with(user("u").roles("SERVICE_ACCOUNT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specVersion").value("0.1"))
                .andExpect(jsonPath("$.asOf").value("2026-10-31T12:00:00Z"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].asset").value("az://examplestore/archive-2019"))
                .andExpect(jsonPath("$.unrepresentable[0].reason").value("ASSET_URI_NOT_CANONICAL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?size=0", "?size=501", "?page=-1"})
    void aPageOutOfRangeIs400(String query) throws Exception {
        mockMvc.perform(get(LIST + query).with(user("u").roles("ADMIN"))).andExpect(status().isBadRequest());
        verify(emitter, never()).page(anyInt(), anyInt(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER", "AUDITOR", "VIEWER",
            "SERVICE_ACCOUNT"})
    void onlyAdminMayExport(String role) throws Exception {
        mockMvc.perform(get(OKF).with(user("u").roles(role))).andExpect(status().isForbidden());
        verify(emitter, never()).after(any(), anyInt(), any());
    }

    @Test
    void anAdminGetsTheZipOfConceptFiles() throws Exception {
        given(emitter.now()).willReturn(AS_OF);
        given(emitter.after(eq(new UUID(0, 0)), eq(LifecycleStateController.EXPORT_BATCH), eq(AS_OF)))
                .willReturn(List.of(new LifecycleStateEmitter.Outcome(ASSET, azure(), null)));
        given(emitter.after(eq(ASSET), eq(LifecycleStateController.EXPORT_BATCH), eq(AS_OF))).willReturn(List.of());

        MvcResult started = mockMvc.perform(get(OKF).with(user("u").roles("ADMIN")))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult done = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"lifecycle-state-okf-2026-10-31T120000Z.zip\""))
                .andReturn();

        Map<String, String> files = OkfFrontmatterCheck.unzip(done.getResponse().getContentAsByteArray());
        assertThat(files).containsOnlyKeys(
                "lifecycle-state/6fa1a9936837aaaa5c9f0fc1318140a4dbf75bc8ffb97ce244311bf349f32028.md");
        files.forEach(OkfFrontmatterCheck::check);
    }
}
