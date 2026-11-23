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

package com.dlap.autolabel.service;

import java.util.Map;
import java.util.UUID;

/**
 * Drives labeling passes that produce durable {@code LabelingJob} rows so the
 * SmartLabel Agent dashboard reflects real activity.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #runManualLabeling(String, String)} - batch pass over every
 *       catalog asset, triggered by a {@code dlap.labeling.request.event}
 *       message (the manual "trigger labeling" button).</li>
 *   <li>{@link #applyPolicyLabel(UUID, String, String, String, String)} - apply a
 *       single label to one asset in response to a policy engine
 *       {@code addLabel} action on {@code dlap.policies.actions}.</li>
 * </ul>
 */
public interface ILabelingRunService {

    /**
     * Enumerate catalog assets, run the label orchestrator against each, and
     * persist one {@code LabelingJob} row capturing the real processed /
     * succeeded / failed counts and the applied-label summary.
     *
     * @param requestId  correlation id from the labeling-request event.
     * @param triggeredBy user or system principal that triggered the run.
     * @return the persisted job id.
     */
    String runManualLabeling(String requestId, String triggeredBy);

    /**
     * Apply a single policy-driven label to one catalog asset and record a
     * {@code LabelingJob} row (source {@code POLICY_ENGINE}).
     *
     * @param assetId   catalog asset id the policy targeted.
     * @param labelName the label the policy asked to add.
     * @param policyId  originating policy id (nullable, for job naming).
     * @param policyName originating policy name (nullable, for job naming).
     * @param fireId    the fire id of the policy action (nullable). A fire already applied is not applied
     *                  or recorded a second time: the job of the first delivery is returned (IDEM-01, Q-856).
     * @return the persisted job id, or the id of the job that already recorded this fire.
     */
    String applyPolicyLabel(UUID assetId, String labelName, String policyId, String policyName, String fireId);

    /**
     * Summary of a completed manual labeling run, exposed for logging/metrics.
     */
    record ManualRunResult(String jobId, int processed, int succeeded, int failed, Map<String, Integer> labelSummary) {}
}
