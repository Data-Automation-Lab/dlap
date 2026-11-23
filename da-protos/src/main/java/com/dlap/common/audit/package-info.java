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
 * The tamper-evident audit core (AUD-01; normative spec docs/specs/audit-log.md).
 *
 * <p>Writers append {@link com.dlap.common.audit.AuditEvent}s through
 * {@link com.dlap.common.audit.AuditLogWriter} to {@code dlap_common.audit_log}, one SHA-256
 * hash chain per stream ({@link com.dlap.common.audit.AuditChain}). Verifiers recompute the chain
 * ({@link com.dlap.common.audit.AuditChainVerifier}) and check it against Ed25519-signed
 * checkpoints ({@link com.dlap.common.audit.AuditCheckpoint},
 * {@link com.dlap.common.audit.CheckpointSignatures}). Cloud-neutral: JDK and JDBC only.
 */
package com.dlap.common.audit;
