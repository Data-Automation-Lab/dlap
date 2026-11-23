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

import com.dlap.proto.log.v1.DlapLogEvent;
import java.util.List;
import java.util.Objects;

/**
 * A decoded log event plus how it arrived.
 *
 * @param event    the event
 * @param encoding the wire encoding it was read from
 * @param warnings what the lenient reader dropped or rewrote (unknown fields,
 *                 unknown enum values, legacy keys), as field paths without values
 */
public record DecodedLogEvent(DlapLogEvent event, PayloadEncoding encoding, List<String> warnings) {

    /**
     * Validates and copies the components.
     *
     * @param event    the event
     * @param encoding the wire encoding
     * @param warnings the reader's warnings
     */
    public DecodedLogEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(encoding, "encoding");
        warnings = List.copyOf(warnings);
    }

    /**
     * True when the payload used a legacy form: the double encoding, legacy
     * keys or values the contract does not define.
     *
     * @return whether the producer should be fixed
     */
    public boolean isLegacy() {
        return encoding == PayloadEncoding.STRING_WRAPPED_JSON || !warnings.isEmpty();
    }
}
