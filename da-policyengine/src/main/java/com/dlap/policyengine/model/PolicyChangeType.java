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
 * What made a policy version (AUD-02a). Each change of a policy adds exactly one version.
 */
public enum PolicyChangeType {

    /**
     * The state of a policy created before policy history existed (Flyway V9), recorded by the system
     * just before that policy's first change, so the history starts from what was in force.
     */
    BASELINE,

    /** The policy was created. */
    CREATED,

    /** The policy's content (name, description, document, actions, status) was replaced. */
    UPDATED,

    /** Only the status (ENABLED or DISABLED) changed. */
    STATUS_CHANGED,

    /** The policy was deleted (soft delete: disabled and frozen); no version follows it. */
    DELETED;

    /**
     * The audit event type of this change in the stream {@code govern.policy}.
     *
     * @return for example {@code govern.policy.status_changed}
     */
    public String eventType() {
        return "govern.policy." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
