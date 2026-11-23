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

package com.dlap.policyengine.approval;

/**
 * What one person decided on one request (docs/specs/approvals.md, "Decisions"). Each person decides
 * a request at most once; the row is immutable.
 */
public enum DecisionKind {

    /** Counts toward the quorum. */
    APPROVE,
    /** Vetoes the request: one rejection by an eligible approver ends it. */
    REJECT,
    /** An administrator's release without the quorum; needs a justification and raises an alert. */
    BREAK_GLASS
}
