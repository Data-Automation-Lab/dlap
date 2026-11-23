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

package com.dlap.adminservice.permissioncheck;

import java.util.List;

/**
 * The self-check's answer for one cloud: the permissions the Actor roles miss and the ones they
 * hold beyond the committed trace, per resource.
 *
 * @param cloud    the cloud
 * @param status   {@code PASS}, {@code FAIL} (something missing or extra), {@code ERROR} (a resource
 *                 could not be probed) or {@code NOT_CONFIGURED} (no resource is configured)
 * @param method   the provider API that answered
 * @param identityChecked the identity whose permissions were read: the role ARN (AWS) or a plain
 *                 description of the calling identity (GCP, Azure); several are joined with a comma;
 *                 {@code null} when nothing is configured
 * @param identityBasis whether {@code identityChecked} was named by the operator ({@code NAMED_ROLE}) or is
 *                 whoever made the call ({@code CALLER}), in which case it is the Actor only when
 *                 da-admin-service runs as the Actor (Q-727)
 * @param identityNote one sentence on what the answer is about, so a PASS is never read as a statement about
 *                 an identity that was not asked
 * @param missing  every missing permission, as {@code <executor>: <permission> on <resource>}
 * @param extra    every extra permission, in the same form
 * @param targets  the answer per resource
 */
public record PermissionCheckReport(String cloud, Status status, String method, String identityChecked,
        IdentityBasis identityBasis, String identityNote, List<String> missing, List<String> extra,
        List<TargetReport> targets) {

    /** Whose permissions a probe reads. */
    public enum IdentityBasis {
        /** The provider is asked about an identity the operator named, whoever makes the call (AWS role ARN). */
        NAMED_ROLE,
        /** The provider answers for the identity that makes the call (GCP, Azure); nothing is impersonated. */
        CALLER
    }

    /** The outcome for a cloud. */
    public enum Status {
        /** Every probed resource holds exactly the trace's permissions. */
        PASS,
        /** At least one permission is missing or extra. */
        FAIL,
        /** At least one resource could not be probed. */
        ERROR,
        /** No resource is configured for this cloud. */
        NOT_CONFIGURED
    }

    /**
     * The answer for one resource.
     *
     * @param executor      the executor
     * @param scope         the trace scope
     * @param resource      the resource
     * @param principal     the identity probed
     * @param namedResource false for the unnamed-resource probe that catches a wildcard grant
     * @param expected      the permissions the trace gives the executor here
     * @param missing       expected permissions the identity lacks
     * @param extra         permissions the identity holds that the trace does not give it here
     * @param readerHeld    permissions held here that belong to the Reader role, not counted as extra
     * @param error         why the resource could not be probed, or {@code null}
     */
    public record TargetReport(String executor, String scope, String resource, String principal,
            boolean namedResource, List<String> expected, List<String> missing, List<String> extra,
            List<String> readerHeld, String error) {
    }
}
