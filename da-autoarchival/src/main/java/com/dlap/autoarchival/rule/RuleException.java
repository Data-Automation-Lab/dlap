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

package com.dlap.autoarchival.rule;

/**
 * A native archive rule cannot be planned or applied. The {@link Reason} is a stable machine code
 * (it is written to the audit log and to the task), the message says why in words and never
 * carries a secret, a SAS token or object content.
 */
public class RuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the rule was refused or failed. */
    public enum Reason {
        /** The provider has no native rule support in this service. */
        UNSUPPORTED_PROVIDER,
        /** The asset is not a bucket, container or object of a storage service. */
        UNSUPPORTED_ASSET,
        /** The location is a URL with a query or fragment, has stray whitespace or cannot be parsed. */
        UNSAFE_LOCATION,
        /** The tier is not an archive class the provider's rule accepts. */
        INVALID_TIER,
        /** The age is below the provider's minimum for the class or above the configured maximum. */
        INVALID_AGE,
        /** The prefix is longer than the provider allows. */
        PREFIX_TOO_LONG,
        /** A bucket-wide rule is switched off by configuration. */
        BUCKET_WIDE_NOT_ALLOWED,
        /** The bucket already holds the provider's maximum number of rules, or DLAP's own cap. */
        RULE_LIMIT,
        /** The provider refused the call or could not be reached. */
        PROVIDER_ERROR,
        /** The rule was written but reading it back did not show it, or another rule was lost. */
        VERIFY_FAILED,
        /** DLAP has no call that applies this provider's rule; the rule document is the result. */
        APPLY_NOT_SUPPORTED,
        /** A live DLAP row already holds the identical GCS scope, class and age (Q-673); the message names it. */
        DUPLICATE_SCOPE
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason  the machine code
     * @param message the description
     */
    public RuleException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Creates the exception with a cause.
     *
     * @param reason  the machine code
     * @param message the description
     * @param cause   the underlying failure
     */
    public RuleException(Reason reason, String message, Throwable cause) {
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
}
