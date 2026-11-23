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
 * The log contract in code (ADR-0045, docs/specs/log-event.md): the
 * {@link com.dlap.common.logevent.DlapLogEventCodec} that writes one wire
 * encoding and reads both, the idempotency keys in
 * {@link com.dlap.common.logevent.LogEventIds}, and the consumer rules in
 * {@link com.dlap.common.logevent.LogEventSemantics}. Pure Java plus protobuf
 * and Jackson; no cloud SDKs (CLAUDE.md section 5).
 */
package com.dlap.common.logevent;
