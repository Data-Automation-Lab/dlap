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
 * Why a record was skipped. A skipped record is valid and understood but yields no event; it is
 * counted, never quarantined and never silently dropped (ADR-0045 decision 7).
 */
public enum SkipReason {

    /**
     * The record has no provider clock (and no documented fallback clock). DLAP never stamps
     * business time with its own clock (ADR-0045 decision 3).
     */
    MISSING_EVENT_TIME,

    /**
     * The record is a known shape that is not an activity event: a CloudTrail digest or Insight
     * summary, an aggregated record, a heartbeat. Declared per family ({@code skip-when}).
     */
    NOT_AN_EVENT,

    /** The binding's include or exclude filters leave the record out. */
    FILTERED_BY_BINDING
}
