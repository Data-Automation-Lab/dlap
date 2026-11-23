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

import java.util.Objects;
import java.util.Optional;

/**
 * One finding of {@link LogSourceConfigValidator}.
 *
 * <p>Messages name fields, ids and environment variable names; they never quote a value that
 * could be a secret (a {@code credentials-ref} that is not a variable name is reported by its
 * path only).
 *
 * @param severity  error or warning
 * @param rule      the rule, stable for tests and runbooks
 * @param path      where in the file, for example {@code sources[aws-org].bindings[aws-1].streams[s3-file]}
 * @param bindingId the binding the finding makes invalid, or {@code null} for a file-wide finding
 * @param message   what is wrong and how to fix it
 */
public record ConfigProblem(Severity severity, Rule rule, String path, String bindingId, String message) {

    /** How bad a finding is. */
    public enum Severity {
        /** Startup fails under strict validation; otherwise the binding does not run. */
        ERROR,
        /** Logged; never fails startup. */
        WARNING
    }

    /** The design B2 rules and the checks around them. */
    public enum Rule {
        /** Two sources, two bindings, or two streams of one binding share an id. */
        DUPLICATE_ID,
        /** An id breaks the id syntax. */
        INVALID_ID,
        /** An id, source kind or transport is reserved for the built-in channels. */
        RESERVED_ID,
        /** No registered parser or mapping knows the source kind. */
        UNKNOWN_SOURCE_KIND,
        /** The provider's scope key is missing. */
        SCOPE_MISSING,
        /** A scope id breaks its provider's syntax. */
        SCOPE_SYNTAX,
        /** A binding has no stream. */
        NO_STREAM,
        /** No transport bean serves the stream's transport. */
        UNKNOWN_TRANSPORT,
        /** The transport needs a location key the stream does not set. */
        MISSING_LOCATION,
        /** The stream sets a location key its transport does not read. */
        UNKNOWN_LOCATION_KEY,
        /** No envelope is set and the family has more than one. */
        ENVELOPE_UNRESOLVED,
        /** No detector is registered for the envelope. */
        UNKNOWN_ENVELOPE,
        /** A pinned version breaks the version syntax. */
        VERSION_SYNTAX,
        /** No registered parser reads the pinned version. */
        PIN_NOT_READ,
        /** The pin is above the highest version the parser knows; records parse as that version. */
        PIN_NEWER_MINOR,
        /** A value is not one of the allowed values or not a valid duration or number. */
        INVALID_VALUE,
        /** A field-override key is not a canonical field. */
        FIELD_OVERRIDE_KEY,
        /** A field-override value is not a JSON pointer. */
        FIELD_OVERRIDE_POINTER,
        /** An operation-override row is malformed. */
        OPERATION_OVERRIDE,
        /** A credentials-ref is neither an environment variable name nor a role ARN. */
        CREDENTIALS_REF_SYNTAX,
        /** The environment variable a credentials-ref names is not set. */
        CREDENTIALS_REF_MISSING,
        /** Two streams read the same scope without {@code dedup: cross-stream}. */
        OVERLAPPING_STREAMS,
        /** Two streams of one binding with one envelope resolve different parse settings. */
        PARSE_SETTINGS_CONFLICT,
        /** {@code config-mode} is not {@code FILE}. */
        CONFIG_MODE,
        /** {@code on-unknown-major} is not {@code QUARANTINE}. */
        UNKNOWN_MAJOR_POLICY,
        /** A legacy source is enabled while the registry pipeline is selected. */
        LEGACY_SOURCE_ON_REGISTRY,
        /** Bindings are configured but the legacy pipeline does not read them. */
        INACTIVE_ON_LEGACY
    }

    /**
     * Validates the finding.
     *
     * @param severity  see the record description
     * @param rule      see the record description
     * @param path      see the record description
     * @param bindingId see the record description
     * @param message   see the record description
     */
    public ConfigProblem {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(rule, "rule");
        path = path == null ? "" : path;
        message = message == null ? "" : message;
    }

    /**
     * The binding the finding concerns.
     *
     * @return the binding id, or empty for a file-wide finding
     */
    public Optional<String> binding() {
        return Optional.ofNullable(bindingId);
    }

    /**
     * Whether the finding is an error.
     *
     * @return true for {@link Severity#ERROR}
     */
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return severity + " " + rule + (path.isEmpty() ? "" : " at " + path) + ": " + message;
    }
}
