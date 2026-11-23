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

package com.dlap.catalog.retention;

/**
 * Where a retention rule is kept (ADR-0046 decision 7, asset-descriptor spec "Retention > Rule").
 * The declaration order is the tie-break order of the evaluator: when two rules are equally
 * decisive, the earlier source names the deciding rule.
 */
public enum RuleSource {

    /** An operator's rule in the catalog's rule store, written through the API by a named role. */
    MANUAL,

    /** A policy's {@code setRetention} action, recorded by the catalog's action listener. */
    POLICY,

    /** A default per label, from {@code dlap.catalog.retention.label-defaults}. */
    LABEL_DEFAULT,

    /** A provider-native setting that a crawler recorded in {@code technical_metadata}. */
    PROVIDER;

    /**
     * Whether rules of this source are rows of the rule store.
     *
     * @return {@code true} for {@link #MANUAL} and {@link #POLICY}
     */
    public boolean isStored() {
        return this == MANUAL || this == POLICY;
    }
}
