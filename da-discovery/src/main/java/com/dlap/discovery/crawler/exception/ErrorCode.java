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

package com.dlap.discovery.crawler.exception;

import com.dlap.discovery.log.service.Severity;

/**
 * Enumeration of all possible error codes in the Discovery service.
 * Each error code has a unique numerical value, a default message, and a
 * severity level.
 *
 * Error codes are categorized by functional area with distinct ranges:
 * - Resource Discovery Errors: 1000-1999
 * - Authentication/Authorization Errors: 2000-2999
 * - Cloud Provider Errors: 3000-3999
 * - Configuration Errors: 4000-4999
 * - Crawler Execution Errors: 5000-5999
 * - Data Processing Errors: 6000-6999
 * - System Errors: 9000-9999
 */
public enum ErrorCode {
    // Resource Discovery Errors (1000-1999)
    RESOURCE_NOT_FOUND(1001, "The requested resource could not be found", Severity.WARNING),
    RESOURCE_ACCESS_DENIED(1002, "Access to the resource is denied", Severity.WARNING),
    RESOURCE_ALREADY_EXISTS(1003, "Resource already exists", Severity.WARNING),
    RESOURCE_TYPE_NOT_FOUND(1004, "The specified resource type could not be found", Severity.WARNING),
    RESOURCE_TYPE_UNSUPPORTED(1005, "The specified resource type is not supported by this crawler", Severity.WARNING),
    JOB_NOT_FOUND(1006, "The specified job could not be found", Severity.WARNING),

    // Authentication/Authorization Errors (2000-2999)
    AUTHENTICATION_FAILED(2001, "Authentication failed", Severity.WARNING),
    INSUFFICIENT_PERMISSIONS(2002, "Insufficient permissions to perform this operation", Severity.WARNING),
    INVALID_CREDENTIALS(2003, "Invalid credentials provided", Severity.WARNING),
    CREDENTIALS_EXPIRED(2004, "Credentials have expired", Severity.WARNING),
    TOKEN_VALIDATION_ERROR(2005, "Failed to validate authentication token", Severity.WARNING),

    // Cloud Provider Errors (3000-3999)
    PROVIDER_CONNECTION_ERROR(3001, "Unable to connect to cloud provider", Severity.ERROR),
    PROVIDER_QUOTA_EXCEEDED(3002, "Cloud provider quota exceeded", Severity.ERROR),
    PROVIDER_RATE_LIMITED(3003, "Request rate limited by cloud provider", Severity.WARNING),
    PROVIDER_RESOURCE_NOT_FOUND(3004, "Resource not found on cloud provider", Severity.WARNING),
    PROVIDER_API_ERROR(3005, "Cloud provider API returned an error", Severity.ERROR),
    PROVIDER_ACCESS_DENIED(3006, "Access denied by cloud provider", Severity.ERROR),

    // Configuration Errors (4000-4999)
    INVALID_CONFIGURATION(4001, "Invalid configuration", Severity.ERROR),
    MISSING_CONFIGURATION(4002, "Required configuration is missing", Severity.ERROR),
    CONFIGURATION_VALIDATION_ERROR(4003, "Configuration validation failed", Severity.ERROR),
    INVALID_PARAMETER(4004, "One or more parameters are invalid", Severity.WARNING),
    MISSING_PARAMETER(4005, "One or more required parameters are missing", Severity.WARNING),

    // Crawler Execution Errors (5000-5999)
    CRAWLER_INITIALIZATION_FAILED(5001, "Failed to initialize crawler", Severity.ERROR),
    CRAWLER_EXECUTION_FAILED(5002, "Crawler execution failed", Severity.ERROR),
    CRAWLER_INTERRUPTED(5003, "Crawler execution was interrupted", Severity.WARNING),
    CRAWLER_TIMEOUT(5004, "Crawler execution timed out", Severity.WARNING),
    CRAWLER_DEPENDENCY_MISSING(5005, "A required crawler dependency is missing", Severity.ERROR),
    EXECUTOR_NOT_FOUND(5006, "No suitable executor found for the execution mode", Severity.ERROR),

    // Data Processing Errors (6000-6999)
    DATA_PARSING_ERROR(6001, "Error parsing data", Severity.ERROR),
    DATA_VALIDATION_ERROR(6002, "Data validation failed", Severity.WARNING),
    DATA_TRANSFORMATION_ERROR(6003, "Error transforming data", Severity.ERROR),
    DATA_SERIALIZATION_ERROR(6004, "Failed to serialize data", Severity.ERROR),
    DATA_DESERIALIZATION_ERROR(6005, "Failed to deserialize data", Severity.ERROR),

    // System Errors (9000-9999)
    UNEXPECTED_ERROR(9001, "An unexpected error occurred", Severity.CRITICAL),
    EXTERNAL_SERVICE_ERROR(9002, "Error in external service", Severity.ERROR),
    RESOURCE_EXHAUSTED(9003, "System resources exhausted", Severity.CRITICAL),
    INTERNAL_SERVER_ERROR(9004, "Internal server error", Severity.CRITICAL),
    SERVICE_UNAVAILABLE(9005, "Service is currently unavailable", Severity.CRITICAL),

    INVALID_JOB_TYPE(9006, "Invalid job type", Severity.ERROR),
    INVALID_JOB_STATUS(9007, "Invalid job status", Severity.ERROR);

    private final int code;
    private final String defaultMessage;
    private final Severity severity;

    ErrorCode(int code, String defaultMessage, Severity severity) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.severity = severity;
    }

    /**
     * Gets the numeric error code.
     *
     * @return The numeric error code
     */
    public int getCode() {
        return code;
    }

    /**
     * Gets the default error message.
     *
     * @return The default human-readable error message
     */
    public String getDefaultMessage() {
        return defaultMessage;
    }

    /**
     * Gets the severity level of this error.
     *
     * @return The severity enum value
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * Finds an ErrorCode by its numeric code value.
     *
     * @param code The numeric error code
     * @return The corresponding ErrorCode enum value or null if not found
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.getCode() == code) {
                return errorCode;
            }
        }
        return null;
    }
}
