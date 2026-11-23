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

package com.dlap.discovery.common.exception;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.dlap.discovery.common.service.ErrorResponse;
import com.dlap.discovery.crawler.exception.ErrorCode;
import com.dlap.discovery.event.ExceptionMetricsCollector;

/**
 * Global exception handler for REST controllers.
 * Converts exceptions to standardized error responses with appropriate HTTP
 * status codes.
 */
@ControllerAdvice
public class DiscoveryExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(DiscoveryExceptionHandler.class);

	@Autowired
	private ExceptionMetricsCollector metricsCollector;

	/**
	 * Handles DiscoveryException and its subclasses.
	 * 
	 * @param ex      The exception to handle
	 * @param request The current web request
	 * @return A ResponseEntity with an ErrorResponse
	 */

    // ADR-0032: map an @PreAuthorize denial to 403, not the generic 500 below.
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<String> handleAccessDeniedForbidden(
            org.springframework.security.access.AccessDeniedException ex) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body("Access denied");
    }
	@ExceptionHandler(DiscoveryException.class)
	public ResponseEntity<ErrorResponse> handleDiscoveryException(DiscoveryException ex, WebRequest request) {
		log.debug("Handling DiscoveryException: {}", ex.getMessage());

		// Record metrics for the exception
		if (metricsCollector != null) {
			metricsCollector.recordException(ex);
		}

		ErrorResponse errorResponse = new ErrorResponse(ex);
		HttpStatus status = determineHttpStatus(ex.getErrorCode());

		return new ResponseEntity<>(errorResponse, status);
	}

	/**
	 * Properly overrides the parent method to handle validation exceptions (e.g.,
	 * from @Valid annotations).
	 * 
	 * @param ex      The exception to handle
	 * @param headers The headers for the response
	 * @param status  The status code
	 * @param request The current web request
	 * @return A ResponseEntity with an ErrorResponse
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException ex,
			HttpHeaders headers,
			HttpStatusCode status,
			WebRequest request) {

		// Record metrics
		if (metricsCollector != null) {
			metricsCollector.recordException(ex);
		}

		Map<String, Object> errors = new HashMap<>();
		ex.getBindingResult().getAllErrors().forEach(error -> {
			String fieldName = ((FieldError) error).getField();
			String errorMessage = error.getDefaultMessage();
			errors.put(fieldName, errorMessage);
		});

		ErrorResponse errorResponse = new ErrorResponse(
				ErrorCode.DATA_VALIDATION_ERROR.getCode(),
				"Validation failed",
				UUID.randomUUID().toString(),
				errors,
				java.time.LocalDateTime.now());

		return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
	}

	/**
	 * Handles general exceptions not caught by other handlers.
	 * 
	 * @param ex      The exception to handle
	 * @param request The current web request
	 * @return A ResponseEntity with an ErrorResponse
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
		// Log the full exception for unexpected errors
		log.error("Unhandled exception occurred", ex);

		// Record metrics for generic exceptions
		if (metricsCollector != null) {
			metricsCollector.recordException(ex);
		}

		String errorId = UUID.randomUUID().toString();

		// Create a sanitized error response (don't expose internal details)
		ErrorResponse errorResponse = new ErrorResponse(
				ErrorCode.UNEXPECTED_ERROR.getCode(),
				"An unexpected error occurred. Please contact support with this error ID: " + errorId,
				errorId,
				Map.of("path", request.getDescription(false)),
				java.time.LocalDateTime.now());

		return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * Determines the appropriate HTTP status code based on the error code.
	 * 
	 * @param errorCode The error code
	 * @return The corresponding HTTP status
	 */
	private HttpStatus determineHttpStatus(ErrorCode errorCode) {
		int code = errorCode.getCode();

		// Map error code ranges to HTTP status codes
		if (code >= 1000 && code < 2000) {
			// Resource errors
			return code == ErrorCode.RESOURCE_NOT_FOUND.getCode()
					? HttpStatus.NOT_FOUND
					: HttpStatus.BAD_REQUEST;
		} else if (code >= 2000 && code < 3000) {
			// Authentication/Authorization errors
			return code == ErrorCode.AUTHENTICATION_FAILED.getCode()
					? HttpStatus.UNAUTHORIZED
					: HttpStatus.FORBIDDEN;
		} else if (code >= 3000 && code < 4000) {
			// Provider errors
			if (code == ErrorCode.PROVIDER_RESOURCE_NOT_FOUND.getCode()) {
				return HttpStatus.NOT_FOUND;
			} else if (code == ErrorCode.PROVIDER_RATE_LIMITED.getCode()) {
				return HttpStatus.TOO_MANY_REQUESTS;
			}
			return HttpStatus.BAD_GATEWAY;
		} else if (code >= 4000 && code < 5000) {
			// Configuration errors
			return HttpStatus.BAD_REQUEST;
		} else if (code >= 5000 && code < 6000) {
			// Crawler errors
			return HttpStatus.INTERNAL_SERVER_ERROR;
		} else if (code >= 6000 && code < 7000) {
			// Data errors
			return HttpStatus.BAD_REQUEST;
		} else if (code >= 9000 && code < 10000) {
			// System errors
			if (code == ErrorCode.SERVICE_UNAVAILABLE.getCode()) {
				return HttpStatus.SERVICE_UNAVAILABLE;
			}
			return HttpStatus.INTERNAL_SERVER_ERROR;
		}

		// Default
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}
}