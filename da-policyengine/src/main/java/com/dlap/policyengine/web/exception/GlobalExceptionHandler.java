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
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
 * <p>This is the one central mapping of the policy engine (PE-01, Q-715, D39). An exception class that
 * declares its status with {@code @ResponseStatus} (such as {@code ResourceNotFoundException}, 404, and
 * {@code ConflictException}, 409) answers that status for every controller: this advice's catch-all
 * handler runs before Spring's own {@code ResponseStatusExceptionResolver}, so the status has to be applied
 * here. A 4xx answer carries the annotation's reason, else the exception's message; a declared 5xx answer
 * carries a fixed message. {@code AuditUnavailableException} answers 503. Every other unexpected exception
 * answers 500 with a fixed message: the body never holds a stack trace, a class name or the exception's text.
 *
 * <p>PE-02 (Q-757, Q-758, D41): Spring MVC's own client errors keep their status instead of falling into
 * the catch-all. Every exception that implements {@link ErrorResponse} (a method that is not supported,
 * a missing parameter, an unsupported media type, {@code ResponseStatusException}) answers its own status
 * and headers (such as {@code Allow} on a 405); a malformed body and a path or query value of the wrong
 * type answer 400 with a message that does not echo the parser's text. A
 * {@code DataIntegrityViolationException} on the {@code uq_policies_live_name} constraint, and only that
 * constraint, answers 409 with {@code reason=name-conflict}: it is the race of two creates that both
 * passed the name check. Any other data integrity error stays 500.
 *
 * <p>Logging policy: client-caused errors (4xx) are logged at WARN; server
 * errors and unexpected exceptions at ERROR. Credentials and secrets must
 * never appear in log output.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The {@code reason} of a 503 for a change the audit log could not record (Q-692). */
    public static final String AUDIT_UNAVAILABLE = "audit-unavailable";

    /** The fixed message of every 500, and of a declared 5xx: no internal detail reaches the body. */
    public static final String UNEXPECTED_ERROR = "An unexpected error occurred";

    /** The {@code reason} of the 409 for the same-name create race (Q-757). */
    public static final String NAME_CONFLICT = "name-conflict";

    /** The message of a 400 for a body that cannot be parsed; the parser's text is never echoed. */
    public static final String MALFORMED_BODY = "Malformed request body";

    /** The partial unique index of Flyway V9 on the name of a live policy. */
    static final String LIVE_NAME_CONSTRAINT = "uq_policies_live_name";

    private static final String UNIQUE_VIOLATION = "violates unique constraint ";

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
        ResponseEntity<Map<String, Object>> declared = declaredStatus(ex, request);
        if (declared != null) {
            return declared;
        }
        log.warn("Bad request at {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, safeMessage(ex), request);
    }

    // Without this, an @PreAuthorize denial would be swallowed by the generic
    // Exception handler below and surface as HTTP 500 instead of 403.
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied at {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "Access denied", request);
    }

    // Spring 6.1 raises NoResourceFoundException (or NoHandlerFoundException) for a path
    // that no controller maps. Without this, the generic Exception handler below would
    // turn an unknown or removed endpoint into HTTP 500 plus an ERROR log line.
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex, HttpServletRequest request) {
        log.warn("No handler for {} {}", request.getMethod(), request.getRequestURI());
        return buildResponse(HttpStatus.NOT_FOUND, "Not found", request);
    }

    /**
     * A governance change (a policy create, update, status change or delete, a draft publish, an approval
     * decision) the audit log could not record was rolled back: 503 {@code audit-unavailable} for every
     * controller, never 400 or 500 (AUD-02a, D38, Q-692). Without this the catch-all handler below would
     * answer 500 for any controller that does not map the exception itself, such as the draft publish.
     * The cause's message stays in the log; the body names no driver detail.
     *
     * @param ex      the failure
     * @param request the request
     * @return 503 with {@code reason=audit-unavailable}
     */
    @ExceptionHandler(AuditUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAuditUnavailable(AuditUnavailableException ex,
            HttpServletRequest request) {
        log.error("Change at {} was not made: the audit log could not record it ({})",
                request.getRequestURI(), ex.getMessage());
        Map<String, Object> body = baseBody(HttpStatus.SERVICE_UNAVAILABLE,
                "the audit log could not record the change; nothing was changed", request);
        body.put("reason", AUDIT_UNAVAILABLE);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * A body that cannot be parsed (malformed JSON, an unknown enum value, a wrong field type, no body) is a
     * client error: 400 with a fixed message. The parser's own text names classes and fields of the service,
     * so it stays in the log.
     *
     * @param ex      the failure
     * @param request the request
     * @return 400 {@code Malformed request body}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        log.warn("Unreadable request body at {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, MALFORMED_BODY, request);
    }

    /**
     * A path variable or request parameter that does not convert to its declared type (a non-UUID id) is a
     * client error: 400 naming the parameter, never the offending value.
     *
     * @param ex      the failure
     * @param request the request
     * @return 400 {@code Invalid value for parameter '<name>'}
     */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(TypeMismatchException ex,
            HttpServletRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : ex.getPropertyName();
        log.warn("Type mismatch at {} for parameter {}", request.getRequestURI(), name);
        String message = name == null || name.isBlank() ? "Invalid parameter value"
                : "Invalid value for parameter '" + name + "'";
        return buildResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * The create race (Q-757, D41): two concurrent creates of a live policy with the same name both passed
     * the name check and the loser hit the partial unique index {@code uq_policies_live_name}. That one
     * constraint answers 409 {@code name-conflict}, the answer the sequential case already gives. Every
     * other data integrity error is a server fault and answers 500 with the fixed message.
     *
     * @param ex      the failure
     * @param request the request
     * @return 409 for the live-name constraint, else 500
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex,
            HttpServletRequest request) {
        if (violatesLiveName(ex)) {
            log.warn("Name conflict at {}: a concurrent create took the name ({})", request.getRequestURI(),
                    ex.getMostSpecificCause().getMessage());
            Map<String, Object> body = baseBody(HttpStatus.CONFLICT,
                    "A policy with this name already exists", request);
            body.put("reason", NAME_CONFLICT);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        log.error("Data integrity error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            return errorResponse(errorResponse, ex, request);
        }
        ResponseEntity<Map<String, Object>> declared = declaredStatus(ex, request);
        if (declared != null) {
            return declared;
        }
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR, request);
    }

    /**
     * Honours Spring's {@link ErrorResponse} (Q-758): its status and headers stand. A 4xx answer carries
     * Spring's own detail text (for example {@code Method 'PATCH' is not supported.}); a 5xx answer carries
     * the fixed message and the cause stays in the log. A 406 is answered without a body because the client
     * said it cannot read the JSON one.
     *
     * @param errorResponse the Spring exception
     * @param ex            the same exception as a Throwable
     * @param request       the request
     * @return the answer
     */
    private ResponseEntity<Map<String, Object>> errorResponse(ErrorResponse errorResponse, Exception ex,
            HttpServletRequest request) {
        HttpStatusCode code = errorResponse.getStatusCode();
        HttpStatus status = HttpStatus.resolve(code.value());
        if (status == null) {
            status = code.is4xxClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            log.warn("Not acceptable at {}", request.getRequestURI());
            return ResponseEntity.status(status).headers(errorResponse.getHeaders()).build();
        }
        if (status.is4xxClientError()) {
            String detail = errorResponse.getBody().getDetail();
            log.warn("{} at {}: {}", status.getReasonPhrase(), request.getRequestURI(), detail);
            return ResponseEntity.status(status).headers(errorResponse.getHeaders())
                    .body(baseBody(status, detail == null || detail.isBlank() ? status.getReasonPhrase() : detail,
                            request));
        }
        log.error("Unhandled exception at {} (status {}): {}", request.getRequestURI(), status.value(),
                ex.getMessage(), ex);
        return ResponseEntity.status(status).headers(errorResponse.getHeaders())
                .body(baseBody(status, UNEXPECTED_ERROR, request));
    }

    /**
     * Whether a data integrity failure is the live-name unique index and nothing else. The constraint name
     * Hibernate extracted from the driver decides when it is present; otherwise the driver's own text must
     * say {@code violates unique constraint "uq_policies_live_name"}.
     */
    private static boolean violatesLiveName(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 16; t = t.getCause(), depth++) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return LIVE_NAME_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName());
            }
        }
        depth = 0;
        for (Throwable t = failure; t != null && depth < 16; t = t.getCause(), depth++) {
            String message = t.getMessage();
            if (message != null && message.contains(UNIQUE_VIOLATION + "\"" + LIVE_NAME_CONSTRAINT + "\"")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies the status an exception class declares with {@code @ResponseStatus}, on the class or on a
     * superclass. A 4xx answer carries the annotation's reason, else the exception's message, and is logged
     * at WARN. Any other declared status carries the fixed {@link #UNEXPECTED_ERROR} message and is logged
     * at ERROR with the stack trace in the log only.
     *
     * @param ex      the failure
     * @param request the request
     * @return the answer, or null when the exception class declares no status
     */
    private ResponseEntity<Map<String, Object>> declaredStatus(Exception ex, HttpServletRequest request) {
        ResponseStatus declared = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        if (declared == null) {
            return null;
        }
        HttpStatus status = declared.code();
        if (status.is4xxClientError()) {
            String message = declared.reason().isEmpty() ? safeMessage(ex) : declared.reason();
            log.warn("{} at {}: {}", status.getReasonPhrase(), request.getRequestURI(), ex.getMessage());
            return buildResponse(status, message, request);
        }
        log.error("Unhandled exception at {} (declared status {}): {}", request.getRequestURI(),
                status.value(), ex.getMessage(), ex);
        return buildResponse(status, UNEXPECTED_ERROR, request);
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
