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

package com.dlap.discovery.client.admin;

/**
 * Failure while resolving a cloud connection through {@link IAdminServiceClient}.
 *
 * <p>Carries a {@link Reason} so callers can map the failure to the right outcome
 * (an HTTP status in {@code DiscoveryJobController}, a skipped connection in
 * {@code ScheduledDiscoveryTrigger}) without parsing messages. Messages are written for
 * an operator or an end user: they never include credentials, tokens or response
 * bodies from da-admin-service.
 */
public class AdminServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a connection could not be resolved. */
    public enum Reason {
        /** da-admin-service does not know the connection id. */
        NOT_FOUND,
        /** The connection exists but an administrator disabled it. */
        DISABLED,
        /** The connection's provider type cannot be scanned (for example OTHER). */
        UNSUPPORTED_PROVIDER,
        /** da-admin-service refused the discovery service account (HTTP 4xx other than 404). */
        REJECTED,
        /** Discovery is not configured to call da-admin-service (service-auth is off). */
        MISCONFIGURED,
        /** da-admin-service or the identity provider could not be reached or answered badly. */
        UNAVAILABLE
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason  classification of the failure; never null
     * @param message operator-readable explanation without secrets
     */
    public AdminServiceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Creates the exception with an underlying cause.
     *
     * @param reason  classification of the failure; never null
     * @param message operator-readable explanation without secrets
     * @param cause   the underlying failure
     */
    public AdminServiceException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * Returns the failure classification.
     *
     * @return the reason; never null
     */
    public Reason getReason() {
        return reason;
    }
}
