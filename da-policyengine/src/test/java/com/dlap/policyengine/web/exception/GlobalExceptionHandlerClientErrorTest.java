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

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins the PE-02 additions to {@link GlobalExceptionHandler} (Q-757, Q-758, D41) at the handler: the status
 * and headers of every Spring {@code ErrorResponse} stand, a declared 5xx of one carries the fixed message,
 * and the live-name constraint is told apart from every other data integrity error.
 */
class GlobalExceptionHandlerClientErrorTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static DataIntegrityViolationException wrapped(Throwable cause) {
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    @Test
    void methodNotSupportedIs405AndKeepsAllow() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "POST")), request("PUT", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(response.getBody().get("status")).isEqualTo(405);
        assertThat(response.getBody().get("message")).isEqualTo("Method 'PUT' is not supported.");
        assertThat(response.getBody().keySet()).containsExactly("timestamp", "status", "error", "message", "path");
    }

    @Test
    void unsupportedMediaTypeIs415() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().get("status")).isEqualTo(415);
    }

    @Test
    void aMissingParameterIs400() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new MissingServletRequestParameterException("size", "int"), request("GET", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Required parameter 'size' is not present.");
    }

    @Test
    void notAcceptableIs406WithNoBody() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), request("GET", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void aResponseStatusExceptionKeepsItsClientStatusAndReason() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "not yours"), request("GET", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("message")).isEqualTo("not yours");
    }

    @Test
    void aResponseStatusExceptionWithoutAReasonUsesTheReasonPhrase() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new ResponseStatusException(HttpStatus.GONE), request("GET", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().get("message")).isEqualTo("Gone");
    }

    @Test
    void aResponseStatusExceptionOf5xxKeepsItsStatusButHidesItsReason() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream db-7.internal refused"),
                request("GET", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().toString()).doesNotContain("db-7");
    }

    @Test
    void anUnreadableBodyIs400AndDoesNotEchoTheParserText() {
        HttpInputMessage input = new MockHttpInputMessage(new byte[0]);
        ResponseEntity<Map<String, Object>> response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("JSON parse error: Cannot deserialize com.dlap.Secret", input),
                request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Malformed request body");
        assertThat(response.getBody().toString()).doesNotContain("com.dlap");
    }

    @Test
    void theLiveNameConstraintNamedByHibernateIs409() {
        ConstraintViolationException cause = new ConstraintViolationException("dup", new SQLException("x", "23505"),
                "insert", "uq_policies_live_name");

        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrity(wrapped(cause),
                request("POST", "/api/v1/policyengine/policies"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("reason")).isEqualTo("name-conflict");
        assertThat(response.getBody().get("status")).isEqualTo(409);
        assertThat(response.getBody().get("error")).isEqualTo("Conflict");
    }

    @Test
    void theLiveNameConstraintInTheDriverTextIs409() {
        SQLException driver = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uq_policies_live_name\"", "23505");

        assertThat(handler.handleDataIntegrity(wrapped(driver), request("POST", "/x")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anotherConstraintNamedByHibernateStaysA500EvenWhenTheTextMentionsTheLiveName() {
        ConstraintViolationException cause = new ConstraintViolationException(
                "violates unique constraint \"uq_policies_live_name\"", new SQLException("x", "23505"), "insert",
                "uq_other");

        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrity(wrapped(cause),
                request("POST", "/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody()).doesNotContainKey("reason");
    }

    @Test
    void aDifferentDriverConstraintStaysA500() {
        SQLException driver = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"policies_pkey\"", "23505");

        assertThat(handler.handleDataIntegrity(wrapped(driver), request("POST", "/x")).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void aDataIntegrityErrorWithNoCauseStaysA500() {
        assertThat(handler.handleDataIntegrity(new DataIntegrityViolationException("x"), request("POST", "/x"))
                .getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
