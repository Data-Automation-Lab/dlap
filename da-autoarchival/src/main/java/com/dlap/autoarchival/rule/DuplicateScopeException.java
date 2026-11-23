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

package com.dlap.autoarchival.rule;

import com.dlap.autoarchival.executor.ArchiveRuleEntity;

/**
 * A live DLAP row already holds the identical GCS scope, class and age (Q-673, Q-711).
 *
 * <p>It is a {@link RuleException} with {@link RuleException.Reason#DUPLICATE_SCOPE}, so the dry run and the
 * pre-approval check still answer 409. A caller that submits a task (a policy fire or an operator request)
 * treats it as "already done" instead of a refusal, and uses the facts of the holding row carried here.
 */
public class DuplicateScopeException extends RuleException {

    private static final long serialVersionUID = 1L;

    private final String existingRuleId;
    private final String existingStatus;
    private final String existingApprovalId;
    private final long existingCreatedAtEpochMilli;

    /**
     * Creates the exception from the row that holds the scope.
     *
     * @param message  the description, naming the row
     * @param existing the live row that holds the scope
     */
    public DuplicateScopeException(String message, ArchiveRuleEntity existing) {
        super(Reason.DUPLICATE_SCOPE, message);
        this.existingRuleId = existing.getRuleId();
        this.existingStatus = existing.getStatus();
        this.existingApprovalId = existing.getApprovalId();
        this.existingCreatedAtEpochMilli = existing.getCreatedAt().toEpochMilli();
    }

    /**
     * Returns the rule id of the row that holds the scope.
     *
     * @return the rule id
     */
    public String existingRuleId() {
        return existingRuleId;
    }

    /**
     * Returns the status the holding row had when the check ran.
     *
     * @return the status
     */
    public String existingStatus() {
        return existingStatus;
    }

    /**
     * Returns the approval id of the holding row.
     *
     * @return the approval id
     */
    public String existingApprovalId() {
        return existingApprovalId;
    }

    /**
     * Returns the creation time of the holding row, as epoch milliseconds.
     *
     * @return the creation time
     */
    public long existingCreatedAtEpochMilli() {
        return existingCreatedAtEpochMilli;
    }
}
