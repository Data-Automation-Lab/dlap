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

package com.dlap.logingest.parse.core.mapping;

import java.util.Arrays;
import java.util.Optional;

/**
 * How a delivered unit (an object, a blob, an API page item) holds records, per envelope
 * ({@code framing} in the mapping YAML). Transports and the fixture contract tests split units
 * with {@link RecordFraming}, so a family states its container shapes once.
 */
public enum Framing {

    /** The unit is one record. */
    RECORD("record"),

    /** One record per non-blank line (JSON Lines, NDJSON). */
    JSON_LINES("json-lines"),

    /** A top-level JSON array of records. */
    JSON_ARRAY("json-array"),

    /** An object whose array at {@code records-pointer} holds the records ({@code {"Records": [...]}}). */
    JSON_OBJECT_ARRAY("json-object-array");

    private final String id;

    Framing(String id) {
        this.id = id;
    }

    /**
     * Returns the YAML spelling.
     *
     * @return for example {@code json-lines}
     */
    public String id() {
        return id;
    }

    /**
     * Looks a framing up by its YAML spelling.
     *
     * @param id the spelling
     * @return the framing, or empty
     */
    public static Optional<Framing> fromId(String id) {
        return Arrays.stream(values()).filter(f -> f.id.equals(id)).findFirst();
    }
}
