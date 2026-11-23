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

package com.dlap.discovery.log.exception;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.crawler.exception.ErrorCode;

/**
 * Exception thrown when there's an error communicating with or processing data
 * from a cloud provider.
 * This includes API errors, authentication issues, quota problems, etc.
 */
public class CloudProviderException extends DiscoveryException {

    private static final Logger log = LoggerFactory.getLogger(CloudProviderException.class);
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new CloudProviderException with the specified error code and
     * default message.
     * 
     * @param errorCode The specific cloud provider error code
     */
    public CloudProviderException(ErrorCode errorCode) {
        super(errorCode);
        validateErrorCode(errorCode);
    }

    /**
     * Creates a new CloudProviderException with a custom message.
     * 
     * @param errorCode The specific cloud provider error code
     * @param message   A user-friendly error message
     */
    public CloudProviderException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        validateErrorCode(errorCode);
    }

    /**
     * Creates a new CloudProviderException with a specific cloud provider and
     * operation.
     * 
     * @param errorCode The specific cloud provider error code
     * @param provider  The cloud provider name (e.g., "GCP", "AWS")
     * @param operation The operation that failed (e.g., "list buckets", "describe
     *                  instances")
     */
    public CloudProviderException(ErrorCode errorCode, String provider, String operation) {
        super(errorCode,
                String.format("Cloud provider error (%s) during operation: %s", provider, operation),
                createDetails(provider, operation, null));
        validateErrorCode(errorCode);
    }

    /**
     * Creates a new CloudProviderException with a specific provider, operation, and
     * error details.
     * 
     * @param errorCode    The specific cloud provider error code
     * @param provider     The cloud provider name (e.g., "GCP", "AWS")
     * @param operation    The operation that failed (e.g., "list buckets",
     *                     "describe instances")
     * @param errorDetails Additional provider-specific error details
     */
    public CloudProviderException(ErrorCode errorCode, String provider, String operation, String errorDetails) {
        super(errorCode,
                String.format("Cloud provider error (%s) during operation: %s - %s",
                        provider, operation, errorDetails),
                createDetails(provider, operation, errorDetails));
        validateErrorCode(errorCode);
    }

    /**
     * Creates a new CloudProviderException with a specific provider, operation, and
     * cause.
     * 
     * @param errorCode The specific cloud provider error code
     * @param provider  The cloud provider name (e.g., "GCP", "AWS")
     * @param operation The operation that failed (e.g., "list buckets", "describe
     *                  instances")
     * @param cause     The underlying exception
     */
    public CloudProviderException(ErrorCode errorCode, String provider, String operation, Throwable cause) {
        super(errorCode,
                String.format("Cloud provider error (%s) during operation: %s - %s",
                        provider, operation, cause.getMessage()),
                createDetails(provider, operation, cause.getMessage()),
                cause);
        validateErrorCode(errorCode);
    }

    /**
     * Validates that the error code is appropriate for a cloud provider exception.
     */
    private void validateErrorCode(ErrorCode errorCode) {
        // Ensure the error code is in the cloud provider range (3000-3999)
        int code = errorCode.getCode();
        if (code < 3000 || code > 3999) {
            // Just log a warning, don't throw an exception as that would be confusing
            // This is primarily to help developers use the right error codes
            log.warn("CloudProviderException created with non-provider error code: {}", code);
        }
    }

    private static Map<String, Object> createDetails(String provider, String operation, String errorDetails) {
        Map<String, Object> details = new HashMap<>();
        details.put("provider", provider);
        details.put("operation", operation);
        if (errorDetails != null) {
            details.put("providerErrorDetails", errorDetails);
        }
        return details;
    }
}