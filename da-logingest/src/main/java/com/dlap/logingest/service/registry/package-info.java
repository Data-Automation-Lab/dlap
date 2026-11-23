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
 * The registry-fed ingest path (ADR-0045 decision 8, ADR-0060): records from transports, REST
 * ingest and fixture replay are parsed by the parser registry with their binding's effective
 * settings, stored once in {@code log_events} and published to {@code dlap.log.event.normalized}
 * unless their {@code event_id} is already stored. Active only with
 * {@code dlap.logingest.pipeline=registry}.
 */
package com.dlap.logingest.service.registry;
