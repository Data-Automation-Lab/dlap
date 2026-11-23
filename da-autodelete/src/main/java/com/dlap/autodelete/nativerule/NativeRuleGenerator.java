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

import com.dlap.common.assetid.CanonicalAssetId;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates one platform's native lifecycle rule for an asset. Generation is pure: no cloud call,
 * no credentials; it is what the would-delete list shows and what the applier applies after
 * approval. Implementations live in {@code providers/<cloud>/}.
 */
public interface NativeRuleGenerator {

    /** @return the primitive */
    NativeRulePlatform platform();

    /**
     * Whether an exact native rule exists for the asset: one that deletes the asset and nothing
     * else. A single object has none (a lifecycle prefix filter would also match its siblings).
     *
     * @param asset the canonical asset
     * @return true when {@link #generate} can be called
     */
    boolean supports(CanonicalAssetId asset);

    /**
     * Generates the rule.
     *
     * @param asset     the canonical asset
     * @param ruleKey   a stable key of the delete (the soft-delete record id), from which the rule
     *                  name is derived
     * @param now       the planning time; the rule takes effect at or after it
     * @return the rule
     * @throws IllegalArgumentException when {@link #supports} is false
     */
    NativeRule generate(CanonicalAssetId asset, UUID ruleKey, Instant now);
}
