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

package com.dlap.common.audit;

/**
 * The outcome an audit record states (docs/specs/audit-log.md, section "Record"). A destructive
 * action is written twice: {@link #REQUESTED} before the provider call and one of the final
 * outcomes after it, linked by the correlation id.
 */
public enum AuditOutcome {
    /** The action was asked for or is about to start; no effect is claimed yet. */
    REQUESTED,
    /** The action took effect. */
    SUCCEEDED,
    /** The action was attempted and failed. */
    FAILED,
    /** The action was refused by a policy, an approval rule or an authorization check. */
    DENIED
}
