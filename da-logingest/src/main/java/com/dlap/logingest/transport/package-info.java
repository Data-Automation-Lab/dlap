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
 * The transport layer of the configuration-driven log layer (design B5, B6, ADR-0060).
 *
 * <p>A {@link com.dlap.logingest.transport.LogTransport} pulls raw records of one stream: an API
 * page, a trail file, a sink shard. Transports self-register as beans and are found by their
 * transport id; SDK-backed transports live under {@code providers/<cloud>/} (CLAUDE.md section 5).
 * {@link com.dlap.logingest.transport.StreamRunner} runs one poll of a stream under its advisory
 * lock, feeds the records to the parser registry, checkpoints processed objects and moves the
 * stream cursor only after a batch reached its outcomes. ADR-0023 holds: transports pull; there is
 * no Pub/Sub, Event Hubs or S3 notification transport in 0.x.
 */
package com.dlap.logingest.transport;
