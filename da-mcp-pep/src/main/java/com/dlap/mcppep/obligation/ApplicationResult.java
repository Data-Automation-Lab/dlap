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

package com.dlap.mcppep.obligation;

import java.util.List;

/**
 * The outcome of applying a set of obligations to a {@link LabeledDataset}.
 *
 * <p>Per the enforcement contract (docs/specs/enforcement-contract.md §3),
 * a PEP that cannot honor a returned obligation MUST treat the decision as
 * denied - <em>fail-closed on obligations</em>. When {@code satisfied} is
 * {@code false}, {@code result} is {@code null} and the caller MUST withhold
 * the data (deny), naming {@code unsatisfiedType} in its reason.
 *
 * @param satisfied       true when every obligation was honored; false =
 *                        fail-closed (an obligation type could not be applied)
 * @param unsatisfiedType the offending obligation type when fail-closed,
 *                        otherwise {@code null}
 * @param result          the transformed dataset when satisfied, otherwise
 *                        {@code null} (never return partially-transformed data)
 * @param appliedTypes    the obligation types honored, in application order
 *                        (never null)
 */
public record ApplicationResult(
        boolean satisfied,
        String unsatisfiedType,
        LabeledDataset result,
        List<String> appliedTypes) {

    /** Normalizes {@code appliedTypes} to a non-null immutable list. */
    public ApplicationResult {
        appliedTypes = (appliedTypes == null) ? List.of() : List.copyOf(appliedTypes);
    }

    /**
     * A satisfied result carrying the transformed data.
     *
     * @param result       the transformed dataset
     * @param appliedTypes the obligation types honored, in order
     * @return a satisfied result
     */
    public static ApplicationResult ok(LabeledDataset result, List<String> appliedTypes) {
        return new ApplicationResult(true, null, result, appliedTypes);
    }

    /**
     * A fail-closed result: the named obligation type could not be honored,
     * so no data is returned and the caller must deny.
     *
     * @param type the unsatisfiable obligation type
     * @return a fail-closed result
     */
    public static ApplicationResult failClosed(String type) {
        return new ApplicationResult(false, type, null, List.of());
    }
}
