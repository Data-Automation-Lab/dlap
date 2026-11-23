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

package com.dlap.logingest.kafka;

/**
 * A normalized event could not be put on {@code dlap.log.event.normalized}: it did not encode, the
 * broker did not acknowledge it, or the acknowledgement did not arrive in time. Unchecked, so it
 * rolls back the transaction that stored the event and stops the stream poll before its cursor
 * moves (SPINE-01): the next poll reads the record again and the event is not mistaken for a
 * stored duplicate.
 *
 * <p>The message names the event id and the topic, never event content.
 */
public class LogEventPublishException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed, without event content
     * @param cause   the underlying error, or {@code null}
     */
    public LogEventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
