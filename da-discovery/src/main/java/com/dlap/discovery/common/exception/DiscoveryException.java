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

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.crawler.exception.ErrorCode;
import com.dlap.discovery.log.service.Severity;

/**
 * Base exception class for all Discovery service exceptions.
 * Provides structured error information including error codes,
 * user-friendly messages, and additional context details.
 */
public class DiscoveryException extends RuntimeException {

	private static final Logger log = LoggerFactory.getLogger(DiscoveryException.class);
	private static final long serialVersionUID = 1L;

	private final String errorId;
	private final ErrorCode errorCode;
	private final String userMessage;
	private final Map<String, Object> additionalDetails;
	private final LocalDateTime timestamp;

	/**
	 * Creates a new DiscoveryException with the specified error code and default
	 * message.
	 * 
	 * @param errorCode The error code identifying the error type
	 */
	public DiscoveryException(ErrorCode errorCode) {
		this(errorCode, errorCode.getDefaultMessage(), Collections.emptyMap());
	}

	/**
	 * Creates a new DiscoveryException with a custom user message.
	 * 
	 * @param errorCode   The error code identifying the error type
	 * @param userMessage A user-friendly message describing the error
	 */
	public DiscoveryException(ErrorCode errorCode, String userMessage) {
		this(errorCode, userMessage, Collections.emptyMap());
	}

	/**
	 * Creates a new DiscoveryException with additional context details.
	 * 
	 * @param errorCode         The error code identifying the error type
	 * @param userMessage       A user-friendly message describing the error
	 * @param additionalDetails Additional details about the error context
	 */
	public DiscoveryException(ErrorCode errorCode, String userMessage, Map<String, Object> additionalDetails) {
		super(String.format("[%d] %s", errorCode.getCode(), userMessage));
		this.errorId = UUID.randomUUID().toString();
		this.errorCode = errorCode;
		this.userMessage = userMessage;
		this.additionalDetails = Collections.unmodifiableMap(additionalDetails);
		this.timestamp = LocalDateTime.now();

		logException();
	}

	/**
	 * Creates a new DiscoveryException with a cause.
	 * 
	 * @param errorCode   The error code identifying the error type
	 * @param userMessage A user-friendly message describing the error
	 * @param cause       The underlying cause of this exception
	 */
	public DiscoveryException(ErrorCode errorCode, String userMessage, Throwable cause) {
		this(errorCode, userMessage, Collections.emptyMap(), cause);
	}

	/**
	 * Creates a new DiscoveryException with additional context details and a cause.
	 * 
	 * @param errorCode         The error code identifying the error type
	 * @param userMessage       A user-friendly message describing the error
	 * @param additionalDetails Additional details about the error context
	 * @param cause             The underlying cause of this exception
	 */
	public DiscoveryException(ErrorCode errorCode, String userMessage,
			Map<String, Object> additionalDetails, Throwable cause) {
		super(String.format("[%d] %s", errorCode.getCode(), userMessage), cause);
		this.errorId = UUID.randomUUID().toString();
		this.errorCode = errorCode;
		this.userMessage = userMessage;
		this.additionalDetails = Collections.unmodifiableMap(additionalDetails);
		this.timestamp = LocalDateTime.now();

		logException();
	}

	/**
	 * Logs the exception with appropriate severity level.
	 */
	private void logException() {
		Severity severity = errorCode.getSeverity();

		switch (severity) {
			case ERROR:
			case CRITICAL:
				log.error("Error {}: {} - Details: {} (ID: {})",
						errorCode.getCode(), userMessage, additionalDetails, errorId, this);
				break;
			case WARNING:
				log.warn("Warning {}: {} - Details: {} (ID: {})",
						errorCode.getCode(), userMessage, additionalDetails, errorId);
				break;
			case INFO:
			default:
				log.info("Info {}: {} - Details: {} (ID: {})",
						errorCode.getCode(), userMessage, additionalDetails, errorId);
				break;
		}
	}

	/**
	 * Gets the unique error ID for this exception instance.
	 * 
	 * @return The unique error ID (UUID)
	 */
	public String getErrorId() {
		return errorId;
	}

	/**
	 * Gets the error code for this exception.
	 * 
	 * @return The error code enum value
	 */
	public ErrorCode getErrorCode() {
		return errorCode;
	}

	/**
	 * Gets the user-friendly error message.
	 * 
	 * @return The user-friendly error message
	 */
	public String getUserMessage() {
		return userMessage;
	}

	/**
	 * Gets additional details about the error context.
	 * 
	 * @return An unmodifiable map of additional error details
	 */
	public Map<String, Object> getAdditionalDetails() {
		return additionalDetails;
	}

	/**
	 * Gets the timestamp when this exception was created.
	 * 
	 * @return The exception creation timestamp
	 */
	public LocalDateTime getTimestamp() {
		return timestamp;
	}
}