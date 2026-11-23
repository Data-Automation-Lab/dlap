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

package com.dlap.policyengine.web.exception;

import com.dlap.policyengine.approval.AuditUnavailableException;
import com.dlap.policyengine.common.ConflictException;
import com.dlap.policyengine.common.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the status mapping of {@link GlobalExceptionHandler}: an unmapped or removed
 * path answers 404 (not 500), while the other mappings stay as they were.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    @Test
    void noResourceFound_isNotFound() {
        MockHttpServletRequest req = request("POST", "/api/v1/policyengine/events/test-rule");

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(
                new NoResourceFoundException(HttpMethod.POST, "/api/v1/policyengine/events/test-rule",
                        "api/v1/policyengine/events/test-rule"), req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(404);
        assertThat(body.get("error")).isEqualTo("Not Found");
        assertThat(body.get("message")).isEqualTo("Not found");
        assertThat(body.get("path")).isEqualTo("/api/v1/policyengine/events/test-rule");
    }

    @Test
    void noHandlerFound_isNotFound() {
        MockHttpServletRequest req = request("GET", "/api/v1/policyengine/nothing");

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/api/v1/policyengine/nothing", HttpHeaders.EMPTY), req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void notFoundBody_doesNotEchoTheExceptionMessage() {
        MockHttpServletRequest req = request("GET", "/x");

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(
                new NoResourceFoundException(HttpMethod.GET, "/secret-looking/path", "secret-looking/path"), req);

        assertThat(String.valueOf(response.getBody().get("message"))).doesNotContain("secret-looking");
    }

    @Test
    void accessDenied_staysForbidden() {
        ResponseEntity<Map<String, Object>> response = handler.handleAccessDenied(
                new AccessDeniedException("no"), request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unexpectedException_staysInternalServerError() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new RuntimeException("boom"), request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
    }

    @Test
    void illegalArgument_staysBadRequest() {
        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(
                new IllegalArgumentException("bad"), request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ----- PE-01 (Q-715, D39): the declared status of an exception class ---------------------

    @ResponseStatus(HttpStatus.GONE)
    private static class GoneException extends RuntimeException {
        GoneException(String message) {
            super(message);
        }
    }

    @ResponseStatus(value = HttpStatus.UNPROCESSABLE_ENTITY, reason = "the declared reason")
    private static class ReasonedException extends RuntimeException {
        ReasonedException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    private static class UpstreamException extends RuntimeException {
        UpstreamException(String message) {
            super(message);
        }
    }

    /** A declared status on a subclass of IllegalStateException must beat the bad-request mapping. */
    @ResponseStatus(HttpStatus.CONFLICT)
    private static class StateConflictException extends IllegalStateException {
        StateConflictException(String message) {
            super(message);
        }
    }

    private static class SubOfNotFound extends ResourceNotFoundException {
        SubOfNotFound(String message) {
            super(message);
        }
    }

    @Test
    void resourceNotFound_isNotFoundWithItsOwnMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new ResourceNotFoundException("Policy", "id", "p-1"), request("GET", "/api/v1/policyengine/policies/p-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(404);
        assertThat(body.get("error")).isEqualTo("Not Found");
        assertThat(body.get("message")).isEqualTo("Policy not found with id : 'p-1'");
        assertThat(body.get("path")).isEqualTo("/api/v1/policyengine/policies/p-1");
        assertThat(body).containsKey("timestamp");
    }

    @Test
    void conflict_isConflictWithItsOwnMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new ConflictException("Policy with name 'x' already exists."), request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("status")).isEqualTo(409);
        assertThat(response.getBody().get("error")).isEqualTo("Conflict");
        assertThat(response.getBody().get("message")).isEqualTo("Policy with name 'x' already exists.");
    }

    @Test
    void anyExceptionClassThatDeclaresAStatusKeepsIt() {
        assertThat(handler.handleGeneric(new GoneException("gone"), request("GET", "/x")).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
        assertThat(handler.handleGeneric(new SubOfNotFound("sub"), request("GET", "/x")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theAnnotationsReasonWinsOverTheExceptionMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new ReasonedException("internal text"), request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("message")).isEqualTo("the declared reason");
    }

    @Test
    void aDeclaredServerErrorAnswersItsStatusWithAFixedMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new UpstreamException("host db-7.internal refused"), request("GET", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().toString()).doesNotContain("db-7");
    }

    @Test
    void aDeclaredStatusBeatsTheBadRequestMappingOfItsSuperclass() {
        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(
                new StateConflictException("busy"), request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("message")).isEqualTo("busy");
    }

    @Test
    void auditUnavailable_staysServiceUnavailable() {
        ResponseEntity<Map<String, Object>> response = handler.handleAuditUnavailable(
                new AuditUnavailableException("append failed: connection refused", null), request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("reason")).isEqualTo("audit-unavailable");
        assertThat(response.getBody().toString()).doesNotContain("connection refused");
    }

    @Test
    void unexpectedException_bodyHasNoStackTraceNoClassNameAndNoMessage() {
        RuntimeException failure = new IllegalFormatBoom("jdbc:postgresql://db:5432 password=s3cr3t");

        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(failure, request("GET", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = response.getBody();
        assertThat(body.keySet()).containsExactly("timestamp", "status", "error", "message", "path");
        assertThat(body.toString()).doesNotContain("s3cr3t").doesNotContain("IllegalFormatBoom")
                .doesNotContain("com.dlap").doesNotContain("at ");
    }

    private static class IllegalFormatBoom extends RuntimeException {
        IllegalFormatBoom(String message) {
            super(message);
        }
    }
}
