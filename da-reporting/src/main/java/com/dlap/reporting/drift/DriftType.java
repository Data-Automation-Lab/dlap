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

package com.dlap.reporting.drift;

/**
 * Kinds of decision&#8596;access-log drift the Consumed-stage telemetry loop
 * detects (ADR-0040, enforcement-contract.md &#167;6).
 */
public enum DriftType {

    /**
     * An actual read of an asset by a principal with <b>no</b> corresponding
     * ALLOW decision in the correlation window. The gap ADR-0040 exists to
     * close: data was consumed without the PDP ever authorizing it (no PEP,
     * or a PEP that never called the evaluation endpoint).
     */
    UNENFORCED_READ,

    /**
     * A DENY decision followed by an actual read of the same asset by the same
     * principal inside the window. The PDP said no and the data was read
     * anyway - an enforcement bypass.
     */
    ENFORCEMENT_BYPASS
}
