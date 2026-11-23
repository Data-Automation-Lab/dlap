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

package com.dlap.autodelete.nativerule;

/**
 * Applies a generated rule on its platform. Called only by the hard-delete executor, after a
 * HARD_DELETE approval with at least two distinct approvers passed every rule, and never under
 * dry run. Implementations merge the rule into the platform's existing configuration and never
 * remove a rule DLAP did not write. Implementations live in {@code providers/<cloud>/}.
 */
public interface NativeRuleApplier {

    /** @return the primitive */
    NativeRulePlatform platform();

    /**
     * Applies the rule.
     *
     * @param rule the rule
     * @throws NativeRuleException when the platform refused or could not be reached
     */
    void apply(NativeRule rule);

    /**
     * Removes the rule DLAP wrote, keeping every other rule on the platform (a restore of a rule
     * in force, RET-04, or the end of a rule whose maximum age passed, RET-06). Objects the rule has
     * already expired are gone and are not brought back.
     *
     * <p>The answer says whether every rule of the stored document was found on the platform and
     * taken out. {@code false} means at least one was not found (Q-682): it was removed by someone
     * else, or the platform returns it in a form that no longer matches, in which case it may still
     * be in force. Whatever was found is removed either way.
     *
     * @param rule the rule as it was applied (rebuilt from the stored disposition)
     * @return true when every rule of the document was found and removed
     * @throws NativeRuleException when the platform refused or could not be reached, or the platform
     *                             has no removable container rule
     */
    default boolean remove(NativeRule rule) {
        throw new NativeRuleException(platform() + " has no rule that can be removed", null);
    }

    /**
     * Reads whether the rule's bucket keeps object versions (RET-06, Q-656). Called when the hard
     * delete is planned and again right before the rule is applied. Never throws: an answer the
     * platform did not give is {@link BucketVersioning#UNKNOWN}.
     *
     * @param rule the generated rule
     * @return the bucket's versioning, or {@link BucketVersioning#NOT_APPLICABLE} for a platform
     *         without bucket-level versioning that DLAP checks
     */
    default BucketVersioning versioning(NativeRule rule) {
        return BucketVersioning.NOT_APPLICABLE;
    }
}
