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

/**
 * The registry key of a record format: source kind, envelope and schema version (design B1).
 *
 * <p>All three parts are open strings ({@link FormatNames}). The version is the one detected on a
 * record ({@code DlapLogEvent.source_schema_version}) or the one a parser is asked to parse as.
 *
 * @param sourceKind    upper snake case, for example {@code AWS_CLOUDTRAIL}
 * @param envelope      lower kebab case, for example {@code lookup-events}
 * @param schemaVersion for example {@code 1.11} or {@code bq-auditmetadata}
 */
public record FormatKey(String sourceKind, String envelope, String schemaVersion) {

    /**
     * Validates every part.
     *
     * @param sourceKind    see the record description
     * @param envelope      see the record description
     * @param schemaVersion see the record description
     */
    public FormatKey {
        FormatNames.requireSourceKind(sourceKind);
        FormatNames.requireEnvelope(envelope);
        FormatNames.requireVersion(schemaVersion);
    }

    /**
     * Returns the source kind and envelope.
     *
     * @return the envelope key
     */
    public EnvelopeKey envelopeKey() {
        return new EnvelopeKey(sourceKind, envelope);
    }

    /**
     * Returns the {@code DlapLogEvent.source_format} value.
     *
     * @return {@code <sourceKind>/<envelope>}
     */
    public String sourceFormat() {
        return envelopeKey().sourceFormat();
    }

    /**
     * Returns the same format at another version.
     *
     * @param version the version
     * @return a new key
     */
    public FormatKey withSchemaVersion(String version) {
        return new FormatKey(sourceKind, envelope, version);
    }

    @Override
    public String toString() {
        return sourceKind + "/" + envelope + "@" + schemaVersion;
    }
}
