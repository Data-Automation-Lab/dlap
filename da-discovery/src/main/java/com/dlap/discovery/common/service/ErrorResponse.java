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

package com.dlap.discovery.common.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standardized error response for REST API errors.
 * This class provides a consistent structure for all error responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_DATE_TIME;

	private final int code;
	private final String message;
	private final String errorId;
	private final Map<String, Object> details;

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
	private final String timestamp;

	/**
	 * Creates a new ErrorResponse from a DiscoveryException.
	 * 
	 * @param exception The exception to convert to an error response
	 */
	public ErrorResponse(DiscoveryException exception) {
		this.code = exception.getErrorCode().getCode();
		this.message = exception.getUserMessage();
		this.errorId = exception.getErrorId();
		this.details = exception.getAdditionalDetails().isEmpty() ? null : exception.getAdditionalDetails();
		this.timestamp = DATE_FORMAT.format(exception.getTimestamp());
	}

	/**
	 * Creates a new ErrorResponse with the specified attributes.
	 * 
	 * @param code      The error code
	 * @param message   The error message
	 * @param errorId   The unique error ID
	 * @param details   Additional error details
	 * @param timestamp The timestamp when the error occurred
	 */
	public ErrorResponse(int code, String message, String errorId,
			Map<String, Object> details, LocalDateTime timestamp) {
		this.code = code;
		this.message = message;
		this.errorId = errorId;
		this.details = details;
		this.timestamp = DATE_FORMAT.format(timestamp);
	}

	/**
	 * Creates a new ErrorResponse for a general error.
	 * 
	 * @param code    The error code
	 * @param message The error message
	 */
	public ErrorResponse(int code, String message) {
		this.code = code;
		this.message = message;
		this.errorId = null;
		this.details = null;
		this.timestamp = DATE_FORMAT.format(LocalDateTime.now());
	}

	// Getters

	public int getCode() {
		return code;
	}

	public String getMessage() {
		return message;
	}

	public String getErrorId() {
		return errorId;
	}

	public Map<String, Object> getDetails() {
		return details;
	}

	public String getTimestamp() {
		return timestamp;
	}
}