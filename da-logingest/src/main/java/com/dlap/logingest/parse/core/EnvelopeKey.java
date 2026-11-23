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

import com.dlap.common.logevent.SourceFormats;

/**
 * A source kind and one of its envelopes: the unit that parsers and version detectors register for.
 *
 * @param sourceKind upper snake case, for example {@code AWS_CLOUDTRAIL}
 * @param envelope   lower kebab case, for example {@code s3-file}
 */
public record EnvelopeKey(String sourceKind, String envelope) {

    /**
     * Validates both parts.
     *
     * @param sourceKind see the record description
     * @param envelope   see the record description
     */
    public EnvelopeKey {
        FormatNames.requireSourceKind(sourceKind);
        FormatNames.requireEnvelope(envelope);
    }

    /**
     * Adds a schema version.
     *
     * @param schemaVersion the version
     * @return the full format key
     */
    public FormatKey at(String schemaVersion) {
        return new FormatKey(sourceKind, envelope, schemaVersion);
    }

    /**
     * Returns the {@code DlapLogEvent.source_format} value.
     *
     * @return {@code <sourceKind>/<envelope>}
     */
    public String sourceFormat() {
        return SourceFormats.of(sourceKind, envelope);
    }

    @Override
    public String toString() {
        return sourceKind + "/" + envelope;
    }
}
