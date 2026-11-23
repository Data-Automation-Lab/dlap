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

package com.dlap.logingest.parse.core;

import java.util.Optional;

/**
 * Recognizes the envelope shape of a record and reads its schema version (design B1 step 1).
 *
 * <p>One detector serves one {@link EnvelopeKey}; the registry refuses two for the same key.
 * Detectors self-register: declare one as a Spring bean, or give the envelope a
 * {@code version-detection} section in the family's mapping YAML and the registry builds a
 * declarative detector from it. A family uses one of the two for an envelope, never both.
 *
 * <p>A detector is pure and total: it reads only the decoded body, never throws on any JSON value,
 * and has no side effects. Returning empty means "this is not a record of my envelope", which the
 * registry quarantines as {@code UNKNOWN_ENVELOPE}.
 */
public interface VersionDetector {

    /**
     * Returns the envelope this detector serves.
     *
     * @return the source kind and envelope
     */
    EnvelopeKey envelopeKey();

    /**
     * Detects the format of a decoded record.
     *
     * @param record a record whose body decoded and whose envelope key equals {@link #envelopeKey()}
     * @return the format, with the same source kind and envelope as the record, or empty when the
     *         body is not a record of this envelope or carries no readable version
     */
    Optional<FormatKey> detect(RawRecord record);
}
