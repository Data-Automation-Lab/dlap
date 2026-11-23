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

/**
 * The result of a restore (RET-04).
 *
 * @param restored           true when the record is now {@code RESTORED}
 * @param reason             why it was not restored (a machine reason such as {@code record-not-found}), or null
 * @param ruleRemoved        true when the restore removed a native lifecycle rule (a record that was
 *                           {@code RULE_IN_FORCE})
 * @param approvalWithdrawal what became of the pending {@code HARD_DELETE} approval:
 *                           {@code NOT_ATTEMPTED}, {@code WITHDRAWN}, {@code APPROVAL_ALREADY_DECIDED:<status>},
 *                           {@code ENDPOINT_NOT_AVAILABLE:<http status>}, {@code REQUEST_NOT_FOUND},
 *                           {@code REFUSED:<reason>} or
 *                           {@code UNREACHABLE:<detail>}
 * @param message            a sentence for the person, or null
 * @param recordStatus       the record's state after the call: {@code RESTORED}, {@code RESTORE_FAILED}
 *                           (DLAP's rule was not found, RET-06), or null when the record was not changed
 */
public record RestoreOutcome(boolean restored, String reason, boolean ruleRemoved, String approvalWithdrawal,
        String message, String recordStatus) {

    /** The sentence of a restore whose rule was not found (RET-06, Q-682). */
    public static final String RULE_NOT_FOUND_MESSAGE = "DLAP's lifecycle rule was not found on the platform, so "
            + "nothing was removed and the record is RESTORE_FAILED. The rule was removed by someone else, or it is "
            + "there in another form and may still delete objects: check the bucket's lifecycle configuration.";

    /** The sentence that follows the removal of a rule in force. */
    public static final String EXPIRED_OBJECTS_ARE_GONE = "The lifecycle rule was removed. Objects it had already "
            + "expired are gone and are not restored.";

    /**
     * A restore that did not happen.
     *
     * @param reason the machine reason
     * @param message a sentence
     * @return the outcome
     */
    public static RestoreOutcome notRestored(String reason, String message) {
        return new RestoreOutcome(false, reason, false, "NOT_ATTEMPTED", message, null);
    }

    /**
     * A restore that happened.
     *
     * @param ruleRemoved        whether a native rule was removed
     * @param approvalWithdrawal what became of the pending approval
     * @return the outcome
     */
    public static RestoreOutcome done(boolean ruleRemoved, String approvalWithdrawal) {
        return new RestoreOutcome(true, null, ruleRemoved, approvalWithdrawal,
                ruleRemoved ? EXPIRED_OBJECTS_ARE_GONE : null, "RESTORED");
    }

    /**
     * A restore that found no rule to remove (RET-06, Q-682): the record ends {@code RESTORE_FAILED}.
     *
     * @param reason the machine reason ({@code rule-not-found})
     * @return the outcome
     */
    public static RestoreOutcome ruleNotFound(String reason) {
        return new RestoreOutcome(false, reason, false, "NOT_ATTEMPTED", RULE_NOT_FOUND_MESSAGE, "RESTORE_FAILED");
    }
}
