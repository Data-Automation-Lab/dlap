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

package com.dlap.autoarchival.exception;

/**
 * Signals a transient failure while processing an archival action (e.g. network
 * timeout, temporary upstream outage). These are retryable - Kafka's
 * {@code DefaultErrorHandler} should back off and re-deliver before routing to DLQ.
 */
public class TransientArchivalException extends RuntimeException {

    public TransientArchivalException(String message) {
        super(message);
    }

    public TransientArchivalException(String message, Throwable cause) {
        super(message, cause);
    }
}
