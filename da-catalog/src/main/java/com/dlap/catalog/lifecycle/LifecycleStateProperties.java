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

package com.dlap.catalog.lifecycle;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the Lifecycle State emitter (LSE-01), prefix {@code dlap.catalog.lifecycle-state}.
 */
@ConfigurationProperties(prefix = "dlap.catalog.lifecycle-state")
public class LifecycleStateProperties {

    /** Upper bound of the {@code size} of one page of the list endpoint. */
    public static final int MAX_PAGE_SIZE = 500;

    /**
     * Principal ids ({@code principal.id} of the Log Event) of this engine's own executors. A
     * DELETE or ARCHIVE event whose principal is one of them is a disposition with
     * {@code executedBy: engine}; one whose principal type is {@code SYSTEM} and that carries the
     * provider's lifecycle signature ({@link LifecycleSignature}) is {@code native-rule}; any other
     * actor has no value in 0.1, so its disposition is null
     * (spec section 3.7). Empty by default: no event is attributed to the engine until the
     * operator names its principals.
     */
    private List<String> enginePrincipals = new ArrayList<>();

    /**
     * Most event ids kept per asset and field. When more applied events carry the same value (for
     * example every crawl reporting the same creation time), the lowest ids are kept, which is the
     * same set for every engine given the same events.
     */
    private int maxEventIdsPerField = 100;

    /** Default page size of the list endpoint. */
    private int defaultPageSize = 100;

    /**
     * The configured engine principals.
     *
     * @return the principal ids
     */
    public List<String> getEnginePrincipals() {
        return enginePrincipals;
    }

    /**
     * Sets the engine principals.
     *
     * @param enginePrincipals the principal ids
     */
    public void setEnginePrincipals(List<String> enginePrincipals) {
        this.enginePrincipals = enginePrincipals == null ? new ArrayList<>() : new ArrayList<>(enginePrincipals);
    }

    /**
     * The cap on event ids per asset and field.
     *
     * @return at least 1
     */
    public int getMaxEventIdsPerField() {
        return maxEventIdsPerField;
    }

    /**
     * Sets the cap on event ids per asset and field.
     *
     * @param maxEventIdsPerField at least 1
     * @throws IllegalArgumentException when below 1
     */
    public void setMaxEventIdsPerField(int maxEventIdsPerField) {
        if (maxEventIdsPerField < 1) {
            throw new IllegalArgumentException("dlap.catalog.lifecycle-state.max-event-ids-per-field must be at least 1");
        }
        this.maxEventIdsPerField = maxEventIdsPerField;
    }

    /**
     * The default page size.
     *
     * @return 1 to {@value #MAX_PAGE_SIZE}
     */
    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    /**
     * Sets the default page size.
     *
     * @param defaultPageSize 1 to {@value #MAX_PAGE_SIZE}
     * @throws IllegalArgumentException when out of range
     */
    public void setDefaultPageSize(int defaultPageSize) {
        if (defaultPageSize < 1 || defaultPageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("dlap.catalog.lifecycle-state.default-page-size must be 1 to "
                    + MAX_PAGE_SIZE);
        }
        this.defaultPageSize = defaultPageSize;
    }
}
