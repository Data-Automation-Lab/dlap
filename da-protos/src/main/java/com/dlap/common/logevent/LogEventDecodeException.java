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

package com.dlap.common.logevent;

/**
 * A payload on a log-layer topic could not be decoded. Unchecked so a Kafka
 * listener can let it propagate to its error handler and dead-letter topic;
 * the message names the problem and the field path, never payload values.
 */
public class LogEventDecodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why decoding failed. */
    public enum Reason {
        /** Null, empty or whitespace-only payload. */
        EMPTY_PAYLOAD,
        /** The bytes are not JSON. */
        INVALID_JSON,
        /** Valid JSON, but not an object (after unwrapping string encodings). */
        NOT_AN_OBJECT,
        /** A field has a value its type cannot hold (for example a bad timestamp). */
        FIELD_TYPE_MISMATCH,
        /** The strict reader found a field the contract does not define. */
        UNKNOWN_FIELD,
        /** The payload is neither bytes, text, a Jackson tree, a Map nor a message. */
        UNSUPPORTED_PAYLOAD_TYPE
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason  why decoding failed
     * @param message what failed, without payload values
     * @param cause   the underlying parser error, or {@code null}
     */
    public LogEventDecodeException(Reason reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = reason;
    }

    /**
     * Returns why decoding failed.
     *
     * @return the reason
     */
    public Reason getReason() {
        return reason;
    }
}
