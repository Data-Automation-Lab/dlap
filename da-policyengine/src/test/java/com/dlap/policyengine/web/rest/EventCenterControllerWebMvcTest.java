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

package com.dlap.policyengine.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dlap.policyengine.config.SecurityConfiguration;
import com.dlap.policyengine.dto.EventSubscriptionInputDTO;
import com.dlap.policyengine.dto.EventSubscriptionOutputDTO;
import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventType;
import com.dlap.policyengine.service.IEventSubscriptionService;

/**
 * Web-layer contract for the formerly MVEL-bearing parts of the Event Center API.
 *
 * <p>SEC-04 (ADR-0057): subscription rule conditions were MVEL, which is executable code,
 * and are retired. Any body that still carries a rule gets HTTP 400 for every role, ROLE_ADMIN
 * included, and never reaches the service; bean validation answers before the role check.
 * Subscriptions use typed filters instead and stay open to the subscriber roles. The former
 * {@code POST /events/test-rule} endpoint no longer exists (HTTP 404, SEC-01).
 */
@WebMvcTest(controllers = EventCenterController.class)
@Import(SecurityConfiguration.class)
class EventCenterControllerWebMvcTest {

    private static final String BASE = "/api/v1/policyengine/events";
    private static final String SUBSCRIPTIONS = BASE + "/subscriptions";
    private static final String VALIDATE = BASE + "/config/validate";

    private static final String BODY_NO_RULES =
            "{\"name\":\"s1\",\"notificationConfig\":{\"email\":true}}";
    private static final String BODY_NULL_RULES =
            "{\"name\":\"s1\",\"notificationConfig\":{\"email\":true},\"rules\":null}";
    private static final String BODY_EMPTY_RULES =
            "{\"name\":\"s1\",\"notificationConfig\":{\"email\":true},\"rules\":[]}";
    private static final String BODY_WITH_RULE =
            "{\"name\":\"s1\",\"notificationConfig\":{\"email\":true},"
            + "\"rules\":[{\"name\":\"r1\",\"condition\":\"severity == 'HIGH'\"}]}";
    /** A condition that would execute code if it were ever compiled and run. */
    private static final String BODY_WITH_CODE_RULE =
            "{\"name\":\"s1\",\"notificationConfig\":{\"email\":true},"
            + "\"rules\":[{\"name\":\"r1\",\"condition\":\"java.lang.Runtime.getRuntime().exec('x')\"}]}";
    /** The typed filter that replaced rule conditions. */
    private static final String BODY_TYPED_FILTER =
            "{\"name\":\"s1\",\"notificationConfig\":{\"email\":true},"
            + "\"eventTypes\":[\"ASSET_DISCOVERED\"],\"severities\":[\"HIGH\"],"
            + "\"sourceServices\":[\"da-catalog\"],\"assetPrefixes\":[\"s3://finance/\"],"
            + "\"labels\":[\"PII\"]}";

    private static final String[] EVERY_ROLE = {"ROLE_ADMIN", "ROLE_POLICY_MANAGER", "ROLE_DATA_ENGINEER",
        "ROLE_DATA_SCIENTIST", "ROLE_DATA_ANALYST", "ROLE_VIEWER", "ROLE_AUDITOR"};

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IEventSubscriptionService eventSubscriptionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String authority) {
        return user("tester").authorities(new SimpleGrantedAuthority(authority));
    }

    private static EventSubscriptionOutputDTO savedSubscription() {
        EventSubscriptionOutputDTO out = new EventSubscriptionOutputDTO();
        out.setId(UUID.randomUUID());
        out.setName("s1");
        return out;
    }

    // ----- POST /events/test-rule is gone -------------------------------------------------

    /**
     * No handler maps the former test-rule path and the service no longer has a method
     * that could run a caller-supplied condition.
     */
    @Test
    void testRuleEndpoint_hasNoHandlerAndNoServiceMethod() {
        for (java.lang.reflect.Method m : EventCenterController.class.getDeclaredMethods()) {
            assertThat(m.getName()).as("controller handler %s", m).doesNotContain("testEventRule");
        }
        assertThat(IEventSubscriptionService.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("testEventRule");
    }

    /**
     * A request to the former path answers 404 for every role and never reaches the service.
     *
     * <p>The framework raises NoResourceFoundException for an unmapped path;
     * {@code web.exception.GlobalExceptionHandler} maps it to 404.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_POLICY_MANAGER", "ROLE_DATA_ENGINEER",
            "ROLE_DATA_SCIENTIST", "ROLE_DATA_ANALYST"})
    void testRuleEndpoint_isGone_isRefusedAndReachesNoService(String role) throws Exception {
        mockMvc.perform(post(BASE + "/test-rule").with(as(role)).with(csrf())
                        .param("ruleCondition", "java.lang.System.exit(1)")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"POLICY_VIOLATION\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(eventSubscriptionService);
    }

    // ----- POST /events/subscriptions -----------------------------------------------------

    @Test
    void createSubscription_withRules_isBadRequestForEveryRoleAndReachesNoService() throws Exception {
        for (String role : EVERY_ROLE) {
            for (String body : new String[] {BODY_WITH_RULE, BODY_WITH_CODE_RULE}) {
                mockMvc.perform(post(SUBSCRIPTIONS).with(as(role)).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(eventSubscriptionService);
    }

    @Test
    void createSubscription_withTypedFilter_passesEveryFieldToTheService() throws Exception {
        when(eventSubscriptionService.createSubscription(any(EventSubscriptionInputDTO.class), any()))
                .thenReturn(savedSubscription());

        mockMvc.perform(post(SUBSCRIPTIONS).with(as("ROLE_DATA_ANALYST")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_TYPED_FILTER))
                .andExpect(status().isCreated());

        ArgumentCaptor<EventSubscriptionInputDTO> sent = ArgumentCaptor.forClass(EventSubscriptionInputDTO.class);
        verify(eventSubscriptionService).createSubscription(sent.capture(), any());
        assertThat(sent.getValue().getEventTypes()).containsExactly(EventType.ASSET_DISCOVERED);
        assertThat(sent.getValue().getSeverities()).containsExactly(EventSeverity.HIGH);
        assertThat(sent.getValue().getSourceServices()).containsExactly("da-catalog");
        assertThat(sent.getValue().getAssetPrefixes()).containsExactly("s3://finance/");
        assertThat(sent.getValue().getLabels()).containsExactly("PII");
    }

    @Test
    void createSubscription_withAnUnknownEventType_isRefusedAndReachesNoService() throws Exception {
        mockMvc.perform(post(SUBSCRIPTIONS).with(as("ROLE_DATA_ANALYST")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"s1\",\"notificationConfig\":{\"email\":true},"
                                + "\"eventTypes\":[\"NOT_A_TYPE\"]}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(201));
        verifyNoInteractions(eventSubscriptionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_POLICY_MANAGER", "ROLE_DATA_ENGINEER", "ROLE_DATA_SCIENTIST", "ROLE_DATA_ANALYST"})
    void createSubscription_withoutRules_bySubscriberRoles_isCreated(String role) throws Exception {
        when(eventSubscriptionService.createSubscription(any(EventSubscriptionInputDTO.class), any()))
                .thenReturn(savedSubscription());

        for (String body : new String[] {BODY_NO_RULES, BODY_NULL_RULES, BODY_EMPTY_RULES}) {
            mockMvc.perform(post(SUBSCRIPTIONS).with(as(role)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_DIRECTOR", "ROLE_SERVICE_ACCOUNT", "ROLE_USER"})
    void createSubscription_byRoleOutsideSubscriberSet_isForbidden(String role) throws Exception {
        for (String body : new String[] {BODY_NO_RULES, BODY_TYPED_FILTER}) {
            mockMvc.perform(post(SUBSCRIPTIONS).with(as(role)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        verify(eventSubscriptionService, never()).createSubscription(any(), any());
    }

    @Test
    void createSubscription_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(post(SUBSCRIPTIONS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_WITH_RULE))
                .andExpect(status().isUnauthorized());
    }

    // ----- PUT /events/subscriptions/{id} -------------------------------------------------

    @Test
    void updateSubscription_withRules_isBadRequestForEveryRoleAndReachesNoService() throws Exception {
        for (String role : EVERY_ROLE) {
            mockMvc.perform(put(SUBSCRIPTIONS + "/{id}", UUID.randomUUID()).with(as(role)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(BODY_WITH_CODE_RULE))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(eventSubscriptionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_POLICY_MANAGER", "ROLE_DATA_ENGINEER", "ROLE_DATA_SCIENTIST", "ROLE_DATA_ANALYST"})
    void updateSubscription_withoutRules_byNonAdminSubscriber_isOk(String role) throws Exception {
        UUID id = UUID.randomUUID();
        when(eventSubscriptionService.updateSubscription(eq(id), any(EventSubscriptionInputDTO.class), any()))
                .thenReturn(savedSubscription());

        for (String body : new String[] {BODY_NO_RULES, BODY_NULL_RULES, BODY_EMPTY_RULES}) {
            mockMvc.perform(put(SUBSCRIPTIONS + "/{id}", id).with(as(role)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
    }

    // ----- POST /events/config/validate ---------------------------------------------------

    @Test
    void validateConfiguration_withRules_isBadRequestForEveryRoleAndNothingIsCompiled() throws Exception {
        for (String role : EVERY_ROLE) {
            mockMvc.perform(post(VALIDATE).with(as(role)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(BODY_WITH_CODE_RULE))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(eventSubscriptionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_POLICY_MANAGER", "ROLE_DATA_ENGINEER", "ROLE_DATA_SCIENTIST", "ROLE_DATA_ANALYST"})
    void validateConfiguration_withoutRules_byNonAdminSubscriber_isOk(String role) throws Exception {
        mockMvc.perform(post(VALIDATE).with(as(role)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_NO_RULES))
                .andExpect(status().isOk());

        verify(eventSubscriptionService).validateSubscriptionConfiguration(any(EventSubscriptionInputDTO.class));
    }

    @Test
    void validateConfiguration_structuralFailure_isBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Subscription name is required"))
                .when(eventSubscriptionService).validateSubscriptionConfiguration(any());

        mockMvc.perform(post(VALIDATE).with(as("ROLE_DATA_ANALYST")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_NO_RULES))
                .andExpect(status().isBadRequest());
    }
}
