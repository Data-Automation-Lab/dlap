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

/**
 * The frozen parser contract of the log layer (ADR-0045 decision 8, ADR-0054,
 * {@code docs/specs/log-parser-contract.md}).
 *
 * <p>Every log family builds on these types and nothing else:
 * <ul>
 *   <li>{@link com.dlap.logingest.parse.core.RawRecord} in, a
 *       {@link com.dlap.logingest.parse.core.ParseResult} out, through
 *       {@link com.dlap.logingest.parse.core.ParserRegistry};</li>
 *   <li>{@link com.dlap.logingest.parse.core.VersionDetector} and
 *       {@link com.dlap.logingest.parse.core.LogRecordParser} beans that self-register, keyed by
 *       {@link com.dlap.logingest.parse.core.FormatKey} (open-string source kind, envelope and
 *       version);</li>
 *   <li>{@link com.dlap.logingest.parse.core.CanonicalEvent}, which enforces the producer rules,
 *       and {@link com.dlap.logingest.parse.core.CanonicalEventMapper}, which writes the
 *       {@code dlap.log.v1} wire contract;</li>
 *   <li>{@link com.dlap.logingest.parse.core.BindingConfig}, the parser's view of a binding.</li>
 * </ul>
 *
 * <p>The frozen rules: source and envelope ids are strings; parsers, detectors and transports
 * self-register as beans; per-family settings live in the family YAML; an unknown major version
 * is quarantined and a newer minor is parsed with a warning; the event id is
 * {@code SHA-256(<provider>|<native key>)} with non-primary source kinds namespacing the key as
 * {@code <SOURCE_KIND>|...}, and a stored id is not republished outside replay; raw records of
 * data events are kept only in quarantine by default; usage dedup uses {@code sharedEventID} plus
 * the target when present; {@code dlap.logingest.pipeline} defaults to {@code legacy}.
 *
 * <p>Boundary: this package and every {@code parse} subpackage use JSON and the da-protos contract
 * only. No cloud SDK, no Kafka, no JPA, no web types (enforced by an ArchUnit test).
 */
package com.dlap.logingest.parse.core;
