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
 * The {@code DLAP_NORMALIZED} pass-through family (design B1): records that arrive already in
 * DLAP's normalized shape, as {@code IngestRequestDTO} JSON, through REST ingest and fixture
 * replay. On the registry pipeline they take the same parse path as provider logs, so they get a
 * binding, a source format, an origin, a parse status and the frozen dedup rule.
 *
 * <p>The frozen registry ties every source kind to one provider (ADR-0054 decision 2), and a
 * normalized record can carry any provider, so the family registers one source kind per provider:
 * {@code DLAP_NORMALIZED_AWS}, {@code DLAP_NORMALIZED_GCP} and so on (ADR-0060). Events keep the
 * {@code event_id} the legacy pipeline computed for the same record.
 */
package com.dlap.logingest.parse.dlapnormalized;
