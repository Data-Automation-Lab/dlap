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

package com.dlap.policyengine.model;

/**
 * How a stored policy row is evaluated (POL-04, ADR-0136). It is the discriminator that tells a row
 * that never had a document apart from a row whose document went missing, so the PDP can fail
 * closed on the second without touching the first (Q-871).
 */
public enum PolicyKind {
    /** A pre-DSL row that carried MVEL rules (retired by ADR-0057). It never has a document. */
    LEGACY_RULE,
    /**
     * A row whose behaviour is its dlap/v1 document. An ENABLED row of this kind with a null or
     * blank document is an invalid deny with no readable scope.
     */
    DSL
}
