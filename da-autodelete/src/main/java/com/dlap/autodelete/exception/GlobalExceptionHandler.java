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

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler producing a consistent JSON error envelope.
 *
 * <p>Response shape: {@code {timestamp, status, error, message, path}}.
 * Validation failures additionally include a {@code fieldErrors} map.
 *
 * <p>Only exceptions that would otherwise surface as HTTP 500 are mapped here.
 * Exceptions that declare their own client-error status keep it: an exception
 * annotated with {@code @ResponseStatus} (such as
 * {@code DeletionTaskNotFoundException}) and Spring MVC's own
 * {@link ErrorResponse} exceptions (405, 415, missing parameter and so on).
 * A request body that is missing or is not valid JSON is a 400.
 *
 * <p>Logging policy: client-caused errors (4xx) are logged at WARN; server
 * errors and unexpected exceptions at ERROR. Credentials and secrets must
 * never appear in log output.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);


    // ADR-0032: map an @PreAuthorize denial to 403, not the generic 500 below.
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<String> handleAccessDeniedForbidden(
            org.springframework.security.access.AccessDeniedException ex) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body("Access denied");
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fe -> fe.getField(),
                        fe -> fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(),
                        (a, b) -> a
                ));
        log.warn("Validation failure at {}: {}", request.getRequestURI(), fieldErrors);
        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "Validation failed", request);
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        log.warn("Bad request at {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, safeMessage(ex), request);
    }

    /**
     * A request body that is missing, empty or not parseable is a client error (400), not a 500.
     * The parser's message is not echoed, because it can quote part of the payload.
     *
     * @param ex      the body-reading failure
     * @param request the current request
     * @return a 400 response in the standard error envelope
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                    HttpServletRequest request) {
        log.warn("Unreadable request body at {}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
        return buildResponse(HttpStatus.BAD_REQUEST, "Request body is missing or malformed", request);
    }

    /**
     * Catch-all. An exception that declares a client-error status keeps it; anything else is a 500
     * with a generic message, so internal detail never reaches the caller.
     *
     * @param ex      the unhandled exception
     * @param request the current request
     * @return the response in the standard error envelope
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
        HttpStatus declared = declaredClientErrorStatus(ex);
        if (declared != null) {
            log.warn("{} at {}: {}", declared, request.getRequestURI(), ex.getMessage());
            ResponseEntity.BodyBuilder response = ResponseEntity.status(declared);
            if (ex instanceof ErrorResponse errorResponse) {
                // Keeps headers Spring sets for its own exceptions, such as Allow on a 405.
                response.headers(errorResponse.getHeaders());
            }
            return response.body(baseBody(declared, declaredMessage(ex, declared), request));
        }
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    /**
     * The 4xx status the exception declares through {@code @ResponseStatus} or by implementing
     * Spring's {@link ErrorResponse}, or {@code null} when it declares none (or a 5xx one).
     */
    private static HttpStatus declaredClientErrorStatus(Exception ex) {
        HttpStatus status = null;
        if (ex instanceof ErrorResponse errorResponse) {
            status = HttpStatus.resolve(errorResponse.getStatusCode().value());
        } else {
            ResponseStatus annotation = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
            if (annotation != null) {
                status = annotation.code();
            }
        }
        return status != null && status.is4xxClientError() ? status : null;
    }

    private static String declaredMessage(Exception ex, HttpStatus status) {
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getBody().getDetail() != null) {
            return errorResponse.getBody().getDetail();
        }
        ResponseStatus annotation = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        if (annotation != null && !annotation.reason().isBlank()) {
            return annotation.reason();
        }
        return ex.getMessage() == null || ex.getMessage().isBlank() ? status.getReasonPhrase() : ex.getMessage();
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(baseBody(status, message, request));
    }

    private Map<String, Object> baseBody(HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message == null ? "" : message);
        body.put("path", request.getRequestURI());
        return body;
    }

    private String safeMessage(Throwable ex) {
        String msg = ex.getMessage();
        return msg == null ? "" : msg;
    }
}
