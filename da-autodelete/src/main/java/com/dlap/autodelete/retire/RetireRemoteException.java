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

package com.dlap.autodelete.retire;

/**
 * A call to another DLAP service (the policy engine's approval API, the catalog) did not give an
 * answer the executor can act on. A retryable failure (the service is down, a timeout, a 5xx)
 * leaves the work where it is for the next attempt; a permanent one (a 4xx other than 404) is
 * reported as a refusal. An executor never reads either as "allowed".
 */
public class RetireRemoteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    /**
     * Creates the exception.
     *
     * @param message   what failed, without credentials
     * @param retryable whether a later attempt may succeed
     * @param cause     the cause, or null
     */
    public RetireRemoteException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** @return whether a later attempt may succeed */
    public boolean isRetryable() {
        return retryable;
    }
}
