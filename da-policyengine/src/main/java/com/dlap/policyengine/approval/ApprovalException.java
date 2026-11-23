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

package com.dlap.policyengine.approval;

import org.springframework.http.HttpStatus;

/**
 * A refused approval operation, with the HTTP status the API answers and a stable machine reason
 * (docs/specs/approvals.md, "Errors"). The message never carries a comment or a justification.
 */
public class ApprovalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The request does not exist. */
    public static final String NOT_FOUND = "not-found";
    /** The request is no longer pending. */
    public static final String NOT_PENDING = "not-pending";
    /** The request expired before this decision. */
    public static final String EXPIRED = "expired";
    /** The caller asked for, or submitted, the request, or authored its policy. */
    public static final String SELF_APPROVAL = "self-approval";
    /** The caller already decided the request. */
    public static final String ALREADY_DECIDED = "already-decided";
    /** The caller holds no approver role, or is a service account. */
    public static final String NOT_AN_APPROVER = "not-an-approver";
    /** The token lacks the MFA marker role. */
    public static final String MFA_REQUIRED = "mfa-required";
    /** The token has no usable subject. */
    public static final String NO_SUBJECT = "no-subject";
    /** Break-glass is switched off. */
    public static final String BREAK_GLASS_DISABLED = "break-glass-disabled";
    /** The request body is not acceptable. */
    public static final String INVALID = "invalid";
    /** Only a service account may name the person it acts for. */
    public static final String ON_BEHALF_REFUSED = "on-behalf-refused";

    /** Only a service account may withdraw a request. */
    public static final String SERVICE_ACCOUNT_ONLY = "service-account-only";
    /** The audit log could not record the withdrawal, so it did not happen. */
    public static final String AUDIT_UNAVAILABLE = "audit-unavailable";

    private final HttpStatus status;
    private final String currentStatus;
    private final String reason;

    /**
     * Creates the exception.
     *
     * @param status  the HTTP status to answer
     * @param reason  the machine reason, one of the constants of this class
     * @param message a human message without user-supplied text
     */
    public ApprovalException(HttpStatus status, String reason, String message) {
        this(status, reason, message, null);
    }

    /**
     * Creates the exception of a refused state change, naming the state the request is in.
     *
     * @param status        the HTTP status to answer
     * @param reason        the machine reason, one of the constants of this class
     * @param message       a human message without user-supplied text
     * @param currentStatus the state the request is in; null when the refusal is not about a state
     */
    public ApprovalException(HttpStatus status, String reason, String message, ApprovalStatus currentStatus) {
        super(message);
        this.status = status;
        this.reason = reason;
        this.currentStatus = currentStatus == null ? null : currentStatus.name();
    }

    /**
     * The state the request was in when a state change was refused (withdraw answers it as
     * {@code status} in the 409 body).
     *
     * @return the state name, or null
     */
    public String currentStatus() {
        return currentStatus;
    }

    /**
     * The HTTP status to answer.
     *
     * @return the status
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * The machine reason.
     *
     * @return the reason
     */
    public String reason() {
        return reason;
    }

    static ApprovalException invalid(String message) {
        return new ApprovalException(HttpStatus.BAD_REQUEST, INVALID, message);
    }

    static ApprovalException forbidden(String reason, String message) {
        return new ApprovalException(HttpStatus.FORBIDDEN, reason, message);
    }

    static ApprovalException conflict(String reason, String message) {
        return new ApprovalException(HttpStatus.CONFLICT, reason, message);
    }

    static ApprovalException conflict(String reason, String message, ApprovalStatus currentStatus) {
        return new ApprovalException(HttpStatus.CONFLICT, reason, message, currentStatus);
    }

    static ApprovalException unavailable(String reason, String message) {
        return new ApprovalException(HttpStatus.SERVICE_UNAVAILABLE, reason, message);
    }

    static ApprovalException notFound() {
        return new ApprovalException(HttpStatus.NOT_FOUND, NOT_FOUND, "approval request not found");
    }
}
