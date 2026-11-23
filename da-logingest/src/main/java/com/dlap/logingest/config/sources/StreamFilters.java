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

package com.dlap.logingest.config.sources;

import java.util.List;

/**
 * The effective service and operation filters of a stream. An event that fails them is skipped
 * as {@code FILTERED_BY_BINDING}: not stored, not published.
 *
 * @param includeServices   when non-empty, only these services pass
 * @param excludeServices   these services never pass
 * @param includeOperations when non-empty, only these operations pass
 * @param excludeOperations these operations never pass
 */
public record StreamFilters(List<String> includeServices, List<String> excludeServices,
                            List<String> includeOperations, List<String> excludeOperations) {

    /** No filter. */
    public static final StreamFilters NONE = new StreamFilters(List.of(), List.of(), List.of(), List.of());

    /**
     * Freezes the lists.
     *
     * @param includeServices   see the record description
     * @param excludeServices   see the record description
     * @param includeOperations see the record description
     * @param excludeOperations see the record description
     */
    public StreamFilters {
        includeServices = includeServices == null ? List.of() : List.copyOf(includeServices);
        excludeServices = excludeServices == null ? List.of() : List.copyOf(excludeServices);
        includeOperations = includeOperations == null ? List.of() : List.copyOf(includeOperations);
        excludeOperations = excludeOperations == null ? List.of() : List.copyOf(excludeOperations);
    }

    /**
     * Whether an event passes.
     *
     * @param service   the event's service
     * @param operation the event's operation
     * @return true when the event is kept
     */
    public boolean accepts(String service, String operation) {
        if (!includeServices.isEmpty() && !includeServices.contains(service)) {
            return false;
        }
        if (excludeServices.contains(service)) {
            return false;
        }
        if (!includeOperations.isEmpty() && !includeOperations.contains(operation)) {
            return false;
        }
        return !excludeOperations.contains(operation);
    }

    /**
     * Whether any filter is set.
     *
     * @return false for {@link #NONE}
     */
    public boolean isEmpty() {
        return includeServices.isEmpty() && excludeServices.isEmpty()
                && includeOperations.isEmpty() && excludeOperations.isEmpty();
    }
}
