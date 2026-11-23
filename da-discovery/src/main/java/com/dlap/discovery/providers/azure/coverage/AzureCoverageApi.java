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

package com.dlap.discovery.providers.azure.coverage;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The Azure Resource Manager reads of the coverage collector. Each method returns the API response
 * as JSON in the shape of the public REST reference and throws
 * {@link com.dlap.discovery.coverage.MissingPermissionException} naming the Azure action when the
 * call is refused.
 */
public interface AzureCoverageApi {

    /**
     * Storage Accounts - List of a subscription, every page.
     *
     * @param subscriptionId the subscription
     * @return the storage account resources ({@code id}, {@code name})
     */
    List<JsonNode> listStorageAccounts(String subscriptionId);

    /**
     * Diagnostic Settings - List of {@code <accountId>/blobServices/default}.
     *
     * @param storageAccountId the storage account's ARM id
     * @return the response, with {@code value}
     */
    JsonNode listBlobDiagnosticSettings(String storageAccountId);
}
