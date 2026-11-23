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
 * What a binding does when the version detected on a record differs from the version it pins
 * (design B1 step 3, binding key {@code on-mismatch}). A binding with {@code version: auto} pins
 * nothing and never mismatches.
 *
 * <p>The policy never overrides the unknown-version rule: a version no parser supports is
 * quarantined as {@code UNKNOWN_MAJOR_VERSION} whatever this says.
 */
public enum OnMismatch {

    /** Quarantine the record as {@code VERSION_PIN_MISMATCH}. */
    QUARANTINE,

    /** Parse as the pinned version and add {@code PARSE_WARNING_VERSION_PIN_MISMATCH}. */
    PARSE_WITH_PIN,

    /** Parse as the detected version and add {@code PARSE_WARNING_VERSION_PIN_MISMATCH}. The default. */
    PARSE_WITH_DETECTED;

    /** The default policy (design B2 {@code defaults.on-mismatch}). */
    public static final OnMismatch DEFAULT = PARSE_WITH_DETECTED;
}
