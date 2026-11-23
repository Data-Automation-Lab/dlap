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

package com.dlap.autodelete.service;

import java.util.Map;
import java.util.UUID;

/**
 * The delete lifecycle (RET-02):
 *
 * <ul>
 *   <li>{@link #queueSoftDelete}: an approved policy delete enters its soft-delete window after the
 *       executor rules of docs/specs/approvals.md section 9 pass; nothing in the cloud is touched.</li>
 *   <li>{@link #restore}: an operator pulls the asset back while nothing has been touched.</li>
 *   <li>{@link #processDueHardDeletes}: the sweeper asks for a HARD_DELETE approval when a window
 *       ends, and once two distinct approvers released it and every rule passes again, applies the
 *       platform's native lifecycle rule (or, for a single object, the engine delete), or under dry
 *       run records the plan. A row becomes HARD_DELETED only when the provider's own log event
 *       proves the deletion.</li>
 * </ul>
 */
public interface IDeletionService {

    /**
     * Admits an approved policy delete into its soft-delete window, or refuses it.
     *
     * @param policyEvent the decoded {@code PolicyActionRequest}
     * @return the soft-delete record id, or null when a rule refused the delete (audited)
     */
    UUID queueSoftDelete(Map<String, Object> policyEvent);

    /**
     * Restores an asset whose delete has not touched the cloud yet, or whose bucket, prefix or
     * container rule was applied (in force, or still waiting for its proof): the restore then removes
     * the rule; objects it already expired are gone. When DLAP's rule is not found on the platform the
     * record ends {@code RESTORE_FAILED} (RET-06, Q-682). A restore of a record that waits for a
     * hard-delete approval also withdraws that approval through the policy engine's service-account
     * withdraw operation, on behalf of the restorer (Q-621, Q-676). A restore the service refuses is
     * audited.
     *
     * @param softDeleteRecordId the record
     * @param actorSubject       the JWT subject of the person restoring
     * @param notes              optional notes
     * @return what happened
     */
    RestoreOutcome restore(UUID softDeleteRecordId, String actorSubject, String notes);

    /**
     * Records a policy dry run of a delete: nothing is executed, and the audit log says so (Q-632).
     *
     * @param policyEvent the decoded {@code PolicyActionRequest} with {@code dryRun: true}
     */
    void recordPolicyDryRun(Map<String, Object> policyEvent);

    /**
     * Runs one sweep now.
     *
     * @return the number of rows and tasks handled
     */
    int processDueHardDeletes();
}
