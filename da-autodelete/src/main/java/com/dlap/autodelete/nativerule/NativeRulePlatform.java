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

/**
 * A platform's own lifecycle primitive that DLAP can generate a rule for (decision D20).
 *
 * <p>{@link #containerRule()} tells whether the rule covers a whole bucket, prefix or container and
 * deletes objects on the platform's schedule, so the target itself stays: the first proven child
 * delete shows the rule fires, not that everything is gone (RET-04, Q-631). A BigQuery table
 * expiration deletes the table itself.
 */
public enum NativeRulePlatform {
    /** Amazon S3 lifecycle configuration, an expiration rule on the whole bucket. */
    S3_LIFECYCLE(true),
    /** Google Cloud Storage bucket lifecycle, Delete rules on the whole bucket. */
    GCS_LIFECYCLE(true),
    /** Azure Blob Storage lifecycle management, a delete rule on one container's prefix. */
    AZURE_BLOB_LIFECYCLE(true),
    /** BigQuery table expiration time. */
    BIGQUERY_TABLE_EXPIRATION(false);

    private final boolean containerRule;

    NativeRulePlatform(boolean containerRule) {
        this.containerRule = containerRule;
    }

    /** @return true when the rule covers a bucket, prefix or container whose objects expire one by one */
    public boolean containerRule() {
        return containerRule;
    }

    /**
     * Whether a disposition's platform name is a native rule that covers a bucket, prefix or
     * container. The engine's own deletes ({@code ENGINE_<provider>}) and unknown names are not.
     *
     * @param platformName the platform column of a disposition
     * @return true for a container-level native rule
     */
    public static boolean isContainerRule(String platformName) {
        if (platformName == null) {
            return false;
        }
        for (NativeRulePlatform p : values()) {
            if (p.name().equals(platformName)) {
                return p.containerRule;
            }
        }
        return false;
    }
}
