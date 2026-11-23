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

package com.dlap.policyengine.filter;

/**
 * Messages and names for the retired MVEL rule conditions (SEC-04, ADR-0057).
 *
 * <p>Policy {@code rules[]} and subscription {@code rules[]} carried MVEL expressions, which
 * are executable code. MVEL is no longer on the classpath. Flyway V5 copied every stored
 * condition to {@code retired_mvel_rules}, disabled the policies and subscriptions that
 * used them and emptied {@code policy_rules} and {@code event_rules}. A request that still
 * sends a rule is refused with HTTP 400 and one of the messages below.
 */
public final class RetiredRules {

    /** The archive table V5 created. */
    public static final String ARCHIVE_TABLE = "retired_mvel_rules";

    /** Why a policy {@code rules[]} entry is refused. */
    public static final String POLICY_RULES_MESSAGE =
            "Policy rules[] with MVEL conditions were retired (ADR-0057). "
            + "Express the policy as a dlap/v1 DSL document in policyDocument.";

    /** Why a subscription {@code rules[]} entry is refused. */
    public static final String SUBSCRIPTION_RULES_MESSAGE =
            "Subscription rules[] with MVEL conditions were retired (ADR-0057). "
            + "Use the typed filters eventTypes, severities, sourceServices, assetPrefixes and labels.";

    private RetiredRules() {
    }
}
