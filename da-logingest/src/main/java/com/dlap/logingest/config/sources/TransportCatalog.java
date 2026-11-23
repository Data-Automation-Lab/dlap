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

package com.dlap.logingest.config.sources;

import java.util.Optional;
import java.util.Set;

/**
 * What the validator needs to know about transports: which ids are registered and which location
 * keys each one reads. The transport layer implements it from its self-registered beans, so this
 * package does not depend on the transport package.
 */
public interface TransportCatalog {

    /**
     * The location keys of one transport.
     *
     * @param required keys every stream of the transport must set
     * @param optional keys the transport also reads
     */
    record Requirements(Set<String> required, Set<String> optional) {

        /**
         * Freezes the sets.
         *
         * @param required see the record description
         * @param optional see the record description
         */
        public Requirements {
            required = required == null ? Set.of() : Set.copyOf(required);
            optional = optional == null ? Set.of() : Set.copyOf(optional);
        }

        /**
         * Whether the transport reads a key.
         *
         * @param key the location key
         * @return true when the key is required or optional
         */
        public boolean reads(String key) {
            return required.contains(key) || optional.contains(key);
        }
    }

    /**
     * The requirements of a transport.
     *
     * @param transportId the transport id
     * @return the requirements, or empty when no transport of that id is registered
     */
    Optional<Requirements> requirements(String transportId);

    /**
     * A catalog with no transports.
     *
     * @return the catalog
     */
    static TransportCatalog none() {
        return id -> Optional.empty();
    }
}
