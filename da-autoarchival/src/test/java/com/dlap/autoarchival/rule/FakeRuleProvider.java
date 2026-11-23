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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * An in-memory {@link NativeRuleProvider} for tests: a bucket's rules are a list of rule ids, the
 * listing is generated, and every call is recorded in order so a test can assert that the audit
 * intent came before the write.
 */
public class FakeRuleProvider implements NativeRuleProvider {

    private final String provider;
    private final boolean canApply;
    /** Every call in order, for example {@code apply:dlap-archive-x}. */
    public final List<String> calls = new ArrayList<>();
    /** The rule ids the bucket holds. */
    public final List<String> rules = new ArrayList<>();
    /** Rules that exist but are not DLAP's, counted against the provider maximum. */
    public int foreignRules;
    /** The maximum the fake reports. */
    public int providerMax = 100;
    /** How many objects exist under any prefix. */
    public int objectCount = 3;
    /** When set, {@link #apply} throws it. */
    public RuntimeException applyFailure;
    /** Whether calls are mocked. */
    public boolean mock;
    /** When set, runs at the start of {@link #apply}: a slow worker, during which something else changes the row. */
    public Runnable duringApply;

    /**
     * Creates the fake.
     *
     * @param provider the provider name
     * @param canApply whether it can write rules
     */
    public FakeRuleProvider(String provider, boolean canApply) {
        this.provider = provider;
        this.canApply = canApply;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public boolean canApply() {
        return canApply;
    }

    @Override
    public boolean isMock() {
        return mock;
    }

    @Override
    public Inventory inventory(RuleSpec spec) {
        calls.add("inventory");
        return new Inventory(rules.size() + foreignRules, providerMax, rules.contains(spec.ruleId()));
    }

    @Override
    public Listing list(RuleScope scope, int maxKeys) {
        calls.add("list:" + maxKeys);
        int n = Math.min(objectCount, maxKeys);
        List<ListingEntry> entries = IntStream.range(0, n)
                .mapToObj(i -> new ListingEntry(scope.prefix() + "obj-" + i, 10L, "STANDARD")).toList();
        return new Listing(entries, objectCount > maxKeys, maxKeys, 10L * n, true, "fake");
    }

    @Override
    public String render(RuleSpec spec) {
        return "{\"rule\":\"" + spec.ruleId() + "\",\"class\":\"" + spec.nativeClass() + "\"}";
    }

    @Override
    public ApplyResult apply(RuleSpec spec) {
        calls.add("apply:" + spec.ruleId());
        if (duringApply != null) {
            duringApply.run();
        }
        if (!canApply) {
            throw new RuleException(RuleException.Reason.APPLY_NOT_SUPPORTED, "render only");
        }
        if (applyFailure != null) {
            throw applyFailure;
        }
        int before = rules.size() + foreignRules;
        if (rules.contains(spec.ruleId())) {
            return new ApplyResult(true, before, before);
        }
        rules.add(spec.ruleId());
        return new ApplyResult(false, before, before + 1);
    }

    @Override
    public boolean remove(RuleSpec spec) {
        calls.add("remove:" + spec.ruleId());
        return rules.remove(spec.ruleId());
    }
}
