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

import com.dlap.common.audit.AuditException;

/**
 * The audit log could not record a governance change, so the change must not commit (fail closed).
 * Every cause of a failed append ends here, a database error ({@link AuditException}) as well as a
 * refusal of the writer ({@link IllegalArgumentException}, for example a different event under an
 * idempotency key the chain already holds), so a caller answers 503 {@code audit-unavailable} and
 * never 400 or 500 (Q-692).
 */
public class AuditUnavailableException extends AuditException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what could not be recorded
     * @param cause   the failure, or {@code null}
     */
    public AuditUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
