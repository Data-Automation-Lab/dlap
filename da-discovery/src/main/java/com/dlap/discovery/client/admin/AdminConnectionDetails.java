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

package com.dlap.discovery.client.admin;

import com.dlap.discovery.common.model.enums.CloudProvider;

/**
 * Connection details returned by {@link IAdminServiceClient#getConnectionDetails(String)}.
 *
 * <p>Extracted from the previously-nested {@code MockAdminServiceClient.ConnectionDetails}
 * inside {@code DiscoveryJobController} (R-P0-2 / finding N8). Hoisting the type to the
 * top level lets the real Feign-backed client (added in milestone M1) and the dev-only
 * mock both return the same shape.
 */
public final class AdminConnectionDetails {

    private final String accountId;
    private final CloudProvider provider;
    private final String connectionName;

    public AdminConnectionDetails(String accountId, CloudProvider provider, String connectionName) {
        this.accountId = accountId;
        this.provider = provider;
        this.connectionName = connectionName;
    }

    public String getAccountId() {
        return accountId;
    }

    public CloudProvider getProvider() {
        return provider;
    }

    public String getConnectionName() {
        return connectionName;
    }
}
