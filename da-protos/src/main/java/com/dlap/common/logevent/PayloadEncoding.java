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
 * How a decoded payload was encoded on the wire. Consumers log or count the
 * legacy forms so the double-encoding can be retired once every producer
 * writes {@link #JSON_OBJECT} (ADR-0045).
 */
public enum PayloadEncoding {

    /** A JSON object: the one encoding producers write. */
    JSON_OBJECT,

    /**
     * Legacy double encoding: a JSON string whose text is the JSON object,
     * produced by sending a pre-serialized String through Spring Kafka's
     * {@code JsonSerializer} ({@code LogEventPublisher}, the crawler bridge
     * and the log-analyzer publisher before ADR-0045).
     */
    STRING_WRAPPED_JSON,

    /** Already deserialized by the caller into a Jackson tree or a Map. */
    TREE,

    /** Already a {@code DlapLogEvent} message; nothing was decoded. */
    MESSAGE
}
