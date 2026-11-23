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
 * Whether a bucket keeps object versions (RET-06, Q-656). An S3 expiration on a versioned bucket only
 * adds delete markers, and a delete marker is an UPDATE, not a DELETE (ADR-0104), so the rule could
 * never be proven from the provider's log. Until a live capture settles how such a rule is proven, a
 * native delete rule on a versioned bucket is refused ({@code VERSIONED_BUCKET_UNSUPPORTED}).
 */
public enum BucketVersioning {
    /** Versioning was never turned on. */
    UNVERSIONED,
    /** Versioning is on (S3 {@code Enabled}, GCS {@code versioning.enabled}, Azure {@code isVersioningEnabled}). */
    ENABLED,
    /** Versioning was turned on and is now suspended (S3 only); old versions and delete markers stay. */
    SUSPENDED,
    /** The platform has no bucket-level versioning DLAP checks (BigQuery tables). */
    NOT_APPLICABLE,
    /** The platform could not be asked; a rule is never applied on an unknown answer. */
    UNKNOWN;

    /** @return true when the bucket keeps versions now or kept them before (Enabled or Suspended) */
    public boolean versioned() {
        return this == ENABLED || this == SUSPENDED;
    }
}
