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

package com.dlap.adminservice.credential;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link CredentialProvider} for {@link Mode#FEDERATED_IDENTITY} - the recommended
 * production posture: DLAP stores <b>no</b> long-lived secret. It returns a federated
 * marker; the caller authenticates with the runtime's ambient identity (AWS STS
 * AssumeRole, GCP Workload Identity, Azure federated credentials) via the provider SDK,
 * using the non-secret {@code connectionParameters} (role ARN / audience / service account).
 * Active when {@code dlap.credentials.provider=federated}.
 */
@Component
@ConditionalOnProperty(name = "dlap.credentials.provider", havingValue = "federated")
public class FederatedIdentityCredentialProvider implements CredentialProvider {

    @Override
    public Mode mode() {
        return Mode.FEDERATED_IDENTITY;
    }

    @Override
    public Resolved resolve(Request request) {
        String provider = request == null ? "?" : request.providerType();
        return Resolved.federated("federated identity for provider " + provider
                + " - no stored secret; caller uses the ambient identity");
    }
}
