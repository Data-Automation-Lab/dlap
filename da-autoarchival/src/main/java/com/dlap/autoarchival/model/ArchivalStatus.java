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

package com.dlap.autoarchival.model;

public enum ArchivalStatus {
    PENDING_APPROVAL,
    APPROVED, // Approved, ready for processing or being processed
    REJECTED,
    SUBMITTED, // Submitted directly, no approval needed, or post-approval
    IN_PROGRESS,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    CANCELLED,
    RESTORATION_REQUESTED,
    RESTORATION_IN_PROGRESS,
    RESTORED, // Assets are available
    RESTORATION_FAILED,
    /** RET-03: the native lifecycle rule is in place at the provider; nothing has transitioned yet. */
    RULE_APPLIED,
    /** RET-03: DLAP has no API call for this provider's rule; the rule document is ready to apply. */
    RULE_RENDERED,
    /** RET-03: a provider log event proved the transition. Set only from provider evidence. */
    EXECUTED,
    /** RET-03: the executor refused the action (no executable approval, unsafe location, or a limit). */
    REFUSED,
    /** RET-03: the approval expired before anyone decided it. */
    EXPIRED,
    /**
     * RET-09 (Q-708): a restore took the rule out of every asset of the task and objects had already moved, so
     * nothing was restored. A normal end state, not a failure; the per-asset RULE_REMOVED status and message say why.
     */
    RULE_REMOVED,
    /**
     * RET-09 (Q-711): every asset of the task is already covered by an identical live GCS row, so there was
     * nothing to plan. Not a refusal and not a failure.
     */
    ALREADY_HELD
} 