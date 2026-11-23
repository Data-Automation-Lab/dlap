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

package com.dlap.policyengine.projection;

/**
 * One platform-native access-control intent: a single
 * {@code (principal, privilege, securable)} tuple plus whether it should be
 * granted or revoked. The cloud-neutral currency between the pure
 * {@link AccessPolicyToGrantTranslator} and any {@link PolicyProjectionSink}.
 *
 * <p>This is DLAP's abstract shape, deliberately platform-agnostic: a Unity
 * Catalog sink renders it to {@code GRANT SELECT ON TABLE ... TO `role`}, a
 * BigQuery sink to an IAM policy binding, and so on. ADR-0040 push-down mode is
 * detection-grade/best-effort - the AuthZEN PDP stays source of truth.
 *
 * @param principal  the subject the grant targets (a realm role / persona-tier
 *                   token, verbatim from the AccessPolicy subjects)
 * @param privilege  the platform-neutral privilege verb derived from an
 *                   AccessPolicy action (e.g. {@code SELECT})
 * @param securable  the object the privilege is over (a resolved asset URI, or a
 *                   label-selector token when no concrete asset was resolved)
 * @param grant      {@code true} → grant this tuple; {@code false} → revoke it
 *                   (an AccessPolicy {@code effect: deny} projects as a revoke)
 */
public record GrantIntent(
        String principal,
        String privilege,
        String securable,
        boolean grant) {

    public GrantIntent {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("principal is required");
        }
        if (privilege == null || privilege.isBlank()) {
            throw new IllegalArgumentException("privilege is required");
        }
        if (securable == null || securable.isBlank()) {
            throw new IllegalArgumentException("securable is required");
        }
    }

    /** @return a grant tuple. */
    public static GrantIntent grant(String principal, String privilege, String securable) {
        return new GrantIntent(principal, privilege, securable, true);
    }

    /** @return a revoke tuple. */
    public static GrantIntent revoke(String principal, String privilege, String securable) {
        return new GrantIntent(principal, privilege, securable, false);
    }
}
