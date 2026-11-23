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

package com.dlap.autoarchival.approval;

/**
 * The executor refuses an archive action because it does not carry an executable approval
 * (docs/specs/approvals.md section 9). A refusal is final unless {@link #isTransient()}: then the
 * approval could not be checked (policy engine unreachable) and a retry may succeed.
 */
public class ApprovalRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the action was refused. */
    public enum Reason {
        /** The action carries no {@code approval} object (section 9, rule 1). */
        NO_APPROVAL,
        /** The approval status is neither APPROVED nor APPROVED_BREAK_GLASS (rule 1). */
        NOT_APPROVED,
        /** The approval was given for another action class (rule 1). */
        WRONG_CLASS,
        /** The approval names another asset than the action (rule 3). */
        ASSET_MISMATCH,
        /** The policy engine says the request is not executable: rejected, expired or unknown (rule 3). */
        NOT_EXECUTABLE,
        /** The request is still waiting for decisions; the action waits too. */
        NOT_DECIDED,
        /** The policy engine could not be asked (rule 3); the action is not executed. */
        VERIFY_UNAVAILABLE
    }

    private final Reason reason;

    /**
     * Creates the refusal.
     *
     * @param reason  the machine code
     * @param message the description, without secrets
     */
    public ApprovalRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Creates the refusal with a cause.
     *
     * @param reason  the machine code
     * @param message the description, without secrets
     * @param cause   the underlying failure
     */
    public ApprovalRefusedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * Returns the machine code.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Whether a retry could change the answer.
     *
     * @return true when the approval could not be checked
     */
    public boolean isTransient() {
        return reason == Reason.VERIFY_UNAVAILABLE;
    }

    /**
     * Whether the request is still waiting for decisions (the action waits, nothing is refused).
     *
     * @return true when the approval is {@code PENDING}
     */
    public boolean isPending() {
        return reason == Reason.NOT_DECIDED;
    }
}
