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
 * The result of planning a native archive rule: what would be written, what the bucket holds
 * today, and a bounded list of the objects it would move. Read only; building a plan changes
 * nothing at the provider.
 *
 * @param spec      the rule
 * @param inventory the bucket's rules today
 * @param listing   the dry-run listing
 * @param document  the rule as the provider's own JSON
 * @param applyable whether DLAP can write this provider's rule through its API
 * @param scopeKind {@code BUCKET}, {@code PREFIX} or {@code OBJECT}
 * @param notes     things a reader should know before approving
 */
public record RulePlan(
        NativeRuleProvider.RuleSpec spec,
        NativeRuleProvider.Inventory inventory,
        NativeRuleProvider.Listing listing,
        String document,
        boolean applyable,
        String scopeKind,
        List<String> notes) {
}
