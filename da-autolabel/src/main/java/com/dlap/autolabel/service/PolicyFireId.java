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

package com.dlap.autolabel.service;

/**
 * Normalisation of the {@code fireId} of a policy action, the idempotency key of
 * {@code dlap.policies.actions} (ADR-0135). It mirrors da-reporting's {@code fireIdOf}: the value is trimmed,
 * a blank one is no key at all, and one that does not fit the {@code fire_id} column is refused (Q-890).
 */
public final class PolicyFireId {

    /** Length of {@code dlap_autolabel_job.fire_id} (autolabel Flyway V2). */
    public static final int MAX_LENGTH = 255;

    private PolicyFireId() {
    }

    /**
     * Trims a received fireId and treats a blank one as absent.
     *
     * @param raw the fireId as received, may be null
     * @return the trimmed text, or null when {@code raw} is null or blank
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Whether a normalised fireId cannot be stored in the {@code fire_id} column.
     *
     * @param fireId a value returned by {@link #normalize(String)}, may be null
     * @return true when it is longer than {@link #MAX_LENGTH}
     */
    public static boolean exceedsMaxLength(String fireId) {
        return fireId != null && fireId.length() > MAX_LENGTH;
    }
}
