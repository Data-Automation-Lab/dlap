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

import java.util.List;

/**
 * One cloud's native lifecycle rule: the storage service's own mechanism that moves objects to a
 * colder class on a schedule the provider runs (S3 lifecycle transition, GCS lifecycle
 * {@code SetStorageClass}, Azure management policy tiering).
 *
 * <p>DLAP generates the rule, writes it next to the rules the bucket already has (never replacing
 * them), reads it back, and then waits for the provider's own log event to say the transition
 * happened. Implementations live under {@code com.dlap.autoarchival.providers.<cloud>} and are the
 * only code that touches a cloud SDK (CLAUDE.md section 9).
 *
 * <p>Implementations are thread-safe. They never log credentials, SAS tokens or object content.
 */
public interface NativeRuleProvider {

    /**
     * What a rule asks the provider to do.
     *
     * @param ruleId      the rule's stable id, {@code dlap-archive-<12 hex>}
     * @param scope       the bucket and prefix it covers
     * @param nativeClass the provider's own storage class name
     * @param afterDays   the age in days after which objects move
     */
    record RuleSpec(String ruleId, RuleScope scope, String nativeClass, int afterDays) {
    }

    /**
     * The rules a bucket already has.
     *
     * @param totalRules    how many lifecycle rules the bucket holds; -1 when the provider cannot be asked
     * @param providerMax   the provider's maximum rules per bucket (or per account policy)
     * @param specPresent   whether an equal rule is already in place (a repeated apply is a no-op)
     */
    record Inventory(int totalRules, int providerMax, boolean specPresent) {
    }

    /**
     * One object a rule would touch.
     *
     * @param key          the object key
     * @param sizeBytes    its size
     * @param storageClass its current storage class, or empty when the listing does not show it
     */
    record ListingEntry(String key, long sizeBytes, String storageClass) {
    }

    /**
     * A bounded dry-run listing.
     *
     * @param entries     at most {@code limit} objects under the prefix, in the provider's order
     * @param truncated   whether the prefix holds more objects than {@code entries}
     * @param limit       the bound that was applied
     * @param bytesListed the total size of {@code entries}
     * @param available   false when the provider could not list (no client configured); then
     *                    {@code entries} is empty and means nothing
     * @param note        a sentence on how to read the listing
     */
    record Listing(List<ListingEntry> entries, boolean truncated, int limit, long bytesListed,
            boolean available, String note) {
    }

    /**
     * What an apply did.
     *
     * @param alreadyPresent whether the same rule was in place and nothing was written
     * @param rulesBefore    the bucket's rule count before
     * @param rulesAfter     the bucket's rule count after, read back from the provider
     */
    record ApplyResult(boolean alreadyPresent, int rulesBefore, int rulesAfter) {
    }

    /**
     * The provider this implementation serves.
     *
     * @return {@code AWS}, {@code GCP} or {@code AZURE}
     */
    String provider();

    /**
     * Whether {@link #apply} writes the rule. False means DLAP renders the rule document and a person
     * or a pipeline applies it.
     *
     * @return true when DLAP can apply the rule through the provider's API
     */
    boolean canApply();

    /**
     * Whether calls are mocked (development and CI without credentials). A mocked apply writes
     * nothing at the provider, and the executor records that on the task.
     *
     * @return true when {@link #apply} does not touch the provider
     */
    default boolean isMock() {
        return false;
    }

    /**
     * Reads the bucket's current rules.
     *
     * @param spec the rule to look for
     * @return the inventory
     * @throws RuleException {@code PROVIDER_ERROR} when the provider refuses or cannot be reached
     */
    Inventory inventory(RuleSpec spec);

    /**
     * Lists up to {@code maxKeys} objects under the scope's prefix, read only.
     *
     * @param scope   the scope
     * @param maxKeys the bound
     * @return the listing
     * @throws RuleException {@code PROVIDER_ERROR} when the provider refuses or cannot be reached
     */
    Listing list(RuleScope scope, int maxKeys);

    /**
     * Renders the rule as the provider's own document (the JSON a person would hand to the CLI).
     *
     * @param spec the rule
     * @return pretty JSON; contains no secret
     */
    String render(RuleSpec spec);

    /**
     * Adds the rule to the bucket's rules and verifies it by reading the rules back. Existing rules
     * are kept; an equal rule is not added twice.
     *
     * @param spec the rule
     * @return what happened
     * @throws RuleException {@code PROVIDER_ERROR}, {@code VERIFY_FAILED} or {@code APPLY_NOT_SUPPORTED}
     */
    ApplyResult apply(RuleSpec spec);

    /**
     * Removes the rule, keeping every other rule.
     *
     * @param spec the rule
     * @return true when the rule was found and removed
     * @throws RuleException {@code PROVIDER_ERROR}, {@code VERIFY_FAILED} or {@code APPLY_NOT_SUPPORTED}
     */
    boolean remove(RuleSpec spec);
}
