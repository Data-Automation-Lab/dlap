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

package com.dlap.autodelete.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.autodelete.service.exception.DeletionTaskNotFoundException;

/**
 * Pins the status mapping of {@link GlobalExceptionHandler} through a stub controller, so the
 * contract does not depend on any real endpoint: a client error stays a client error, and only an
 * unexpected failure becomes a 500 with a generic message.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // API clients send Accept: application/json. Without it the standalone setup would pick
        // XML, because an XML converter is on the classpath.
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                .build();
    }

    @Test
    void missingRequestBody_isBadRequest_notServerError() throws Exception {
        mockMvc.perform(put("/probe/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"))
                .andExpect(jsonPath("$.path").value("/probe/body"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void malformedJsonBody_isBadRequest_andDoesNotEchoTheParserMessage() throws Exception {
        mockMvc.perform(put("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"secret-payload-fragment\" "))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("secret-payload-fragment"))));
    }

    @Test
    void exceptionWithResponseStatus_keepsItsDeclaredStatus() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void responseStatusReason_isUsedAsTheMessage() throws Exception {
        mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Task already approved"));
    }

    @Test
    void missingRequestParameter_isBadRequest() throws Exception {
        mockMvc.perform(get("/probe/param"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethod_isMethodNotAllowed_withAllowHeader() throws Exception {
        mockMvc.perform(put("/probe/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    void unsupportedMediaType_isUnsupportedMediaType() throws Exception {
        mockMvc.perform(put("/probe/body").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void illegalArgument_isBadRequest_withItsMessage() throws Exception {
        mockMvc.perform(get("/probe/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("bad input"));
    }

    @Test
    void unexpectedFailure_isServerError_withAGenericMessage() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    /** Stub endpoints, one per failure kind. */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        record Body(String name) { }

        @PutMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@RequestBody Body body) { }

        @GetMapping("/not-found")
        void notFound() {
            throw new DeletionTaskNotFoundException("task-1");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new AlreadyApprovedException();
        }

        @GetMapping("/param")
        void param(@RequestParam String q) { }

        @GetMapping("/illegal")
        void illegal() {
            throw new IllegalArgumentException("bad input");
        }

        @GetMapping("/boom")
        void boom() {
            throw new UnsupportedOperationException("secret internal detail");
        }
    }

    @ResponseStatus(value = HttpStatus.CONFLICT, reason = "Task already approved")
    static class AlreadyApprovedException extends RuntimeException { }
}
