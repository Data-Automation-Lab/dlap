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
import java.util.Set;

/**
 * Asks one cloud which permissions an identity holds, through the provider's own dry-run or
 * policy-simulation API. Implementations live under {@code permissioncheck/providers/<cloud>/};
 * nothing here knows a provider.
 */
public interface PermissionProbe {

    /**
     * A resource to probe.
     *
     * @param executor       the executor the Actor serves ({@code delete} or {@code archive})
     * @param scope          the trace scope of the resource, for example {@code bucket}
     * @param resource       the provider's resource name (an ARN, a bucket, an ARM id)
     * @param principal      the identity whose permissions are probed
     * @param grantExpected  false when the operator turned the scope off (object actions), so every
     *                       permission found there is extra
     * @param namedResource  false for a resource the operator never named, probed to catch a
     *                       wildcard grant
     */
    record Target(String executor, String scope, String resource, String principal, boolean grantExpected,
            boolean namedResource) {
    }

    /**
     * A provider's answer.
     *
     * @param allowed       the probed permissions the identity holds on the resource
     * @param grantedBeyond permissions the provider reports as held that were not probed and are not
     *                      acceptable (only a provider that lists effective permissions knows them)
     */
    record Answer(Set<String> allowed, Set<String> grantedBeyond) {
    }

    /** @return the cloud, matching the trace's {@code cloud} */
    String cloud();

    /** @return the provider API the probe uses, for the report */
    String method();

    /**
     * Whose permissions the provider API answers for. The default is a named role; a probe whose API
     * answers for whoever makes the call returns {@code CALLER} (Q-727).
     *
     * @return the basis, reported as {@code identityBasis}
     */
    default PermissionCheckReport.IdentityBasis identityBasis() {
        return PermissionCheckReport.IdentityBasis.NAMED_ROLE;
    }

    /**
     * One sentence for the report saying which identity the answer is about.
     *
     * @return the note, reported as {@code identityNote}
     */
    default String identityNote() {
        return "The provider is asked about the identity named in each target; it answers for that identity.";
    }

    /** @return the resources the operator configured, plus any unnamed-resource probes; empty when not configured */
    List<Target> targets();

    /**
     * Probes one resource.
     *
     * @param target     the resource
     * @param candidates the permissions to test
     * @param acceptable permissions the identity may hold there (expected or inherited); a provider
     *                   that lists effective permissions reports everything else as granted beyond
     * @return the answer
     * @throws RuntimeException when the provider cannot be asked; the message names the call and the
     *                          status, never a credential
     */
    Answer probe(Target target, Set<String> candidates, Set<String> acceptable);
}
