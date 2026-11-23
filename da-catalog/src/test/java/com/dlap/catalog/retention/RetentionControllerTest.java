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

package com.dlap.catalog.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.catalog.common.ConflictException;
import com.dlap.catalog.common.GlobalExceptionHandler;
import com.dlap.catalog.common.ResourceNotFoundException;
import com.dlap.catalog.config.SecurityConfiguration;
import com.dlap.catalog.retention.RetentionAdminService.Actor;
import com.dlap.common.model.RetentionBasis;

/**
 * {@code @WebMvcTest} slice for {@link RetentionController}: the status codes, the request
 * validation, and that method security is genuinely enforced. Reading is open to every human role
 * of the Access Model and the machine principal; changing a rule or the hold needs one of the four
 * governing roles.
 */
@WebMvcTest(RetentionController.class)
@ContextConfiguration(classes = RetentionControllerTest.TestApp.class)
@Import({SecurityConfiguration.class, GlobalExceptionHandler.class})
class RetentionControllerTest {

    /** Web, security and validation only: no JPA, no Kafka. */
    @SpringBootConfiguration
    @ImportAutoConfiguration({WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, ValidationAutoConfiguration.class, SecurityAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class})
    @ComponentScan(basePackageClasses = RetentionController.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = RetentionController.class))
    static class TestApp {
    }

    private static final UUID ASSET = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID RULE = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    private static final String BASE = "/api/v1/catalog/assets/" + ASSET;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RetentionAdminService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static AssetRetentionView view() {
        return new AssetRetentionView(ASSET, "gs://b", "DUE", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-10-20T12:00:00Z"), 365, "CREATED", "MANUAL", null, false,
                List.of(new RetentionRuleView(RULE, "MANUAL", "CREATED", 365, null, null, null, "policy", null,
                        "alice")));
    }

    @Test
    void getReturnsTheStateAndTheRules() throws Exception {
        given(service.view(ASSET)).willReturn(view());

        mockMvc.perform(get(BASE + "/retention").with(user("a").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionStatus").value("DUE"))
                .andExpect(jsonPath("$.retentionExpiresAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.retentionSource").value("MANUAL"))
                .andExpect(jsonPath("$.legalHold").value(false))
                .andExpect(jsonPath("$.rules[0].ruleId").value(RULE.toString()))
                .andExpect(jsonPath("$.rules[0].createdBy").value("alice"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER", "DATA_SCIENTIST",
            "DATA_ANALYST", "AUDITOR", "VIEWER", "SERVICE_ACCOUNT"})
    void everyHumanRoleAndTheMachinePrincipalMayRead(String role) throws Exception {
        given(service.view(ASSET)).willReturn(view());

        mockMvc.perform(get(BASE + "/retention").with(user("u").roles(role))).andExpect(status().isOk());
    }

    @Test
    void aTokenWithNoDlapRoleCannotRead() throws Exception {
        mockMvc.perform(get(BASE + "/retention").with(user("u").roles("GUEST"))).andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/retention")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/legal-hold").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownAssetIs404WithTheErrorEnvelope() throws Exception {
        given(service.view(ASSET)).willThrow(new ResourceNotFoundException("Asset", "id", ASSET.toString()));

        mockMvc.perform(get(BASE + "/retention").with(user("u").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Asset not found with id: " + ASSET));
    }

    @Test
    void addingARuleReturns201WithItsLocationAndPassesTheOperatorToTheService() throws Exception {
        RetentionRuleView created = new RetentionRuleView(RULE, "MANUAL", "LAST_ACCESSED", 90, null, null, null,
                "quarterly review", Instant.parse("2026-10-20T12:00:00Z"), "alice");
        given(service.addManualRule(eq(ASSET), any(), any())).willReturn(created);

        mockMvc.perform(post(BASE + "/retention/rules").with(user("alice").roles("POLICY_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"LAST_ACCESSED\",\"days\":90,\"reason\":\"quarterly review\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", BASE + "/retention/rules/" + RULE))
                .andExpect(jsonPath("$.ruleId").value(RULE.toString()))
                .andExpect(jsonPath("$.days").value(90));

        ArgumentCaptor<RetentionRuleRequest> request = ArgumentCaptor.forClass(RetentionRuleRequest.class);
        ArgumentCaptor<Actor> actor = ArgumentCaptor.forClass(Actor.class);
        verify(service).addManualRule(eq(ASSET), request.capture(), actor.capture());
        assertThat(request.getValue()).isEqualTo(new RetentionRuleRequest(RetentionBasis.LAST_ACCESSED, 90, null,
                "quarterly review"));
        assertThat(actor.getValue().name()).isEqualTo("alice");
    }

    @Test
    void aFixedDateRuleCarriesItsExpiry() throws Exception {
        given(service.addManualRule(eq(ASSET), any(), any())).willReturn(new RetentionRuleView(RULE, "MANUAL",
                "FIXED_DATE", null, Instant.parse("2027-12-31T00:00:00Z"), null, null, null, null, "a"));

        mockMvc.perform(post(BASE + "/retention/rules").with(user("a").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"FIXED_DATE\",\"expiresAt\":\"2027-12-31T00:00:00Z\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<RetentionRuleRequest> request = ArgumentCaptor.forClass(RetentionRuleRequest.class);
        verify(service).addManualRule(eq(ASSET), request.capture(), any());
        assertThat(request.getValue().expiresAt()).isEqualTo(Instant.parse("2027-12-31T00:00:00Z"));
    }

    @Test
    void aRuleWithoutABasisOrWithAnUnknownBasisOrAMalformedBodyIs400AndNeverReachesTheService() throws Exception {
        for (String body : new String[] {"{}", "{\"basis\":\"SOMETIME\",\"days\":1}", "{\"basis\":", "[]",
                "{\"basis\":\"CREATED\",\"days\":1,\"reason\":\"" + "x".repeat(1001) + "\"}"}) {
            mockMvc.perform(post(BASE + "/retention/rules").with(user("a").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is4xxClientError());
        }
        verify(service, never()).addManualRule(any(), any(), any());
    }

    @Test
    void aRuleTheServiceRejectsIs400() throws Exception {
        given(service.addManualRule(eq(ASSET), any(), any()))
                .willThrow(new IllegalArgumentException("days must be between 0 and 365000 for basis CREATED"));

        mockMvc.perform(post(BASE + "/retention/rules").with(user("a").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CREATED\",\"days\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("days must be between 0 and 365000 for basis CREATED"));
    }

    @Test
    void tooManyManualRulesIs409() throws Exception {
        given(service.addManualRule(eq(ASSET), any(), any())).willThrow(new ConflictException("too many"));

        mockMvc.perform(post(BASE + "/retention/rules").with(user("a").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CREATED\",\"days\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void removingARuleIs204AndAMissingRuleIs404() throws Exception {
        mockMvc.perform(delete(BASE + "/retention/rules/" + RULE).with(user("a").roles("DIRECTOR")))
                .andExpect(status().isNoContent());
        verify(service).removeManualRule(eq(ASSET), eq(RULE), any());

        org.mockito.BDDMockito.willThrow(new ResourceNotFoundException("Manual retention rule", "id", RULE.toString()))
                .given(service).removeManualRule(eq(ASSET), eq(RULE), any());
        mockMvc.perform(delete(BASE + "/retention/rules/" + RULE).with(user("a").roles("DIRECTOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void placingAndReleasingTheHoldReturnTheStateAndNeedAReason() throws Exception {
        given(service.placeLegalHold(eq(ASSET), eq("litigation 42"), any())).willReturn(view());
        given(service.releaseLegalHold(eq(ASSET), eq("case closed"), any())).willReturn(view());

        mockMvc.perform(put(BASE + "/legal-hold").with(user("a").roles("CDO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"litigation 42\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.retentionStatus").value("DUE"));
        mockMvc.perform(post(BASE + "/legal-hold/release").with(user("a").roles("CDO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"case closed\"}"))
                .andExpect(status().isOk());

        for (String body : new String[] {"{}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}"}) {
            mockMvc.perform(put(BASE + "/legal-hold").with(user("a").roles("CDO"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post(BASE + "/legal-hold/release").with(user("a").roles("CDO"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATA_ENGINEER", "DATA_SCIENTIST", "DATA_ANALYST", "AUDITOR", "VIEWER", "SERVICE_ACCOUNT"})
    void onlyTheFourGoverningRolesMayChangeRulesOrTheHold(String role) throws Exception {
        mockMvc.perform(post(BASE + "/retention/rules").with(user("u").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CREATED\",\"days\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/retention/rules/" + RULE).with(user("u").roles(role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(BASE + "/legal-hold").with(user("u").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/legal-hold/release").with(user("u").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).addManualRule(any(), any(), any());
        verify(service, never()).placeLegalHold(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CDO", "DIRECTOR", "POLICY_MANAGER"})
    void theFourGoverningRolesMayChangeRules(String role) throws Exception {
        given(service.addManualRule(eq(ASSET), any(), any())).willReturn(new RetentionRuleView(RULE, "MANUAL",
                "CREATED", 1, null, null, null, null, null, "u"));

        mockMvc.perform(post(BASE + "/retention/rules").with(user("u").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CREATED\",\"days\":1}"))
                .andExpect(status().isCreated());
    }
}
